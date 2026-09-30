package com.example.testproject.live;

import com.example.testproject.model.ResourceNode;
import com.example.testproject.service.ResourceNodeService;
import com.example.testproject.service.WorldService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/// The regular background tick for worlds where someone is playing: drops
/// new sticks and stones (and tells everyone there), and notes that the world is still
/// active, so a catch-up after everyone leaves starts from the right time
/// even if the server stops unexpectedly.
@Component
public class WorldTicker {

    private final ResourceNodeService resourceNodeService;
    private final WorldService worldService;
    private final WorldSessionRegistry registry;

    public WorldTicker(
            ResourceNodeService resourceNodeService,
            WorldService worldService,
            WorldSessionRegistry registry
    ) {
        this.resourceNodeService = resourceNodeService;
        this.worldService = worldService;
        this.registry = registry;
    }

    @Scheduled(
            initialDelay = ResourceNodeService.LOOSE_DROP_INTERVAL_MS,
            fixedDelay = ResourceNodeService.LOOSE_DROP_INTERVAL_MS
    )
    public void tick() {
        tickActiveWorlds(ResourceNodeService.LOOSE_DROP_CHANCE);
    }

    public void tickActiveWorlds(double looseDropChance) {
        for (long worldId : registry.activeWorldIds()) {
            worldService.markActive(worldId);
            for (ResourceNode node : resourceNodeService.dropLooseNodes(worldId, looseDropChance)) {
                registry.broadcast(worldId, LiveEvents.nodeAdded(ResourceNodeService.view(node)));
            }
        }
    }
}
