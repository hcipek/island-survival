package com.example.testproject.live;

import com.example.testproject.model.Boat;
import com.example.testproject.model.PlayerPresence;
import com.example.testproject.model.User;
import com.example.testproject.repository.BoatRepository;
import com.example.testproject.repository.PlayerPresenceRepository;
import com.example.testproject.repository.UserRepository;
import com.example.testproject.service.PlayerResourcesService;
import com.example.testproject.service.ResourceNodeService;
import com.example.testproject.service.WorldService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// Everything that happens over a world socket: who's in the world, and
/// players walking, boarding, sailing and landing.
///
/// Movement is sent as intents ("walk this path") rather than a stream of
/// positions. The server stores where each movement ends, remembers the one
/// in progress so late joiners can catch up, and passes it on to everyone
/// else, who simulate it themselves.
@Service
public class LiveWorldService {

    /// Keeps one bad or malicious message from making everyone simulate a
    /// huge path.
    private static final int MAX_PATH_POINTS = 50;

    private final WorldSessionRegistry registry;
    private final WorldService worldService;
    private final ResourceNodeService resourceNodeService;
    private final PlayerResourcesService playerResourcesService;
    private final PlayerPresenceRepository playerPresenceRepository;
    private final BoatRepository boatRepository;
    private final UserRepository userRepository;

    /// Movement in progress, kept in memory only - after a restart everyone
    /// just starts at their stored destination.
    private final Map<String, Movement> playerMovements = new ConcurrentHashMap<>();
    private final Map<Long, Movement> boatMovements = new ConcurrentHashMap<>();

    public LiveWorldService(
            WorldSessionRegistry registry,
            WorldService worldService,
            ResourceNodeService resourceNodeService,
            PlayerResourcesService playerResourcesService,
            PlayerPresenceRepository playerPresenceRepository,
            BoatRepository boatRepository,
            UserRepository userRepository
    ) {
        this.registry = registry;
        this.worldService = worldService;
        this.resourceNodeService = resourceNodeService;
        this.playerResourcesService = playerResourcesService;
        this.playerPresenceRepository = playerPresenceRepository;
        this.boatRepository = boatRepository;
        this.userRepository = userRepository;
    }

    /// Registers a new socket and sends it everything it needs to draw the
    /// world as it is right now. Its player isn't visible to others until it
    /// sends `join`.
    public void connect(WebSocketSession session, long worldId, long userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown user"));
        worldService.getState(worldId, userId); // fails fast if the world doesn't exist

        synchronized (registry.lockFor(worldId)) {
            // Sticks only drop over time while someone's playing, so a world
            // nobody was in catches up on the ones it missed - based on how
            // long it's been empty, so leaving and coming straight back
            // doesn't refill anything.
            resourceNodeService.ensureNodes(worldId);
            if (registry.joinedUserIds(worldId).isEmpty()) {
                ResourceNodeService.StickChanges sticks =
                        resourceNodeService.catchUpSticks(worldId, worldService.lastActiveAt(worldId));
                worldService.markActive(worldId);
                sticks.removedIds().forEach(id -> registry.broadcast(worldId, LiveEvents.nodeRemoved(id)));
                sticks.added().forEach(node ->
                        registry.broadcast(worldId, LiveEvents.nodeAdded(ResourceNodeService.view(node))));
            }

            registry.register(worldId, userId, session);
            registry.send(session, snapshot(worldId, userId));
        }
    }

    public void disconnect(WebSocketSession session) {
        Long worldId = registry.worldIdOf(session);
        Long userId = registry.userIdOf(session);
        boolean wasJoined = registry.isJoined(session);
        registry.unregister(session);
        if (worldId == null || !wasJoined || registry.joinedUserIds(worldId).contains(userId)) {
            // Never joined, or still here in another tab.
            return;
        }

        playerMovements.remove(movementKey(worldId, userId));
        playerResourcesService.playerLeft(worldId, userId);
        if (registry.joinedUserIds(worldId).isEmpty()) {
            worldService.markActive(worldId); // the last player just left
        }
        playerPresenceRepository.findByWorldIdAndUserId(worldId, userId).ifPresent(presence -> {
            presence.setBoatId(null);
            playerPresenceRepository.save(presence);
        });
        registry.broadcast(worldId, LiveEvents.playerLeft(userId));
    }

