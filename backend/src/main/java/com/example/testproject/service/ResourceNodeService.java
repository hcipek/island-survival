package com.example.testproject.service;

import com.example.testproject.live.LiveEvents;
import com.example.testproject.model.Building;
import com.example.testproject.model.PlayerResources;
import com.example.testproject.model.ResourceNode;
import com.example.testproject.model.ResourceNodeType;
import com.example.testproject.model.WorldIsland;
import com.example.testproject.repository.BuildingRepository;
import com.example.testproject.repository.ResourceNodeRepository;
import com.example.testproject.repository.WorldIslandRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/// The trees, berry bushes and sticks on each island, and gathering from
/// them by hand.
///
/// Trees and bushes stay put - a picked bush regrows its berries in place.
/// Sticks are different: picking them up removes them, and new ones drop at
/// random free spots over time (see [dropSticks]), up to a limit per island.
@Service
public class ResourceNodeService {

    /// How close a player has to be standing. The client walks them to just
    /// next to the node first, so this only has to allow a little slack.
    private static final double GATHER_RANGE = 80;

    /// The client shows a 2 second progress ring before asking; this is a
    /// little shorter so normal play never trips it, but spamming does.
    private static final Duration GATHER_COOLDOWN = Duration.ofMillis(1500);

    private static final double FOOD_PER_BERRY_BUSH = 3;
    private static final double WOOD_PER_STICKS = 2;
    private static final Duration BERRY_BUSH_REGROW = Duration.ofMinutes(3);

    /// The most sticks lying around on an island at once.
    private static final int HOME_STICKS_CAP = 3;
    private static final int OUTER_STICKS_CAP = 4;

    /// How many of each the home island gets - kept sparse, since that's
    /// where everyone builds.
    private static final int HOME_TREES = 5;
    private static final int HOME_BUSHES = 2;

    /// Other islands get a random amount in this range of each, which makes
    /// them worth sailing to.
    private static final int OUTER_MIN_EACH = 2;
    private static final int OUTER_MAX_EACH = 4;

    /// Keep nodes on the grass, away from the coast, each other and
    /// buildings, and off the spot everyone spawns on in the middle of the
    /// home island.
    private static final double MAX_DISTANCE_AS_GRASS_FRACTION = 0.85;
    private static final double MIN_SPACING = 70;
    private static final double HOME_SPAWN_CLEARANCE = 100;
    private static final int MAX_PLACEMENT_ATTEMPTS = 40;

    private final ResourceNodeRepository resourceNodeRepository;
    private final WorldIslandRepository worldIslandRepository;
    private final BuildingRepository buildingRepository;
    private final PlayerResourcesService playerResourcesService;
    private final Random random = new Random();

    /// When each player (by world and user) last gathered, for the cooldown.
    private final Map<String, Instant> lastGatheredAt = new ConcurrentHashMap<>();

    public ResourceNodeService(
            ResourceNodeRepository resourceNodeRepository,
            WorldIslandRepository worldIslandRepository,
            BuildingRepository buildingRepository,
            PlayerResourcesService playerResourcesService
    ) {
        this.resourceNodeRepository = resourceNodeRepository;
        this.worldIslandRepository = worldIslandRepository;
        this.buildingRepository = buildingRepository;
        this.playerResourcesService = playerResourcesService;
    }

    /// What gathering did: the node - now regrowing, or [removed] entirely
    /// if it was sticks - and the player's updated resources.
    public record GatherResult(ResourceNode node, boolean removed, PlayerResources resources) {
    }

    /// Sticks that appeared, and ones that were cleared away.
    public record StickChanges(List<ResourceNode> added, List<Long> removedIds) {
    }

