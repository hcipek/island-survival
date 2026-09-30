package com.example.testproject.service;

import com.example.testproject.live.LiveEvents;
import com.example.testproject.model.ResourceNode;
import com.example.testproject.model.ResourceNodeType;
import com.example.testproject.model.WorldIsland;
import com.example.testproject.repository.ResourceNodeRepository;
import com.example.testproject.repository.WorldIslandRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/// The trees, berry bushes and sticks on each island.
@Service
public class ResourceNodeService {

    /// How many of each the home island gets - kept sparse, since that's
    /// where everyone builds.
    private static final int HOME_TREES = 5;
    private static final int HOME_BUSHES = 2;
    private static final int HOME_STICKS = 2;

    /// Other islands get a random amount in this range of each, which makes
    /// them worth sailing to.
    private static final int OUTER_MIN_EACH = 2;
    private static final int OUTER_MAX_EACH = 4;

    /// Keep nodes on the grass, away from the coast and each other, and off
    /// the spot everyone spawns on in the middle of the home island.
    private static final double MAX_DISTANCE_AS_GRASS_FRACTION = 0.85;
    private static final double MIN_SPACING = 70;
    private static final double HOME_SPAWN_CLEARANCE = 100;
    private static final int MAX_PLACEMENT_ATTEMPTS = 40;

    private final ResourceNodeRepository resourceNodeRepository;
    private final WorldIslandRepository worldIslandRepository;
    private final Random random = new Random();

    public ResourceNodeService(
            ResourceNodeRepository resourceNodeRepository,
            WorldIslandRepository worldIslandRepository
    ) {
        this.resourceNodeRepository = resourceNodeRepository;
        this.worldIslandRepository = worldIslandRepository;
    }

    /// Places nodes on every island of [worldId] unless it already has
    /// some - so worlds created before nodes existed get them the first time
    /// they're loaded.
    public synchronized void ensureNodes(long worldId) {
        if (resourceNodeRepository.existsByWorldId(worldId)) {
            return;
        }
        List<ResourceNode> placed = new ArrayList<>();
        for (WorldIsland island : worldIslandRepository.findAllByWorldId(worldId)) {
            placeOn(island, ResourceNodeType.TREE, island.isHome() ? HOME_TREES : outerCount(), placed);
            placeOn(island, ResourceNodeType.BERRY_BUSH, island.isHome() ? HOME_BUSHES : outerCount(), placed);
            placeOn(island, ResourceNodeType.STICKS, island.isHome() ? HOME_STICKS : outerCount(), placed);
        }
        resourceNodeRepository.saveAll(placed);
    }

    /// Every node in [worldId], as clients need to draw them.
    public List<Map<String, Object>> nodesView(long worldId) {
        Instant now = Instant.now();
        return resourceNodeRepository.findAllByWorldId(worldId).stream()
                .map(node -> LiveEvents.node(
                        node.getId(), node.getType(), node.getX(), node.getY(), regrowsInMillis(node, now)))
                .toList();
    }

    public void deleteAllIn(long worldId) {
        resourceNodeRepository.deleteAll(resourceNodeRepository.findAllByWorldId(worldId));
    }

    static long regrowsInMillis(ResourceNode node, Instant now) {
        return node.isAvailable(now) ? 0 : Duration.between(now, node.getDepletedUntil()).toMillis();
    }

    private int outerCount() {
        return OUTER_MIN_EACH + random.nextInt(OUTER_MAX_EACH - OUTER_MIN_EACH + 1);
    }

    private void placeOn(WorldIsland island, ResourceNodeType type, int count, List<ResourceNode> placed) {
        double maxDistance = island.grassRadius() * MAX_DISTANCE_AS_GRASS_FRACTION;
        for (int i = 0; i < count; i++) {
            for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
                // sqrt spreads points evenly over the area instead of
                // bunching them up in the middle.
                double distance = Math.sqrt(random.nextDouble()) * maxDistance;
                double angle = random.nextDouble() * 2 * Math.PI;
                double x = island.getCenterX() + Math.cos(angle) * distance;
                double y = island.getCenterY() + Math.sin(angle) * distance;

                boolean clearOfSpawn = !island.isHome() || distance >= HOME_SPAWN_CLEARANCE;
                boolean clearOfOthers = placed.stream()
                        .allMatch(other -> Math.hypot(other.getX() - x, other.getY() - y) >= MIN_SPACING);
                if (clearOfSpawn && clearOfOthers) {
                    placed.add(new ResourceNode(island.getWorldId(), island.getId(), type, x, y));
                    break;
                }
            }
        }
    }
}
