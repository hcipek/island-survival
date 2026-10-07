package com.example.testproject.service;

import com.example.testproject.live.LiveEvents;
import com.example.testproject.live.Point;
import com.example.testproject.live.WorldSessionRegistry;
import com.example.testproject.model.*;
import com.example.testproject.model.dto.BoatDto;
import com.example.testproject.model.dto.BuildingDto;
import com.example.testproject.model.dto.IslandLocationDto;
import com.example.testproject.model.dto.WorldDto;
import com.example.testproject.model.dto.PlayerStateDto;
import com.example.testproject.model.req.BuildingRequest;
import com.example.testproject.model.req.CreateBoatRequest;
import com.example.testproject.model.dto.UserDto;
import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.model.req.WorldSettingsRequest;
import com.example.testproject.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/// Owns everything scoped to a single [World]: its island layout (generated
/// once, at creation time, so every player in that world sees the same
/// map), its buildings and boats, and each player's resources there.
@Service
public class WorldService {

    private static final double HOME_RADIUS = 600;
    private static final int DISCOVERABLE_ISLAND_COUNT = 3;

    private static final double MIN_ISLAND_RADIUS = 250;
    private static final double MAX_ISLAND_RADIUS = 450;
    private static final double MIN_DISTANCE_FROM_HOME = 1300;
    private static final double MAX_DISTANCE_FROM_HOME = 3400;
    private static final double MIN_GAP_BETWEEN_ISLANDS = 300;
    private static final int MAX_PLACEMENT_ATTEMPTS = 30;

    static final int LUMBERJACK_COST = 20;

    /// A lumberjack slowly fills its own storage, which its owner comes to
    /// collect from - 0.1/s fills it in a little over 8 minutes.
    private static final double LUMBERJACK_WOOD_PER_SECOND = 0.1;
    private static final double LUMBERJACK_CAPACITY = 50;
    /// How close you have to be standing to collect.
    private static final double COLLECT_RANGE = 120;
    /// Per player, per world.
    private static final long MAX_LUMBERJACKS = 1;
    private static final int BOAT_COST = 10;

    /// How far off the coast (as a multiple of the island radius) a new boat
    /// is placed - matches where the client used to spawn boats locally.
    private static final double BOAT_SPAWN_DISTANCE = 1.05;

    private final WorldRepository worldRepository;
    private final WorldIslandRepository worldIslandRepository;
    private final BuildingRepository buildingRepository;
    private final UserRepository userRepository;
    private final PlayerPresenceRepository playerPresenceRepository;
    private final BoatRepository boatRepository;
    private final PlayerVitalsService playerVitalsService;
    private final InventoryService inventoryService;
    private final ResourceNodeService resourceNodeService;
    private final AnimalService animalService;
    private final WorldSessionRegistry sessionRegistry;
    private final Random random = new Random();

    public WorldService(
            WorldRepository worldRepository,
            WorldIslandRepository worldIslandRepository,
            BuildingRepository buildingRepository,
            UserRepository userRepository,
            PlayerPresenceRepository playerPresenceRepository,
            BoatRepository boatRepository,
            PlayerVitalsService playerVitalsService,
            InventoryService inventoryService,
            ResourceNodeService resourceNodeService,
            AnimalService animalService,
            WorldSessionRegistry sessionRegistry
    ) {
        this.worldRepository = worldRepository;
        this.worldIslandRepository = worldIslandRepository;
        this.buildingRepository = buildingRepository;
        this.userRepository = userRepository;
        this.playerPresenceRepository = playerPresenceRepository;
        this.boatRepository = boatRepository;
        this.playerVitalsService = playerVitalsService;
        this.inventoryService = inventoryService;
        this.resourceNodeService = resourceNodeService;
        this.animalService = animalService;
        this.sessionRegistry = sessionRegistry;
    }

    public List<WorldDto> listWorlds() {
        return worldRepository.findAll().stream().map(this::toWorldDto).toList();
    }

    private WorldDto toWorldDto(World world) {
        List<UserDto> playing = sessionRegistry.joinedUserIds(world.getId()).stream()
                .map(userRepository::findById)
                .flatMap(Optional::stream)
                .map(user -> UserDto.from(user, true))
                .sorted(Comparator.comparing(UserDto::name))
                .toList();
        return WorldDto.from(world, playing);
    }

    /// Changes the settings of a world nobody has played in yet. Once it's
    /// started they're fixed, so the rules can't change mid-game.
    ///
    /// Synchronized with [markStarted], so an edit and someone entering at
    /// the same moment can't both win.
    public synchronized WorldDto updateSettings(long worldId, WorldSettingsRequest request) {
        World world = getWorldOrThrow(worldId);
        if (world.getStartedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This world has already started - its settings can't change any more");
        }
        if (request.animalAggression() != null) {
            world.setAnimalAggression(request.animalAggression());
        }
        if (request.inventoryOnDeath() != null) {
            world.setInventoryOnDeath(parseInventoryOnDeath(request.inventoryOnDeath()));
        }
        return toWorldDto(worldRepository.save(world));
    }

