package com.example.testproject.live;

import com.example.testproject.model.ResourceNode;
import com.example.testproject.service.ResourceNodeService;
import com.example.testproject.service.WorldService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/// The regular background ticks for worlds where someone is playing: every
/// 0.5 s, the animals decide what to do; every 2 s, everyone's health and
/// hunger; every 20 s, new animals, new sticks and stones
/// (telling everyone there), expired item piles removed, and a note that the world is still
/// active, so a catch-up after everyone leaves starts from the right time
/// even if the server stops unexpectedly.
@Component
public class WorldTicker {

    private static final long VITALS_INTERVAL_MS = 2_000;
    private static final long ANIMAL_INTERVAL_MS = 500;

    private final ResourceNodeService resourceNodeService;
    private final WorldService worldService;
    private final WorldSessionRegistry registry;
    private final LiveWorldService liveWorldService;

    public WorldTicker(
            ResourceNodeService resourceNodeService,
            WorldService worldService,
            WorldSessionRegistry registry,
            LiveWorldService liveWorldService
    ) {
        this.resourceNodeService = resourceNodeService;
        this.worldService = worldService;
        this.registry = registry;
        this.liveWorldService = liveWorldService;
    }

    @Scheduled(
            initialDelay = ResourceNodeService.LOOSE_DROP_INTERVAL_MS,
            fixedDelay = ResourceNodeService.LOOSE_DROP_INTERVAL_MS
    )
    public void tick() {
        tickActiveWorlds(ResourceNodeService.LOOSE_DROP_CHANCE);
    }

    /// Animals decide what to do twice a second.
    @Scheduled(initialDelay = ANIMAL_INTERVAL_MS, fixedDelay = ANIMAL_INTERVAL_MS)
    public void tickAnimals() {
        for (long worldId : registry.activeWorldIds()) {
            liveWorldService.tickAnimals(worldId);
        }
    }

    /// Keeps everyone's health and hunger up to date, and notices when
    /// someone has starved.
    @Scheduled(initialDelay = VITALS_INTERVAL_MS, fixedDelay = VITALS_INTERVAL_MS)
    public void tickVitals() {
        for (long worldId : registry.activeWorldIds()) {
            for (long userId : registry.joinedUserIds(worldId)) {
                liveWorldService.checkVitals(worldId, userId);
            }
        }
    }

    public void tickActiveWorlds(double looseDropChance) {
        for (long worldId : registry.activeWorldIds()) {
            worldService.markActive(worldId);
            for (long pileId : resourceNodeService.removeExpiredPiles(worldId)) {
                registry.broadcast(worldId, LiveEvents.nodeRemoved(pileId));
            }
            liveWorldService.respawnAnimals(worldId);
            for (ResourceNode node : resourceNodeService.dropLooseNodes(worldId, looseDropChance)) {
                registry.broadcast(worldId, LiveEvents.nodeAdded(ResourceNodeService.view(node)));
            }
        }
    }
}