    /// [userId], standing at ([x], [y]), gathers from [nodeId] by hand.
    ///
    /// Synchronized so two players reaching the same bush at the same moment
    /// can't both get its berries.
    public synchronized GatherResult gather(long worldId, long userId, Long nodeId, double x, double y) {
        if (nodeId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nodeId is required");
        }
        ResourceNode node = resourceNodeRepository.findById(nodeId)
                .filter(n -> n.getWorldId().equals(worldId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nothing to gather there"));
        Instant now = Instant.now();

        if (node.getType() == ResourceNodeType.TREE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You'll need an axe to chop trees");
        }
        if (!node.isAvailable(now)) {
            long seconds = Math.max(1, regrowsInMillis(node, now) / 1000);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already picked - it regrows in " + seconds + "s");
        }
        if (Math.hypot(node.getX() - x, node.getY() - y) > GATHER_RANGE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Walk up to it first");
        }
        String playerKey = worldId + ":" + userId;
        Instant last = lastGatheredAt.get(playerKey);
        if (last != null && Duration.between(last, now).compareTo(GATHER_COOLDOWN) < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Still busy gathering");
        }

        PlayerResources resources = playerResourcesService.collected(worldId, userId);
        GatherResult result = switch (node.getType()) {
            case BERRY_BUSH -> {
                playerResourcesService.addFood(resources, FOOD_PER_BERRY_BUSH);
                node.setDepletedUntil(now.plus(BERRY_BUSH_REGROW));
                yield new GatherResult(resourceNodeRepository.save(node), false, resources);
            }
            case STICKS -> {
                // Berries are allowed while starving - otherwise you could
                // never recover.
                playerResourcesService.requireNotStarving(resources);
                playerResourcesService.addWood(resources, WOOD_PER_STICKS);
                resourceNodeRepository.delete(node);
                yield new GatherResult(node, true, resources);
            }
            case TREE -> throw new IllegalStateException("handled above");
        };
        lastGatheredAt.put(playerKey, now);
        return result;
    }

    /// Fills every island of [worldId] up to its stick limit at once - for
    /// when someone enters a world nobody was playing in, since sticks only
    /// drop over time while someone's there.
    ///
    /// Also clears away sticks from before they stopped regrowing in place.
    public synchronized StickChanges topUpSticks(long worldId) {
        List<ResourceNode> nodes = new ArrayList<>(resourceNodeRepository.findAllByWorldId(worldId));
        List<Long> removedIds = new ArrayList<>();
        for (ResourceNode node : List.copyOf(nodes)) {
            if (node.getType() == ResourceNodeType.STICKS && node.getDepletedUntil() != null) {
                resourceNodeRepository.delete(node);
                nodes.remove(node);
                removedIds.add(node.getId());
            }
        }

        List<ResourceNode> added = new ArrayList<>();
        for (WorldIsland island : worldIslandRepository.findAllByWorldId(worldId)) {
            int missing = sticksCap(island) - sticksOn(island, nodes);
            added.addAll(placeOn(island, ResourceNodeType.STICKS, missing, nodes, worldId));
        }
        return new StickChanges(resourceNodeRepository.saveAll(added), removedIds);
    }

    /// Each island that's below its stick limit has [chance] (0 to 1) of
    /// one new stick dropping at a random free spot. Called regularly while
    /// someone is in the world.
    public synchronized List<ResourceNode> dropSticks(long worldId, double chance) {
        List<ResourceNode> nodes = new ArrayList<>(resourceNodeRepository.findAllByWorldId(worldId));
        List<ResourceNode> added = new ArrayList<>();
        for (WorldIsland island : worldIslandRepository.findAllByWorldId(worldId)) {
            if (sticksOn(island, nodes) < sticksCap(island) && random.nextDouble() < chance) {
                added.addAll(placeOn(island, ResourceNodeType.STICKS, 1, nodes, worldId));
            }
        }
        return resourceNodeRepository.saveAll(added);
    }

    public static Map<String, Object> view(ResourceNode node) {
        return LiveEvents.node(
                node.getId(), node.getType(), node.getX(), node.getY(), regrowsInMillis(node, Instant.now()));
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
            placeOn(island, ResourceNodeType.TREE, island.isHome() ? HOME_TREES : outerCount(), placed, worldId);
            placeOn(island, ResourceNodeType.BERRY_BUSH, island.isHome() ? HOME_BUSHES : outerCount(), placed, worldId);
            placeOn(island, ResourceNodeType.STICKS, sticksCap(island), placed, worldId);
        }
        resourceNodeRepository.saveAll(placed);
    }

    /// Every node in [worldId], as clients need to draw them.
    public List<Map<String, Object>> nodesView(long worldId) {
        return resourceNodeRepository.findAllByWorldId(worldId).stream().map(ResourceNodeService::view).toList();
    }

    public void deleteAllIn(long worldId) {
        resourceNodeRepository.deleteAll(resourceNodeRepository.findAllByWorldId(worldId));
    }

    public static long regrowsInMillis(ResourceNode node, Instant now) {
        return node.isAvailable(now) ? 0 : Duration.between(now, node.getDepletedUntil()).toMillis();
    }

    private int outerCount() {
        return OUTER_MIN_EACH + random.nextInt(OUTER_MAX_EACH - OUTER_MIN_EACH + 1);
    }

    private static int sticksCap(WorldIsland island) {
        return island.isHome() ? HOME_STICKS_CAP : OUTER_STICKS_CAP;
    }

    private static int sticksOn(WorldIsland island, List<ResourceNode> nodes) {
        return (int) nodes.stream()
                .filter(n -> n.getType() == ResourceNodeType.STICKS && island.getId().equals(n.getIslandId()))
                .count();
    }

    /// Places up to [count] new nodes of [type] at random free spots on
    /// [island], adding them to [placed] (which also counts as taken space)
    /// and returning them. Gives up on a node if no free spot turns up.
    private List<ResourceNode> placeOn(
            WorldIsland island, ResourceNodeType type, int count, List<ResourceNode> placed, long worldId
    ) {
        List<Building> buildings = buildingRepository.findAllByWorldId(worldId);
        List<ResourceNode> added = new ArrayList<>();
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
                boolean clearOfBuildings = buildings.stream()
                        .allMatch(b -> Math.hypot(b.getX() - x, b.getY() - y) >= MIN_SPACING);
                if (clearOfSpawn && clearOfOthers && clearOfBuildings) {
                    ResourceNode node = new ResourceNode(island.getWorldId(), island.getId(), type, x, y);
                    placed.add(node);
                    added.add(node);
                    break;
                }
            }
        }
        return added;
    }
}
