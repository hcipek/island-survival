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
/// collected), spending it, adding to it, and telling the player about it.
@Service
public class PlayerResourcesService {

    private static final double WOOD_PER_LUMBERJACK_PER_SECOND = 0.5;

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
        double elapsedSeconds = Duration.between(resources.getLastCollectedAt(), now).toMillis() / 1000.0;
        resources.setWood(resources.getWood() + elapsedSeconds * woodPerSecond(worldId, userId));
        resources.setLastCollectedAt(now);
        return playerResourcesRepository.save(resources);
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
        return new PlayerStateDto(
                resources.getWood(),
                woodPerSecond(resources.getWorldId(), resources.getUserId()),
                resources.getFood()
        );
    }
}
