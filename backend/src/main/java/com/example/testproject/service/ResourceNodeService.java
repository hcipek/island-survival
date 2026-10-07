package com.example.testproject.service;

import com.example.testproject.live.LiveEvents;
import com.example.testproject.model.Building;
import com.example.testproject.model.ItemType;
import com.example.testproject.model.PileItem;
import com.example.testproject.model.ResourceNode;
import com.example.testproject.model.ResourceNodeType;
import com.example.testproject.model.WorldIsland;
import com.example.testproject.repository.BuildingRepository;
import com.example.testproject.repository.PileItemRepository;
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

/// The trees, berry bushes, sticks and stones on each island, and gathering
/// from them by hand.
///
/// Trees and bushes stay put - a picked bush regrows its berries in place.
/// Sticks and stones are loose: picking them up removes them, and new ones
/// drop at random free spots over time (see [dropLooseNodes]), up to a limit
/// per island.
@Service
public class ResourceNodeService {

    /// How close a player has to be standing. The client walks them to just
    /// next to the node first, so this only has to allow a little slack.
    private static final double GATHER_RANGE = 80;

    /// The client shows a 2 second progress ring before asking; this is a
    /// little shorter so normal play never trips it, but spamming does.
    private static final Duration GATHER_COOLDOWN = Duration.ofMillis(1500);

    private static final int BERRIES_PER_BUSH = 3;
    /// A pile of sticks is just a little wood.
    private static final int WOOD_PER_STICK_PILE = 2;
    /// Stones are scarcer than sticks: one each.
    private static final int STONES_PER_PILE = 1;
    private static final Duration BERRY_BUSH_REGROW = Duration.ofMinutes(3);

    /// A chopped tree leaves a stump that grows back into a tree.
    private static final int WOOD_PER_TREE = 3;
    private static final Duration TREE_REGROW = Duration.ofMinutes(5);

    /// How long a dead player's dropped bag stays before it disappears.
    static final Duration PILE_LIFETIME = Duration.ofMinutes(10);

    /// The most of each loose type lying around on an island at once.
    private static final int HOME_STICKS_CAP = 3;
    private static final int OUTER_STICKS_CAP = 4;
    private static final int HOME_STONES_CAP = 2;
    private static final int OUTER_STONES_CAP = 3;

    /// How often loose nodes may drop while someone's playing, and the chance
    /// for each island below its limit (for each type) each time - about one
    /// every 40 seconds.
    public static final long LOOSE_DROP_INTERVAL_MS = 20_000;
    public static final double LOOSE_DROP_CHANCE = 0.5;

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
    private final PileItemRepository pileItemRepository;
    private final Random random = new Random();

    /// When each player (by world and user) last gathered, for the cooldown.
    private final Map<String, Instant> lastGatheredAt = new ConcurrentHashMap<>();

    public ResourceNodeService(
            ResourceNodeRepository resourceNodeRepository,
            WorldIslandRepository worldIslandRepository,
            BuildingRepository buildingRepository,
            PlayerVitalsService playerVitalsService,
            InventoryService inventoryService,
            PileItemRepository pileItemRepository
    ) {
        this.resourceNodeRepository = resourceNodeRepository;
        this.worldIslandRepository = worldIslandRepository;
        this.buildingRepository = buildingRepository;
        this.playerVitalsService = playerVitalsService;
        this.inventoryService = inventoryService;
        this.pileItemRepository = pileItemRepository;
    }

    /// What gathering did to the node: now regrowing, or [removed] entirely
    /// if it was loose. What it gave went into the player's inventory.
    public record GatherResult(ResourceNode node, boolean removed) {
    }

    /// Loose nodes that appeared, and ones that were cleared away.
    public record LooseNodeChanges(List<ResourceNode> added, List<Long> removedIds) {
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
        if (node.isExpired(now)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "It's gone");
        }

