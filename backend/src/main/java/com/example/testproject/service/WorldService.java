package com.example.testproject.service;

import com.example.testproject.live.LiveEvents;
import com.example.testproject.live.WorldSessionRegistry;
import com.example.testproject.model.*;
import com.example.testproject.model.dto.BoatDto;
import com.example.testproject.model.dto.BuildingDto;
import com.example.testproject.model.dto.IslandLocationDto;
import com.example.testproject.model.dto.PlayerPresenceDto;
import com.example.testproject.model.dto.WorldDto;
import com.example.testproject.model.dto.WorldStateDto;
import com.example.testproject.model.req.BuildingRequest;
import com.example.testproject.model.req.CreateBoatRequest;
import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.model.req.UpdatePositionRequest;
import com.example.testproject.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/// Owns everything scoped to a single [World]: its island layout (generated
/// once, at creation time, so every player in that world sees the same
/// map), its shared wood pool, and its buildings.
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

    private static final double WOOD_PER_LUMBERJACK_PER_SECOND = 0.5;
    private static final double LUMBERJACK_COST = 20;
    private static final long MAX_LUMBERJACKS = 1;
    private static final double BOAT_COST = 10;

    /// How far off the coast (as a multiple of the island radius) a new boat
    /// is placed - matches where the client used to spawn boats locally.
    private static final double BOAT_SPAWN_DISTANCE = 1.05;

    /// Enough to build a first lumberjack immediately - otherwise a fresh
    /// world could never produce any wood at all.
    private static final double STARTING_WOOD = LUMBERJACK_COST;

    /// How long a player's last reported position is still trusted before
    /// they're treated as having left (e.g. closed the tab) and dropped
    /// from everyone else's view.
    private static final Duration PRESENCE_TIMEOUT = Duration.ofSeconds(10);

    private final WorldRepository worldRepository;
    private final WorldIslandRepository worldIslandRepository;
    private final BuildingRepository buildingRepository;
    private final UserRepository userRepository;
    private final PlayerPresenceRepository playerPresenceRepository;
    private final BoatRepository boatRepository;
    private final WorldSessionRegistry sessionRegistry;
    private final Random random = new Random();

    public WorldService(
            WorldRepository worldRepository,
            WorldIslandRepository worldIslandRepository,
            BuildingRepository buildingRepository,
            UserRepository userRepository,
            PlayerPresenceRepository playerPresenceRepository,
            BoatRepository boatRepository,
            WorldSessionRegistry sessionRegistry
    ) {
        this.worldRepository = worldRepository;
        this.worldIslandRepository = worldIslandRepository;
        this.buildingRepository = buildingRepository;
        this.userRepository = userRepository;
        this.playerPresenceRepository = playerPresenceRepository;
        this.boatRepository = boatRepository;
        this.sessionRegistry = sessionRegistry;
    }

    public List<WorldDto> listWorlds() {
        return worldRepository.findAll().stream().map(WorldDto::from).toList();
    }

    public WorldDto createWorld(CreateWorldRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be empty");
        }

        World world = new World(name);
        world.setWood(STARTING_WOOD);
        world = worldRepository.save(world);

        generateIslands(world.getId());

        return WorldDto.from(world);
    }

    public void deleteWorld(long worldId) {
        buildingRepository.deleteAll(buildingRepository.findAllByWorldId(worldId));
        boatRepository.deleteAll(boatRepository.findAllByWorldId(worldId));
        worldIslandRepository.deleteAll(worldIslandRepository.findAllByWorldId(worldId));
        playerPresenceRepository.deleteAll(playerPresenceRepository.findAllByWorldId(worldId));
        worldRepository.deleteById(worldId);
    }

    public void updatePlayerPosition(long worldId, long userId, UpdatePositionRequest request) {
        getWorldOrThrow(worldId);
        userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user"));

        PlayerPresence presence = playerPresenceRepository.findByWorldIdAndUserId(worldId, userId)
                .orElseGet(() -> new PlayerPresence(worldId, userId));
        presence.setX(request.x());
        presence.setY(request.y());
        presence.setLastSeenAt(Instant.now());
        playerPresenceRepository.save(presence);
    }

    public List<PlayerPresenceDto> getPlayers(long worldId, long excludingUserId) {
        getWorldOrThrow(worldId);
        Instant cutoff = Instant.now().minus(PRESENCE_TIMEOUT);

        return playerPresenceRepository.findAllByWorldId(worldId).stream()
                .filter(p -> p.getUserId() != excludingUserId)
                .filter(p -> p.getLastSeenAt().isAfter(cutoff))
                .map(p -> {
                    User user = userRepository.findById(p.getUserId()).orElse(null);
                    String name = user != null ? user.getName() : "Unknown";
                    String color = user != null ? user.getColor() : "#9E9E9E";
                    return new PlayerPresenceDto(p.getUserId(), name, color, p.getX(), p.getY());
                })
                .toList();
    }

    public synchronized List<IslandLocationDto> getIslands(long worldId) {
        getWorldOrThrow(worldId);
        List<WorldIsland> islands = worldIslandRepository.findAllByWorldId(worldId);
        if (islands.isEmpty()) {
            islands = generateIslands(worldId);
        }
        return islands.stream().map(IslandLocationDto::from).toList();
    }

    public WorldStateDto getState(long worldId) {
        World world = getWorldOrThrow(worldId);
        collectPendingResources(world);
        worldRepository.save(world);
        return toStateDto(world);
    }

    public List<BuildingDto> getBuildings(long worldId) {
        getWorldOrThrow(worldId);
        return buildingRepository.findAllByWorldId(worldId).stream()
                .map(this::toBuildingDto)
                .toList();
    }

    public BuildingDto createBuilding(long worldId, BuildingRequest request) {
        World world = getWorldOrThrow(worldId);
        User owner = userRepository.findById(request.ownerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user"));
        if (request.type() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Building type is required");
        }
        requireValidPlacement(worldId, request);
        collectPendingResources(world);

        if (request.type() == BuildingType.LUMBERJACK) {
            long existing = buildingRepository.countByWorldIdAndType(worldId, BuildingType.LUMBERJACK);
            if (existing >= MAX_LUMBERJACKS) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Only " + MAX_LUMBERJACKS + " lumberjack allowed for now"
                );
            }
            if (world.getWood() < LUMBERJACK_COST) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Not enough wood to build a lumberjack (need " + (int) LUMBERJACK_COST + ")"
                );
            }
            world.setWood(world.getWood() - LUMBERJACK_COST);
        }

        worldRepository.save(world);
        Building building = buildingRepository.save(
                new Building(worldId, owner.getId(), request.type(), request.x(), request.y())
        );
        BuildingDto dto = toBuildingDto(building, owner);
        sessionRegistry.broadcast(worldId, LiveEvents.buildingAdded(dto));
        if (request.type() == BuildingType.LUMBERJACK) {
            sessionRegistry.broadcast(worldId, LiveEvents.state(toStateDto(world)));
        }
        return dto;
    }

    public void deleteBuilding(long worldId, long buildingId, long userId) {
        World world = getWorldOrThrow(worldId);
        Building building = buildingRepository.findById(buildingId)
                .filter(b -> b.getWorldId().equals(worldId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Building not found"));
        if (building.getOwnerId() != userId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can remove this building");
        }

        // Settle wood earned up to now before the building (and any
        // production it contributes) disappears.
        collectPendingResources(world);
        worldRepository.save(world);

        buildingRepository.delete(building);
        sessionRegistry.broadcast(worldId, LiveEvents.buildingRemoved(buildingId));
        if (building.getType() == BuildingType.LUMBERJACK) {
            sessionRegistry.broadcast(worldId, LiveEvents.state(toStateDto(world)));
        }
    }

    public List<BoatDto> getBoats(long worldId) {
        getWorldOrThrow(worldId);
        return boatRepository.findAllByWorldId(worldId).stream().map(BoatDto::from).toList();
    }

    /// Builds a boat just off the coast next to one of the requester's own
    /// ports, paid for from the world's wood.
    public BoatDto createBoat(long worldId, CreateBoatRequest request) {
        World world = getWorldOrThrow(worldId);
        Building port = buildingRepository.findById(request.portId())
                .filter(b -> b.getWorldId().equals(worldId) && b.getType() == BuildingType.PORT)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Port not found"));
        if (port.getOwnerId() != request.ownerId()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only build boats at your own port");
        }

        collectPendingResources(world);
        if (world.getWood() < BOAT_COST) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Not enough wood to build a boat (need " + (int) BOAT_COST + ")"
            );
        }
        world.setWood(world.getWood() - BOAT_COST);
        worldRepository.save(world);

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
        sessionRegistry.broadcast(worldId, LiveEvents.state(toStateDto(world)));
        return boat;
    }

    public void updateBoatPosition(long worldId, long boatId, long userId, UpdatePositionRequest request) {
        Boat boat = boatRepository.findById(boatId)
                .filter(b -> b.getWorldId().equals(worldId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Boat not found"));
        if (boat.getOwnerId() != userId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the owner can sail this boat");
        }
        boat.setX(request.x());
        boat.setY(request.y());
        boatRepository.save(boat);
    }

    private WorldIsland nearestIsland(long worldId, double x, double y) {
        return worldIslandRepository.findAllByWorldId(worldId).stream()
                .min((a, b) -> Double.compare(
                        distance(x, y, a.getCenterX(), a.getCenterY()) - a.getRadius(),
                        distance(x, y, b.getCenterX(), b.getCenterY()) - b.getRadius()
                ))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "World has no islands"));
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

    private void collectPendingResources(World world) {
        Instant now = Instant.now();
        double elapsedSeconds = Duration.between(world.getLastCollectedAt(), now).toMillis() / 1000.0;
        world.setWood(world.getWood() + elapsedSeconds * currentWoodPerSecond(world));
        world.setLastCollectedAt(now);
    }

    private double currentWoodPerSecond(World world) {
        long lumberjacks = buildingRepository.countByWorldIdAndType(world.getId(), BuildingType.LUMBERJACK);
        return lumberjacks * WOOD_PER_LUMBERJACK_PER_SECOND;
    }

    private WorldStateDto toStateDto(World world) {
        return new WorldStateDto(world.getWood(), currentWoodPerSecond(world));
    }

    private BuildingDto toBuildingDto(Building building) {
        User owner = userRepository.findById(building.getOwnerId()).orElse(null);
        return toBuildingDto(building, owner);
    }

    private BuildingDto toBuildingDto(Building building, User owner) {
        String ownerName = owner != null ? owner.getName() : "Unknown";
        String ownerColor = owner != null ? owner.getColor() : "#9E9E9E";
        return new BuildingDto(
                building.getId(),
                building.getType(),
                building.getX(),
                building.getY(),
                building.getOwnerId(),
                ownerName,
                ownerColor
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
