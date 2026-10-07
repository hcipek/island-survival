package com.example.testproject.service;

import com.example.testproject.live.LiveEvents;
import com.example.testproject.live.WorldSessionRegistry;
import com.example.testproject.model.ItemType;
import com.example.testproject.model.PlayerVitals;
import com.example.testproject.model.dto.PlayerStateDto;
import com.example.testproject.repository.PlayerVitalsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

/// Each player's hunger and health in each world: bringing them up to date,
/// pausing them while they're away, eating, getting hurt, and telling the
/// player about it.
@Service
public class PlayerVitalsService {

    private static final double MAX_HUNGER = 100;
    /// From full to starving in 15 minutes of play.
    private static final double HUNGER_DRAIN_PER_SECOND = MAX_HUNGER / (15 * 60);
    private static final double HUNGER_PER_BERRY = 15;
    private static final double HUNGER_PER_MEAT = 35;

    static final double MAX_HEALTH = 100;
    /// Health slowly comes back while you're well fed: empty to full in
    /// about 8 minutes.
    private static final double WELL_FED_HUNGER = 50;
    private static final double HEALTH_REGEN_PER_SECOND = MAX_HEALTH / (8 * 60);
    /// Starving costs health: full to dead in about 4 minutes.
    private static final double STARVING_DAMAGE_PER_SECOND = MAX_HEALTH / (4 * 60);
    /// Coming back after dying: fed enough not to starve again right away.
    private static final double RESPAWN_HUNGER = 50;

    private final PlayerVitalsRepository playerVitalsRepository;
    private final WorldSessionRegistry sessionRegistry;

    public PlayerVitalsService(PlayerVitalsRepository playerVitalsRepository, WorldSessionRegistry sessionRegistry) {
        this.playerVitalsRepository = playerVitalsRepository;
        this.sessionRegistry = sessionRegistry;
    }

    public PlayerStateDto stateOf(long worldId, long userId) {
        return toStateDto(current(worldId, userId));
    }

    /// [userId]'s vitals in [worldId], created the first time they're
    /// needed, with the hunger lost since last time taken off.
    ///
    /// Synchronized so two requests at once can't both create the row.
    public synchronized PlayerVitals current(long worldId, long userId) {
        PlayerVitals vitals = playerVitalsRepository.findByWorldIdAndUserId(worldId, userId)
                .orElseGet(() -> new PlayerVitals(worldId, userId));

        if (vitals.getHungerSettledAt() != null) {
            Instant now = Instant.now();
            double seconds = Duration.between(vitals.getHungerSettledAt(), now).toMillis() / 1000.0;
            settle(vitals, seconds);
            vitals.setHungerSettledAt(now);
        }
        return playerVitalsRepository.save(vitals);
    }

    /// Moves hunger and health on by [seconds]. Hunger drops steadily, and
    /// during that time it can pass "well fed" (health comes back) and then
    /// reach zero (health drops) - so the time is split into those parts.
    static void settle(PlayerVitals vitals, double seconds) {
        double hungerBefore = vitals.getHunger();
        double secondsWellFed = hungerBefore > WELL_FED_HUNGER
                ? Math.min(seconds, (hungerBefore - WELL_FED_HUNGER) / HUNGER_DRAIN_PER_SECOND)
                : 0;
        double secondsUntilStarving = hungerBefore / HUNGER_DRAIN_PER_SECOND;
        double secondsStarving = Math.max(0, seconds - secondsUntilStarving);

        vitals.setHunger(Math.max(0, hungerBefore - seconds * HUNGER_DRAIN_PER_SECOND));
        // Being well fed always comes before starving, so heal (up to full)
        // first, then take the damage.
        double healed = Math.min(MAX_HEALTH, vitals.getHealth() + secondsWellFed * HEALTH_REGEN_PER_SECOND);
        vitals.setHealth(Math.max(0, healed - secondsStarving * STARVING_DAMAGE_PER_SECOND));
    }

    /// Takes [amount] of health, e.g. a boar's bite. Returns the player's
    /// vitals afterwards - health 0 means they died.
    public synchronized PlayerVitals hurt(long worldId, long userId, double amount) {
        PlayerVitals vitals = current(worldId, userId);
        vitals.setHealth(Math.max(0, vitals.getHealth() - amount));
        return playerVitalsRepository.save(vitals);
    }

    /// Back on their feet after dying: full health, and fed enough not to
    /// starve again straight away.
    public synchronized PlayerVitals respawn(long worldId, long userId) {
        PlayerVitals vitals = current(worldId, userId);
        vitals.setHealth(MAX_HEALTH);
        vitals.setHunger(Math.max(vitals.getHunger(), RESPAWN_HUNGER));
        return playerVitalsRepository.save(vitals);
    }

    /// The player came into the world (and wasn't already there in another
    /// tab): hunger starts dropping from now. Time away doesn't count.
    public synchronized PlayerVitals playerEntered(long worldId, long userId) {
        PlayerVitals vitals = current(worldId, userId);
        vitals.setHungerSettledAt(Instant.now());
        return playerVitalsRepository.save(vitals);
    }

    /// The player left the world: settle hunger up to now, then pause it.
    public synchronized void playerLeft(long worldId, long userId) {
        PlayerVitals vitals = current(worldId, userId);
        vitals.setHungerSettledAt(null);
        playerVitalsRepository.save(vitals);
    }

    /// Fails if eating wouldn't do anything, so no berry is wasted.
    public void requireHungry(long worldId, long userId) {
        if (current(worldId, userId).getHunger() >= MAX_HUNGER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You're not hungry");
        }
    }

    /// Whether [item] can be eaten.
    public static boolean isFood(ItemType item) {
        return item == ItemType.BERRY || item == ItemType.MEAT;
    }

    /// Eating [food] (already taken from the player's hand). Meat fills you
    /// up more than berries.
    public synchronized PlayerVitals eat(long worldId, long userId, ItemType food) {
        PlayerVitals vitals = current(worldId, userId);
        double fills = food == ItemType.MEAT ? HUNGER_PER_MEAT : HUNGER_PER_BERRY;
        vitals.setHunger(Math.min(MAX_HUNGER, vitals.getHunger() + fills));
        return playerVitalsRepository.save(vitals);
    }

    /// At zero hunger a player can still move and pick berries, but nothing
    /// else until they eat.
    public void requireNotStarving(long worldId, long userId) {
        if (current(worldId, userId).getHunger() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You're starving - eat something first");
        }
    }

    /// Vitals are private, so the update goes only to that player's own
    /// connections (they may have the world open in several tabs).
    public void sendStateTo(PlayerVitals vitals) {
        sessionRegistry.sendToUser(vitals.getWorldId(), vitals.getUserId(), LiveEvents.state(toStateDto(vitals)));
    }

    public void deleteAllIn(long worldId) {
        playerVitalsRepository.deleteAll(playerVitalsRepository.findAllByWorldId(worldId));
    }

    private PlayerStateDto toStateDto(PlayerVitals vitals) {
        boolean inWorld = vitals.getHungerSettledAt() != null;
        double healthChange = 0;
        if (inWorld && vitals.getHunger() <= 0) {
            healthChange = -STARVING_DAMAGE_PER_SECOND;
        } else if (inWorld && vitals.getHunger() > WELL_FED_HUNGER && vitals.getHealth() < MAX_HEALTH) {
            healthChange = HEALTH_REGEN_PER_SECOND;
        }
        return new PlayerStateDto(
                vitals.getHunger(),
                inWorld && vitals.getHunger() > 0 ? HUNGER_DRAIN_PER_SECOND : 0,
                vitals.getHealth(),
                healthChange
        );
    }
}