    public void join(WebSocketSession session, Point position) {
        long worldId = registry.worldIdOf(session);
        long userId = registry.userIdOf(session);
        requireNotNull(position, "position");

        PlayerPresence presence = presenceOf(worldId, userId);
        presence.setX(position.x());
        presence.setY(position.y());
        presence.setBoatId(null);
        playerPresenceRepository.save(presence);
        playerMovements.remove(movementKey(worldId, userId));

        boolean alreadyHereInAnotherTab = registry.joinedUserIds(worldId).contains(userId);
        registry.markJoined(session);
        registry.broadcastExcept(worldId, session, LiveEvents.playerJoined(playerView(presence)));

        if (alreadyHereInAnotherTab) {
            playerResourcesService.sendStateTo(playerResourcesService.collected(worldId, userId));
        } else {
            // Hunger only drops while you're in the world.
            playerResourcesService.sendStateTo(playerResourcesService.playerEntered(worldId, userId));
        }
    }

    public void eat(WebSocketSession session) {
        long worldId = registry.worldIdOf(session);
        long userId = registry.userIdOf(session);
        requireJoined(session);
        playerResourcesService.sendStateTo(playerResourcesService.eat(worldId, userId));
    }

    public void move(WebSocketSession session, Point from, List<Point> path) {
        long worldId = registry.worldIdOf(session);
        long userId = registry.userIdOf(session);
        requireJoined(session);
        Movement movement = movementOf(from, path);

        PlayerPresence presence = presenceOf(worldId, userId);
        if (presence.getBoatId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You're aboard a boat - sail it instead");
        }
        presence.setX(movement.destination().x());
        presence.setY(movement.destination().y());
        playerPresenceRepository.save(presence);
        playerMovements.put(movementKey(worldId, userId), movement);

        registry.broadcastExcept(worldId, session, LiveEvents.playerMoved(userId, movement));
    }

    public void board(WebSocketSession session, Long boatId) {
        long worldId = registry.worldIdOf(session);
        long userId = registry.userIdOf(session);
        requireJoined(session);
        Boat boat = ownBoat(worldId, userId, boatId);

        PlayerPresence presence = presenceOf(worldId, userId);
        presence.setBoatId(boat.getId());
        presence.setX(boat.getX());
        presence.setY(boat.getY());
        playerPresenceRepository.save(presence);
        playerMovements.remove(movementKey(worldId, userId));

        registry.broadcastExcept(worldId, session, LiveEvents.playerBoarded(userId, boat.getId()));
    }

    public void sail(WebSocketSession session, Long boatId, Point from, List<Point> path) {
        long worldId = registry.worldIdOf(session);
        long userId = registry.userIdOf(session);
        requireJoined(session);
        Boat boat = ownBoat(worldId, userId, boatId);
        requireAboard(worldId, userId, boat);
        Movement movement = movementOf(from, path);

        boat.setX(movement.destination().x());
        boat.setY(movement.destination().y());
        boatRepository.save(boat);
        boatMovements.put(boat.getId(), movement);

        registry.broadcastExcept(worldId, session, LiveEvents.boatMoved(boat.getId(), movement));
    }

    public void land(WebSocketSession session, Long boatId, Point position) {
        long worldId = registry.worldIdOf(session);
        long userId = registry.userIdOf(session);
        requireJoined(session);
        requireNotNull(position, "position");
        Boat boat = ownBoat(worldId, userId, boatId);
        requireAboard(worldId, userId, boat);

        PlayerPresence presence = presenceOf(worldId, userId);
        presence.setBoatId(null);
        presence.setX(position.x());
        presence.setY(position.y());
        playerPresenceRepository.save(presence);

        registry.broadcastExcept(worldId, session, LiveEvents.playerLanded(userId, position));
    }