        if (node.getType() == ResourceNodeType.TREE && inventoryService.held(worldId, userId) != ItemType.AXE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold an axe to chop trees");
        }
        if (!node.isAvailable(now)) {
            long seconds = Math.max(1, regrowsInMillis(node, now) / 1000);
            String what = node.getType() == ResourceNodeType.TREE ? "Just a stump - it grows back" : "Already picked - it regrows";
            throw new ResponseStatusException(HttpStatus.CONFLICT, what + " in " + seconds + "s");
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
                inventoryService.add(worldId, userId, ItemType.WOOD, WOOD_PER_STICK_PILE);
                resourceNodeRepository.delete(node);
                yield new GatherResult(node, true);
            }
            case STONES -> {
                playerVitalsService.requireNotStarving(worldId, userId);
                inventoryService.add(worldId, userId, ItemType.STONE, STONES_PER_PILE);
                resourceNodeRepository.delete(node);
                yield new GatherResult(node, true);
            }
            case ITEM_PILE -> pickUpPile(worldId, userId, node);
            case TREE -> {
                playerVitalsService.requireNotStarving(worldId, userId);
                inventoryService.add(worldId, userId, ItemType.WOOD, WOOD_PER_TREE);
                node.setDepletedUntil(now.plus(TREE_REGROW));
                yield new GatherResult(resourceNodeRepository.save(node), false);
            }
        };
        lastGatheredAt.put(playerKey, now);
        return result;
    }

    /// Adds the loose nodes that would have dropped while nobody was in
    /// [worldId]:
    /// the same rolls the regular tick makes, once for every tick missed
    /// since [lastActiveAt] (as many as needed if it's null). So coming back
    /// after a few seconds adds nothing, and after a few minutes the islands
    /// are most likely full again.
    ///
    /// Also clears away sticks from before they stopped regrowing in place.
    public synchronized LooseNodeChanges catchUpLooseNodes(long worldId, Instant lastActiveAt) {
        long missedTicks = lastActiveAt == null
                ? MAX_CATCH_UP_TICKS
                : Math.min(MAX_CATCH_UP_TICKS,
                        Duration.between(lastActiveAt, Instant.now()).toMillis() / LOOSE_DROP_INTERVAL_MS);

        List<ResourceNode> nodes = new ArrayList<>(resourceNodeRepository.findAllByWorldId(worldId));
        List<Long> removedIds = new ArrayList<>();
        for (ResourceNode node : List.copyOf(nodes)) {
            if (node.getType() == ResourceNodeType.STICKS && node.getDepletedUntil() != null) {
                resourceNodeRepository.delete(node);
                nodes.remove(node);
                removedIds.add(node.getId());
            }
        }

        List<ResourceNode> added = simulateDrops(worldId, nodes, missedTicks, LOOSE_DROP_CHANCE);
        return new LooseNodeChanges(resourceNodeRepository.saveAll(added), removedIds);
    }

    /// One tick: for each island and each loose type it's below the limit
    /// of, there's [chance] (0 to 1) of a new one dropping at a random free
    /// spot. Called regularly while someone is in the world.
    public synchronized List<ResourceNode> dropLooseNodes(long worldId, double chance) {
        List<ResourceNode> nodes = new ArrayList<>(resourceNodeRepository.findAllByWorldId(worldId));
        return resourceNodeRepository.saveAll(simulateDrops(worldId, nodes, 1, chance));
    }

    /// Runs [ticks] rounds of drop rolls in memory, adding the new nodes to
    /// [nodes]. Everything is loaded once up front and it stops as soon as
    /// no island has room left, so catching up on a long absence costs the
    /// same few queries as a single tick.
    private List<ResourceNode> simulateDrops(long worldId, List<ResourceNode> nodes, long ticks, double chance) {
        record Spot(WorldIsland island, ResourceNodeType type) {
        }
        List<Building> buildings = buildingRepository.findAllByWorldId(worldId);
        List<Spot> withRoom = new ArrayList<>();
        for (WorldIsland island : worldIslandRepository.findAllByWorldId(worldId)) {
            for (ResourceNodeType type : ResourceNodeType.values()) {
                if (type.isLoose() && looseOn(island, type, nodes) < looseCap(island, type)) {
                    withRoom.add(new Spot(island, type));
                }
            }
        }

        List<ResourceNode> added = new ArrayList<>();
        for (long tick = 0; tick < ticks && !withRoom.isEmpty(); tick++) {
            for (Spot spot : List.copyOf(withRoom)) {
                if (random.nextDouble() >= chance) {
                    continue;
                }
                List<ResourceNode> dropped = placeOn(spot.island(), spot.type(), 1, nodes, buildings);
                added.addAll(dropped);
                // Full now, or so crowded there's no free spot left.
                if (dropped.isEmpty()
                        || looseOn(spot.island(), spot.type(), nodes) >= looseCap(spot.island(), spot.type())) {
                    withRoom.remove(spot);
                }
            }
        }
        return added;
    }

    public static Map<String, Object> view(ResourceNode node) {
        Instant now = Instant.now();
        Long expiresInMs = node.getExpiresAt() == null ? null : Duration.between(now, node.getExpiresAt()).toMillis();
        return LiveEvents.node(
                node.getId(), node.getType(), node.getX(), node.getY(), regrowsInMillis(node, now),
                node.getOwnerId(), expiresInMs);
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
            placeOn(island, ResourceNodeType.STICKS, looseCap(island, ResourceNodeType.STICKS), placed, buildings);
            placeOn(island, ResourceNodeType.STONES, looseCap(island, ResourceNodeType.STONES), placed, buildings);
        }
        resourceNodeRepository.saveAll(placed);
    }

    /// Every node in [worldId], as clients need to draw them.
    public List<Map<String, Object>> nodesView(long worldId) {
        Instant now = Instant.now();
        return resourceNodeRepository.findAllByWorldId(worldId).stream()
                .filter(node -> !node.isExpired(now))
                .map(ResourceNodeService::view)
                .toList();
    }

    public void deleteAllIn(long worldId) {
        for (ResourceNode node : resourceNodeRepository.findAllByWorldId(worldId)) {
            pileItemRepository.deleteAll(pileItemRepository.findAllByNodeId(node.getId()));
            resourceNodeRepository.delete(node);
        }
    }

    /// Leaves [items] in a pile at ([x], [y]) that only [ownerId] can pick
    /// up, until it disappears. Returns null if there was nothing to leave.
    public synchronized ResourceNode leavePile(long worldId, long ownerId, double x, double y, List<InventoryService.Stack> items) {
        if (items.isEmpty()) {
            return null;
        }
        WorldIsland island = worldIslandRepository.findAllByWorldId(worldId).stream()
                .min((a, b) -> Double.compare(
                        Math.hypot(a.getCenterX() - x, a.getCenterY() - y) - a.getRadius(),
                        Math.hypot(b.getCenterX() - x, b.getCenterY() - y) - b.getRadius()))
                .orElseThrow();
        ResourceNode pile = new ResourceNode(worldId, island.getId(), ResourceNodeType.ITEM_PILE, x, y);
        pile.setOwnerId(ownerId);
        pile.setExpiresAt(Instant.now().plus(PILE_LIFETIME));
        pile = resourceNodeRepository.save(pile);
        for (InventoryService.Stack stack : items) {
            pileItemRepository.save(new PileItem(pile.getId(), stack.item(), stack.quantity()));
        }
        return pile;
    }

    /// Removes item piles that have been left too long, returning their ids.
    public synchronized List<Long> removeExpiredPiles(long worldId) {
        Instant now = Instant.now();
        List<Long> removed = new ArrayList<>();
        for (ResourceNode node : resourceNodeRepository.findAllByWorldId(worldId)) {
            if (node.getType() == ResourceNodeType.ITEM_PILE && node.isExpired(now)) {
                pileItemRepository.deleteAll(pileItemRepository.findAllByNodeId(node.getId()));
                resourceNodeRepository.delete(node);
                removed.add(node.getId());
            }
        }
        return removed;
    }

    /// Picks up as much of [pile] as fits in the bag. Empty afterwards, it's
    /// removed.
    private GatherResult pickUpPile(long worldId, long userId, ResourceNode pile) {
        if (!Long.valueOf(userId).equals(pile.getOwnerId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Those are someone else's things");
        }
        List<PileItem> items = pileItemRepository.findAllByNodeId(pile.getId());
        boolean tookSomething = false;
        for (PileItem stack : items) {
            int taken = Math.min(stack.getQuantity(), inventoryService.roomFor(worldId, userId, stack.getItem()));
            if (taken == 0) {
                continue;
            }
            inventoryService.add(worldId, userId, stack.getItem(), taken);
            tookSomething = true;
            if (taken == stack.getQuantity()) {
                pileItemRepository.delete(stack);
            } else {
                stack.setQuantity(stack.getQuantity() - taken);
                pileItemRepository.save(stack);
            }
        }
        if (!tookSomething && !items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Your bag is full");
        }
        if (pileItemRepository.findAllByNodeId(pile.getId()).isEmpty()) {
            resourceNodeRepository.delete(pile);
            return new GatherResult(pile, true);
        }
        return new GatherResult(pile, false);
    }

    public static long regrowsInMillis(ResourceNode node, Instant now) {
        return node.isAvailable(now) ? 0 : Duration.between(now, node.getDepletedUntil()).toMillis();
    }

    private int outerCount() {
        return OUTER_MIN_EACH + random.nextInt(OUTER_MAX_EACH - OUTER_MIN_EACH + 1);
    }

    private static int looseCap(WorldIsland island, ResourceNodeType type) {
        return switch (type) {
            case STICKS -> island.isHome() ? HOME_STICKS_CAP : OUTER_STICKS_CAP;
            case STONES -> island.isHome() ? HOME_STONES_CAP : OUTER_STONES_CAP;
            default -> 0;
        };
    }

    private static int looseOn(WorldIsland island, ResourceNodeType type, List<ResourceNode> nodes) {
        return (int) nodes.stream()
                .filter(n -> n.getType() == type && island.getId().equals(n.getIslandId()))
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
