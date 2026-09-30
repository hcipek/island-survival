package com.example.testproject.live;

import com.example.testproject.model.ResourceNode;
import com.example.testproject.service.ResourceNodeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/// Every so often, drops new sticks on islands in worlds where someone is
/// playing, and tells everyone there.
@Component
public class StickSpawner {

    /// Each island below its stick limit gets a new stick with this chance
    /// per tick - so about one every 40 seconds, at an irregular pace.
    private static final double CHANCE_PER_TICK = 0.5;

    private final ResourceNodeService resourceNodeService;
    private final WorldSessionRegistry registry;

    public StickSpawner(ResourceNodeService resourceNodeService, WorldSessionRegistry registry) {
        this.resourceNodeService = resourceNodeService;
        this.registry = registry;
    }

    @Scheduled(initialDelay = 20_000, fixedDelay = 20_000)
    public void tick() {
        dropSticksInActiveWorlds(CHANCE_PER_TICK);
    }

    public void dropSticksInActiveWorlds(double chance) {
        for (long worldId : registry.activeWorldIds()) {
            for (ResourceNode node : resourceNodeService.dropSticks(worldId, chance)) {
                registry.broadcast(worldId, LiveEvents.nodeAdded(ResourceNodeService.view(node)));
            }
        }
    }
}