    /// Everything [excludingUserId] needs to draw the world, with their own
    /// resources and everyone else in it.
    /// Gathering by hand from a node the player is standing next to. Everyone
    /// sees the node go bare; only the gatherer gets their new totals.
    public void gather(WebSocketSession session, Long nodeId) {
        long worldId = registry.worldIdOf(session);
        long userId = registry.userIdOf(session);
        requireJoined(session);
        PlayerPresence presence = presenceOf(worldId, userId);
        if (presence.getBoatId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Land on the island first");
        }

        ResourceNodeService.GatherResult result =
                resourceNodeService.gather(worldId, userId, nodeId, presence.getX(), presence.getY());

        if (result.removed()) {
            registry.broadcast(worldId, LiveEvents.nodeRemoved(result.node().getId()));
        } else {
            registry.broadcast(worldId, LiveEvents.nodeDepleted(
                    result.node().getId(), ResourceNodeService.regrowsInMillis(result.node(), Instant.now())));
        }
        playerResourcesService.sendStateTo(result.resources());
    }

    private Map<String, Object> snapshot(long worldId, long excludingUserId) {
        List<Map<String, Object>> boats = boatRepository.findAllByWorldId(worldId).stream()
                .map(b -> LiveEvents.boat(b.getId(), b.getOwnerId(), b.getX(), b.getY(), boatMovements.get(b.getId())))
                .toList();

        List<Map<String, Object>> players = registry.joinedUserIds(worldId).stream()
                .filter(userId -> userId != excludingUserId)
                .map(userId -> playerPresenceRepository.findByWorldIdAndUserId(worldId, userId))
                .flatMap(Optional::stream)
                .map(this::playerView)
                .toList();

        resourceNodeService.ensureNodes(worldId);
        return LiveEvents.snapshot(
                worldService.getState(worldId, excludingUserId), worldService.getBuildings(worldId), boats, players,
                resourceNodeService.nodesView(worldId)
        );
    }

    private Map<String, Object> playerView(PlayerPresence presence) {
        User user = userRepository.findById(presence.getUserId()).orElse(null);
        String name = user != null ? user.getName() : "Unknown";
        String color = user != null ? user.getColor() : "#9E9E9E";
        Movement movement = presence.getBoatId() == null
                ? playerMovements.get(movementKey(presence.getWorldId(), presence.getUserId()))
                : null;
        return LiveEvents.player(
                presence.getUserId(), name, color, presence.getX(), presence.getY(), presence.getBoatId(), movement
        );
    }

    private PlayerPresence presenceOf(long worldId, long userId) {
        return playerPresenceRepository.findByWorldIdAndUserId(worldId, userId)
                .orElseGet(() -> new PlayerPresence(worldId, userId));
    }

    private Boat ownBoat(long worldId, long userId, Long boatId) {
        requireNotNull(boatId, "boatId");
        Boat boat = boatRepository.findById(boatId)
                .filter(b -> b.getWorldId().equals(worldId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Boat not found"));
        if (boat.getOwnerId() != userId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That's another player's boat");
        }
        return boat;
    }

    private void requireAboard(long worldId, long userId, Boat boat) {
        if (!boat.getId().equals(presenceOf(worldId, userId).getBoatId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You're not aboard that boat");
        }
    }

    private void requireJoined(WebSocketSession session) {
        if (!registry.isJoined(session)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Send join first");
        }
    }

    private static Movement movementOf(Point from, List<Point> path) {
        requireNotNull(from, "from");
        if (path == null || path.isEmpty() || path.size() > MAX_PATH_POINTS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "path must have 1 to " + MAX_PATH_POINTS + " points");
        }
        return new Movement(from, List.copyOf(path), Instant.now());
    }

    private static void requireNotNull(Object value, String field) {
        if (value == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
    }

    private static String movementKey(long worldId, long userId) {
        return worldId + ":" + userId;
    }
}
