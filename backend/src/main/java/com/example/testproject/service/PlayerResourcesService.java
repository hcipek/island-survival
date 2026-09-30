package com.example.testproject.service;

import com.example.testproject.live.LiveEvents;
import com.example.testproject.live.WorldSessionRegistry;
import com.example.testproject.model.BuildingType;
import com.example.testproject.model.PlayerResources;
import com.example.testproject.model.dto.PlayerStateDto;
import com.example.testproject.repository.BuildingRepository;
import com.example.testproject.repository.PlayerResourcesRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

/// Each player's own stock in each world: reading it (with production
/// collected and hunger brought up to date), spending it, adding to it,
/// eating, and telling the player about it.
@Service
public class PlayerResourcesService {

    private static final double WOOD_PER_LUMBERJACK_PER_SECOND = 0.5;

    private static final double MAX_HUNGER = 100;
    /// From full to starving in 15 minutes of play.
    private static final double HUNGER_DRAIN_PER_SECOND = MAX_HUNGER / (15 * 60);
    private static final double HUNGER_PER_FOOD = 15;

    /// What every player starts with in a world - enough to build a first
    /// lumberjack immediately.
    static final double STARTING_WOOD = WorldService.LUMBERJACK_COST;

    private final PlayerResourcesRepository playerResourcesRepository;
    private final BuildingRepository buildingRepository;
    private final WorldSessionRegistry sessionRegistry;

    public PlayerResourcesService(
            PlayerResourcesRepository playerResourcesRepository,
            BuildingRepository buildingRepository,
            WorldSessionRegistry sessionRegistry
    ) {
        this.playerResourcesRepository = playerResourcesRepository;
        this.buildingRepository = buildingRepository;
        this.sessionRegistry = sessionRegistry;
    }

    public PlayerStateDto stateOf(long worldId, long userId) {
        return toStateDto(collected(worldId, userId));
    }

    /// [userId]'s resources in [worldId], created with the starting amount
    /// the first time they're needed, and with everything their buildings
    /// produced since last time added on.
    ///
    /// Synchronized so two requests at once can't both create the row.
    public synchronized PlayerResources collected(long worldId, long userId) {
        PlayerResources resources = playerResourcesRepository.findByWorldIdAndUserId(worldId, userId)
                .orElseGet(() -> new PlayerResources(worldId, userId, STARTING_WOOD));

        Instant now = Instant.now();
        double elapsedSeconds = secondsBetween(resources.getLastCollectedAt(), now);
        resources.setWood(resources.getWood() + elapsedSeconds * woodPerSecond(worldId, userId));
        resources.setLastCollectedAt(now);

        if (resources.getHungerSettledAt() != null) {
            double hungrySeconds = secondsBetween(resources.getHungerSettledAt(), now);
            resources.setHunger(Math.max(0, resources.getHunger() - hungrySeconds * HUNGER_DRAIN_PER_SECOND));
            resources.setHungerSettledAt(now);
        }
        return playerResourcesRepository.save(resources);
    }

    /// The player came into the world (and wasn't already there in another
    /// tab): hunger starts dropping from now. Time away doesn't count.
    public synchronized PlayerResources playerEntered(long worldId, long userId) {
        PlayerResources resources = collected(worldId, userId);
        resources.setHungerSettledAt(Instant.now());
        return playerResourcesRepository.save(resources);
    }

    /// The player left the world: settle hunger up to now, then pause it.
    public synchronized void playerLeft(long worldId, long userId) {
        PlayerResources resources = collected(worldId, userId);
        resources.setHungerSettledAt(null);
        playerResourcesRepository.save(resources);
    }

    /// Eats one food.
    public synchronized PlayerResources eat(long worldId, long userId) {
        PlayerResources resources = collected(worldId, userId);
        if (resources.getFood() < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have no food - pick some berries");
        }
        if (resources.getHunger() >= MAX_HUNGER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You're not hungry");
        }
        resources.setFood(resources.getFood() - 1);
        resources.setHunger(Math.min(MAX_HUNGER, resources.getHunger() + HUNGER_PER_FOOD));
        return playerResourcesRepository.save(resources);
    }

    /// At zero hunger a player can still move and pick berries, but nothing
    /// else until they eat.
    public void requireNotStarving(PlayerResources resources) {
        if (resources.getHunger() <= 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You're starving - eat something first");
        }
    }

    public void spendWood(PlayerResources resources, double cost, String what) {
        if (resources.getWood() < cost) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Not enough wood to build " + what + " (need " + (int) cost + ")"
            );
        }
        resources.setWood(resources.getWood() - cost);
        playerResourcesRepository.save(resources);
    }

    public void addWood(PlayerResources resources, double amount) {
        resources.setWood(resources.getWood() + amount);
        playerResourcesRepository.save(resources);
    }

    public void addFood(PlayerResources resources, double amount) {
        resources.setFood(resources.getFood() + amount);
        playerResourcesRepository.save(resources);
    }

    /// Resources are private, so the update goes only to that player's own
    /// connections (they may have the world open in several tabs).
    public void sendStateTo(PlayerResources resources) {
        sessionRegistry.sendToUser(
                resources.getWorldId(), resources.getUserId(), LiveEvents.state(toStateDto(resources)));
    }

    public void deleteAllIn(long worldId) {
        playerResourcesRepository.deleteAll(playerResourcesRepository.findAllByWorldId(worldId));
    }

    /// A player's lumberjacks only produce for that player.
    private double woodPerSecond(long worldId, long userId) {
        long lumberjacks = buildingRepository.countByWorldIdAndOwnerIdAndType(worldId, userId, BuildingType.LUMBERJACK);
        return lumberjacks * WOOD_PER_LUMBERJACK_PER_SECOND;
    }

    private PlayerStateDto toStateDto(PlayerResources resources) {
        boolean hungerRunning = resources.getHungerSettledAt() != null && resources.getHunger() > 0;
        return new PlayerStateDto(
                resources.getWood(),
                woodPerSecond(resources.getWorldId(), resources.getUserId()),
                resources.getFood(),
                resources.getHunger(),
                hungerRunning ? HUNGER_DRAIN_PER_SECOND : 0
        );
    }

    private static double secondsBetween(Instant from, Instant to) {
        return Duration.between(from, to).toMillis() / 1000.0;
    }
}
