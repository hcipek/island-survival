package com.example.testproject.service;

import com.example.testproject.live.LiveEvents;
import com.example.testproject.model.Building;
import com.example.testproject.model.ItemType;
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

    private static final int BERRIES_PER_BUSH = 3;
    private static final int STICKS_PER_PILE = 2;
    private static final Duration BERRY_BUSH_REGROW = Duration.ofMinutes(3);

    /// The most sticks lying around on an island at once.
    private static final int HOME_STICKS_CAP = 3;
    private static final int OUTER_STICKS_CAP = 4;

    /// How often sticks may drop while someone's playing, and the chance for
    /// each island below its limit each time - about one every 40 seconds.
    public static final long STICK_DROP_INTERVAL_MS = 20_000;
    public static final double STICK_DROP_CHANCE = 0.5;

    /// A safety limit on how many missed ticks a catch-up simulates. Islands
    /// are full long before this (a few dozen ticks), so it only bounds the
    /// work after very long absences.
    private static final long MAX_CATCH_UP_TICKS = 1_000;

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
    private final PlayerVitalsService playerVitalsService;
    private final InventoryService inventoryService;
    private final Random random = new Random();

    /// When each player (by world and user) last gathered, for the cooldown.
    private final Map<String, Instant> lastGatheredAt = new ConcurrentHashMap<>();

    public ResourceNodeService(
            ResourceNodeRepository resourceNodeRepository,
            WorldIslandRepository worldIslandRepository,
            BuildingRepository buildingRepository,
            PlayerVitalsService playerVitalsService,
            InventoryService inventoryService
    ) {
        this.resourceNodeRepository = resourceNodeRepository;
        this.worldIslandRepository = worldIslandRepository;
        this.buildingRepository = buildingRepository;
        this.playerVitalsService = playerVitalsService;
        this.inventoryService = inventoryService;
    }

    /// What gathering did to the node: now regrowing, or [removed] entirely
    /// if it was sticks. What it gave went into the player's inventory.
    public record GatherResult(ResourceNode node, boolean removed) {
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

        // Items go in first: if the bag is full, this fails and the node is
        // left as it was.
        GatherResult result = switch (node.getType()) {
            case BERRY_BUSH -> {
                inventoryService.add(worldId, userId, ItemType.BERRY, BERRIES_PER_BUSH);
                node.setDepletedUntil(now.plus(BERRY_BUSH_REGROW));
                yield new GatherResult(resourceNodeRepository.save(node), false);
            }
            case STICKS -> {
                // Berries are allowed while starving - otherwise you could
                // never recover.
                playerVitalsService.requireNotStarving(worldId, userId);
                inventoryService.add(worldId, userId, ItemType.STICK, STICKS_PER_PILE);
                resourceNodeRepository.delete(node);
                yield new GatherResult(node, true);
            }
            case TREE -> throw new IllegalStateException("handled above");
        };
        lastGatheredAt.put(playerKey, now);
        return result;
    }

    /// Adds the sticks that would have dropped while nobody was in [worldId]:
    /// the same rolls the regular tick makes, once for every tick missed
    /// since [lastActiveAt] (as many as needed if it's null). So coming back
    /// after a few seconds adds nothing, and after a few minutes the islands
    /// are most likely full again.
    ///
    /// Also clears away sticks from before they stopped regrowing in place.
    public synchronized StickChanges catchUpSticks(long worldId, Instant lastActiveAt) {
        long missedTicks = lastActiveAt == null
                ? MAX_CATCH_UP_TICKS
                : Math.min(MAX_CATCH_UP_TICKS,
                        Duration.between(lastActiveAt, Instant.now()).toMillis() / STICK_DROP_INTERVAL_MS);

        List<ResourceNode> nodes = new ArrayList<>(resourceNodeRepository.findAllByWorldId(worldId));
        List<Long> removedIds = new ArrayList<>();
        for (ResourceNode node : List.copyOf(nodes)) {
            if (node.getType() == ResourceNodeType.STICKS && node.getDepletedUntil() != null) {
                resourceNodeRepository.delete(node);
                nodes.remove(node);
                removedIds.add(node.getId());
            }
        }

        List<ResourceNode> added = simulateDrops(worldId, nodes, missedTicks, STICK_DROP_CHANCE);
        return new StickChanges(resourceNodeRepository.saveAll(added), removedIds);
    }

    /// One tick: each island that's below its stick limit has [chance] (0 to
    /// 1) of a new stick dropping at a random free spot. Called regularly
    /// while someone is in the world.
    public synchronized List<ResourceNode> dropSticks(long worldId, double chance) {
        List<ResourceNode> nodes = new ArrayList<>(resourceNodeRepository.findAllByWorldId(worldId));
        return resourceNodeRepository.saveAll(simulateDrops(worldId, nodes, 1, chance));
    }

    /// Runs [ticks] rounds of drop rolls in memory, adding the new sticks to
    /// [nodes]. Everything is loaded once up front and it stops as soon as
    /// no island has room left, so catching up on a long absence costs the
    /// same few queries as a single tick.
    private List<ResourceNode> simulateDrops(long worldId, List<ResourceNode> nodes, long ticks, double chance) {
        List<Building> buildings = buildingRepository.findAllByWorldId(worldId);
        List<WorldIsland> withRoom = new ArrayList<>(worldIslandRepository.findAllByWorldId(worldId).stream()
                .filter(island -> sticksOn(island, nodes) < sticksCap(island))
                .toList());

        List<ResourceNode> added = new ArrayList<>();
        for (long tick = 0; tick < ticks && !withRoom.isEmpty(); tick++) {
            for (WorldIsland island : List.copyOf(withRoom)) {
                if (random.nextDouble() >= chance) {
                    continue;
                }
                List<ResourceNode> dropped = placeOn(island, ResourceNodeType.STICKS, 1, nodes, buildings);
                added.addAll(dropped);
                // Full now, or so crowded there's no free spot left.
                if (dropped.isEmpty() || sticksOn(island, nodes) >= sticksCap(island)) {
                    withRoom.remove(island);
                }
            }
        }
        return added;
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
        List<Building> buildings = buildingRepository.findAllByWorldId(worldId);
        for (WorldIsland island : worldIslandRepository.findAllByWorldId(worldId)) {
            placeOn(island, ResourceNodeType.TREE, island.isHome() ? HOME_TREES : outerCount(), placed, buildings);
            placeOn(island, ResourceNodeType.BERRY_BUSH, island.isHome() ? HOME_BUSHES : outerCount(), placed, buildings);
            placeOn(island, ResourceNodeType.STICKS, sticksCap(island), placed, buildings);
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
            WorldIsland island, ResourceNodeType type, int count, List<ResourceNode> placed, List<Building> buildings
    ) {
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