    /// The first time someone plays in a world, it counts as started.
    public synchronized void markStarted(long worldId) {
        World world = getWorldOrThrow(worldId);
        if (world.getStartedAt() == null) {
            world.setStartedAt(Instant.now());
            worldRepository.save(world);
        }
    }

    public WorldDto createWorld(CreateWorldRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be empty");
        }

        boolean animalAggression = request.animalAggression() == null || request.animalAggression();
        InventoryOnDeath inventoryOnDeath = parseInventoryOnDeath(request.inventoryOnDeath());
        World world = worldRepository.save(new World(name, animalAggression, inventoryOnDeath));

        generateIslands(world.getId());
        resourceNodeService.ensureNodes(world.getId());

        return toWorldDto(world);
    }

    /// Deletes a world and everything in it - but not while anyone is in it,
    /// so nobody gets cut off mid-game.
    public void deleteWorld(long worldId) {
        getWorldOrThrow(worldId);
        if (sessionRegistry.hasConnections(worldId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Someone is playing in this world - it can be deleted once everyone has left");
        }
        buildingRepository.deleteAll(buildingRepository.findAllByWorldId(worldId));
        boatRepository.deleteAll(boatRepository.findAllByWorldId(worldId));
        worldIslandRepository.deleteAll(worldIslandRepository.findAllByWorldId(worldId));
        playerPresenceRepository.deleteAll(playerPresenceRepository.findAllByWorldId(worldId));
        playerVitalsService.deleteAllIn(worldId);
        inventoryService.deleteAllIn(worldId);
        resourceNodeService.deleteAllIn(worldId);
        animalService.forgetWorld(worldId);
        worldRepository.deleteById(worldId);
    }

    /// Where players appear when they first arrive or respawn: the middle of
    /// the home island.
    public Point homeSpawn(long worldId) {
        WorldIsland home = worldIslandRepository.findAllByWorldId(worldId).stream()
                .filter(WorldIsland::isHome)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "World has no home island"));
        return new Point(home.getCenterX(), home.getCenterY());
    }

    /// A spot on the sand of the island nearest ([x], [y]), on the side
    /// facing it - somewhere a player can walk to.
    public Point shoreNear(long worldId, double x, double y) {
        WorldIsland island = nearestIsland(worldId, x, y);
        double dx = x - island.getCenterX();
        double dy = y - island.getCenterY();
        double length = Math.hypot(dx, dy);
        if (length == 0) {
            return new Point(island.getCenterX(), island.getCenterY());
        }
        // Just inside the edge, on the sand ring.
        double distance = island.getRadius() * (1 - WorldIsland.SAND_BAND_FRACTION);
        return new Point(island.getCenterX() + dx / length * distance, island.getCenterY() + dy / length * distance);
    }

    public WorldDto getWorld(long worldId) {
        return toWorldDto(getWorldOrThrow(worldId));
    }

    private static InventoryOnDeath parseInventoryOnDeath(String value) {
        if (value == null) {
            return InventoryOnDeath.DROP;
        }
        try {
            return InventoryOnDeath.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "inventoryOnDeath must be KEEP, DROP or LOSE");
        }
    }

    public Instant lastActiveAt(long worldId) {
        return getWorldOrThrow(worldId).getLastActiveAt();
    }

    public void markActive(long worldId) {
        worldRepository.findById(worldId).ifPresent(world -> {
            world.setLastActiveAt(Instant.now());
            worldRepository.save(world);
        });
    }

    public synchronized List<IslandLocationDto> getIslands(long worldId) {
        getWorldOrThrow(worldId);
        List<WorldIsland> islands = worldIslandRepository.findAllByWorldId(worldId);
        if (islands.isEmpty()) {
            islands = generateIslands(worldId);
        }
        return islands.stream().map(IslandLocationDto::from).toList();
    }

    /// How [userId] is doing in [worldId].
    public PlayerStateDto getState(long worldId, long userId) {
        getWorldOrThrow(worldId);
        return playerVitalsService.stateOf(worldId, userId);
    }

    public List<BuildingDto> getBuildings(long worldId) {
        getWorldOrThrow(worldId);
        return buildingRepository.findAllByWorldId(worldId).stream()
                .map(this::settleProduction)
                .map(this::toBuildingDto)
                .toList();
    }

    public BuildingDto createBuilding(long worldId, BuildingRequest request) {
        getWorldOrThrow(worldId);
        User owner = userRepository.findById(request.ownerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user"));
        if (request.type() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Building type is required");
        }
        requireHoldingHammer(worldId, owner.getId());
        requireValidPlacement(worldId, request);
        playerVitalsService.requireNotStarving(worldId, owner.getId());

        if (request.type() == BuildingType.LUMBERJACK) {
            long existing = buildingRepository.countByWorldIdAndOwnerIdAndType(
                    worldId, owner.getId(), BuildingType.LUMBERJACK.name());
            if (existing >= MAX_LUMBERJACKS) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Only " + MAX_LUMBERJACKS + " lumberjack per player for now"
                );
            }
            inventoryService.remove(worldId, owner.getId(), ItemType.WOOD, LUMBERJACK_COST, "to build a lumberjack");
        }

        Building newBuilding = new Building(worldId, owner.getId(), request.type(), request.x(), request.y());
        newBuilding.setProducedUntil(Instant.now());
        Building building = buildingRepository.save(newBuilding);
        BuildingDto dto = toBuildingDto(building, owner);
        sessionRegistry.broadcast(worldId, LiveEvents.buildingAdded(dto));
        if (request.type() == BuildingType.LUMBERJACK) {
            inventoryService.sendTo(worldId, owner.getId());
        }
        return dto;
    }

    public void deleteBuilding(long worldId, long buildingId, long userId) {
        getWorldOrThrow(worldId);
        Building building = buildingRepository.findById(buildingId)
                .filter(b -> b.getWorldId().equals(worldId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Building not found"));
        if (building.getOwnerId() != userId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can remove this building");
        }

        // Whatever a lumberjack was holding goes with it - collect first.
        buildingRepository.delete(building);
        sessionRegistry.broadcast(worldId, LiveEvents.buildingRemoved(buildingId));
    }

    /// Builds a boat just off the coast next to one of the requester's own
    /// ports, paid for with wood from their inventory.
    public BoatDto createBoat(long worldId, CreateBoatRequest request) {
        getWorldOrThrow(worldId);
        Building port = buildingRepository.findById(request.portId())
                .filter(b -> b.getWorldId().equals(worldId) && b.getType() == BuildingType.PORT)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Port not found"));
        if (port.getOwnerId() != request.ownerId()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only build boats at your own port");
        }
        requireHoldingHammer(worldId, request.ownerId());

        playerVitalsService.requireNotStarving(worldId, request.ownerId());
        inventoryService.remove(worldId, request.ownerId(), ItemType.WOOD, BOAT_COST, "to build a boat");

        WorldIsland island = nearestIsland(worldId, port.getX(), port.getY());
        double dx = port.getX() - island.getCenterX();
        double dy = port.getY() - island.getCenterY();
        double length = Math.hypot(dx, dy);
        if (length == 0) {
            dx = 1;
            dy = 0;
            length = 1;
        }
        double spawnDistance = island.getRadius() * BOAT_SPAWN_DISTANCE;
        double x = island.getCenterX() + dx / length * spawnDistance;
        double y = island.getCenterY() + dy / length * spawnDistance;

        BoatDto boat = BoatDto.from(boatRepository.save(new Boat(worldId, port.getOwnerId(), x, y)));
        sessionRegistry.broadcast(worldId, LiveEvents.boatAdded(boat));
        inventoryService.sendTo(worldId, request.ownerId());
        return boat;
    }

    /// [userId], standing at ([x], [y]), takes the whole wood of their
    /// lumberjack into their inventory - or as much as fits. Returns the
    /// lumberjack as it is now.
    public synchronized BuildingDto collectFromLumberjack(long worldId, long userId, Long buildingId, double x, double y) {
        if (buildingId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "buildingId is required");
        }
        Building lumberjack = buildingRepository.findById(buildingId)
                .filter(b -> b.getWorldId().equals(worldId) && b.getType() == BuildingType.LUMBERJACK)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lumberjack not found"));
        if (lumberjack.getOwnerId() != userId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That's another player's lumberjack");
        }
        if (Math.hypot(lumberjack.getX() - x, lumberjack.getY() - y) > COLLECT_RANGE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Walk up to it first");
        }
        settleProduction(lumberjack);

        int ready = (int) Math.floor(lumberjack.getStoredWood());
        if (ready == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nothing to collect yet");
        }
        int taken = Math.min(ready, inventoryService.roomFor(worldId, userId, ItemType.WOOD));
        if (taken == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Your bag is full");
        }
        inventoryService.add(worldId, userId, ItemType.WOOD, taken);
        lumberjack.setStoredWood(lumberjack.getStoredWood() - taken);
        buildingRepository.save(lumberjack);

        BuildingDto dto = toBuildingDto(lumberjack);
        sessionRegistry.broadcast(worldId, LiveEvents.buildingUpdated(dto));
        inventoryService.sendTo(worldId, userId);
        return dto;
    }

    /// Adds what a lumberjack produced since last time, up to its capacity.
    private Building settleProduction(Building building) {
        if (building.getType() != BuildingType.LUMBERJACK) {
            return building;
        }
        Instant now = Instant.now();
        if (building.getProducedUntil() != null) {
            double seconds = Duration.between(building.getProducedUntil(), now).toMillis() / 1000.0;
            building.setStoredWood(Math.min(
                    LUMBERJACK_CAPACITY, building.getStoredWood() + seconds * LUMBERJACK_WOOD_PER_SECOND));
        }
        building.setProducedUntil(now);
        return buildingRepository.save(building);
    }

    private WorldIsland nearestIsland(long worldId, double x, double y) {
        return worldIslandRepository.findAllByWorldId(worldId).stream()
                .min((a, b) -> Double.compare(
                        distance(x, y, a.getCenterX(), a.getCenterY()) - a.getRadius(),
                        distance(x, y, b.getCenterX(), b.getCenterY()) - b.getRadius()
                ))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "World has no islands"));
    }

    /// Building anything takes a hammer in your hand - one in the bag
    /// doesn't count.
    private void requireHoldingHammer(long worldId, long userId) {
        if (inventoryService.held(worldId, userId) != ItemType.HAMMER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Hold a hammer to build");
        }
    }

    /// Mirrors the client's placement preview: ports only on an island's
    /// sand ring, lumberjacks only on its grass.
    private void requireValidPlacement(long worldId, BuildingRequest request) {
        List<WorldIsland> islands = worldIslandRepository.findAllByWorldId(worldId);
        boolean valid = switch (request.type()) {
            case PORT -> islands.stream().anyMatch(i -> i.isOnSand(request.x(), request.y()));
            case LUMBERJACK -> islands.stream().anyMatch(i -> i.isOnGrass(request.x(), request.y()));
        };
        if (!valid) {
            String where = request.type() == BuildingType.PORT ? "on the sand" : "on the grass";
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A " + request.type().name().toLowerCase() + " must be built " + where
            );
        }
    }

    private World getWorldOrThrow(long worldId) {
        return worldRepository.findById(worldId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "World not found"));
    }

    private BuildingDto toBuildingDto(Building building) {
        User owner = userRepository.findById(building.getOwnerId()).orElse(null);
        return toBuildingDto(building, owner);
    }

    private BuildingDto toBuildingDto(Building building, User owner) {
        String ownerName = owner != null ? owner.getName() : "Unknown";
        String ownerColor = owner != null ? owner.getColor() : "#9E9E9E";
        boolean lumberjack = building.getType() == BuildingType.LUMBERJACK;
        return new BuildingDto(
                building.getId(),
                building.getType(),
                building.getX(),
                building.getY(),
                building.getOwnerId(),
                ownerName,
                ownerColor,
                building.getStoredWood(),
                lumberjack ? LUMBERJACK_WOOD_PER_SECOND : 0,
                lumberjack ? LUMBERJACK_CAPACITY : 0
        );
    }

    private List<WorldIsland> generateIslands(long worldId) {
        List<WorldIsland> placed = new ArrayList<>();

        WorldIsland home = worldIslandRepository.save(new WorldIsland(worldId, 0, 0, HOME_RADIUS, true));
        placed.add(home);

        for (int i = 0; i < DISCOVERABLE_ISLAND_COUNT; i++) {
            double radius = MIN_ISLAND_RADIUS + random.nextDouble() * (MAX_ISLAND_RADIUS - MIN_ISLAND_RADIUS);

            double centerX = 0;
            double centerY = 0;
            for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
                double angle = random.nextDouble() * 2 * Math.PI;
                double distance = MIN_DISTANCE_FROM_HOME
                        + random.nextDouble() * (MAX_DISTANCE_FROM_HOME - MIN_DISTANCE_FROM_HOME);
                double candidateX = Math.cos(angle) * distance;
                double candidateY = Math.sin(angle) * distance;

                boolean farFromHome = distance(candidateX, candidateY, home.getCenterX(), home.getCenterY())
                        > home.getRadius() + radius + MIN_GAP_BETWEEN_ISLANDS;
                boolean farFromOthers = placed.stream()
                        .skip(1) // home already checked above
                        .allMatch(other -> distance(candidateX, candidateY, other.getCenterX(), other.getCenterY())
                                > other.getRadius() + radius + MIN_GAP_BETWEEN_ISLANDS);

                centerX = candidateX;
                centerY = candidateY;
                if (farFromHome && farFromOthers) {
                    break;
                }
            }

            placed.add(worldIslandRepository.save(new WorldIsland(worldId, centerX, centerY, radius, false)));
        }

        return placed;
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, y1 - y2);
    }
}
