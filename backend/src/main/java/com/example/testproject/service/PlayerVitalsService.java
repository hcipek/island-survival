package com.example.testproject.service;

import com.example.testproject.live.LiveEvents;
import com.example.testproject.live.WorldSessionRegistry;
import com.example.testproject.model.PlayerVitals;
import com.example.testproject.model.dto.PlayerStateDto;
import com.example.testproject.repository.PlayerVitalsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

/// Each player's hunger in each world: bringing it up to date, pausing it
/// while they're away, eating, and telling the player about it.
@Service
public class PlayerVitalsService {

    private static final double MAX_HUNGER = 100;
    /// From full to starving in 15 minutes of play.
    private static final double HUNGER_DRAIN_PER_SECOND = MAX_HUNGER / (15 * 60);
    private static final double HUNGER_PER_BERRY = 15;

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
            double hungrySeconds = Duration.between(vitals.getHungerSettledAt(), now).toMillis() / 1000.0;
            vitals.setHunger(Math.max(0, vitals.getHunger() - hungrySeconds * HUNGER_DRAIN_PER_SECOND));
            vitals.setHungerSettledAt(now);
        }
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

    /// Eating a berry (already taken from the player's hand).
    public synchronized PlayerVitals eatBerry(long worldId, long userId) {
        PlayerVitals vitals = current(worldId, userId);
        vitals.setHunger(Math.min(MAX_HUNGER, vitals.getHunger() + HUNGER_PER_BERRY));
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
        boolean hungerRunning = vitals.getHungerSettledAt() != null && vitals.getHunger() > 0;
        return new PlayerStateDto(vitals.getHunger(), hungerRunning ? HUNGER_DRAIN_PER_SECOND : 0);
    }
}
