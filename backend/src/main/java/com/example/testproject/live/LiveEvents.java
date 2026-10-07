package com.example.testproject.live;

import com.example.testproject.model.AnimalType;
import com.example.testproject.model.ResourceNodeType;
import com.example.testproject.model.dto.BoatDto;
import com.example.testproject.model.dto.BuildingDto;
import com.example.testproject.model.dto.PlayerStateDto;
import com.example.testproject.model.dto.WorldDto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Builds the JSON messages the server pushes over a world socket. Each one
/// is a map with a `type` plus that event's fields, serialized as-is.
public final class LiveEvents {

    private LiveEvents() {
    }

    public static Map<String, Object> snapshot(
            PlayerStateDto state,
            List<BuildingDto> buildings,
            List<Map<String, Object>> boats,
            List<Map<String, Object>> players,
            List<Map<String, Object>> nodes,
            Map<String, Object> inventory,
            List<Map<String, Object>> recipes,
            Map<String, Object> you,
            WorldDto world,
            List<Map<String, Object>> animals
    ) {
        return event(
                "snapshot", "state", state, "buildings", buildings, "boats", boats, "players", players,
                "nodes", nodes, "inventory", inventory, "recipes", recipes, "you", you, "world", world,
                "animals", animals
        );
    }

    /// Where the player receiving a snapshot was when they last left, or
    /// null for their first time in the world.
    public static Map<String, Object> position(double x, double y, Long boatId) {
        return fields("x", x, "y", y, "boatId", boatId);
    }

    public static Map<String, Object> state(PlayerStateDto state) {
        return event("state", "state", state);
    }

    public static Map<String, Object> buildingAdded(BuildingDto building) {
        return event("buildingAdded", "building", building);
    }

    public static Map<String, Object> buildingRemoved(long buildingId) {
        return event("buildingRemoved", "buildingId", buildingId);
    }

    public static Map<String, Object> boatAdded(BoatDto boat) {
        return event("boatAdded", "boat", boat(boat.id(), boat.ownerId(), boat.x(), boat.y(), null));
    }

    public static Map<String, Object> playerJoined(Map<String, Object> player) {
        return event("playerJoined", "player", player);
    }

    public static Map<String, Object> playerLeft(long userId) {
        return event("playerLeft", "userId", userId);
    }

    public static Map<String, Object> playerMoved(long userId, Movement movement) {
        return event("playerMoved", "userId", userId, "movement", movement(movement));
    }

    public static Map<String, Object> playerBoarded(long userId, long boatId) {
        return event("playerBoarded", "userId", userId, "boatId", boatId);
    }

    public static Map<String, Object> boatMoved(long boatId, Movement movement) {
        return event("boatMoved", "boatId", boatId, "movement", movement(movement));
    }

    public static Map<String, Object> playerLanded(long userId, Point position) {
        return event("playerLanded", "userId", userId, "position", position);
    }

    /// Someone gathered from a node; it can't be used again for
    /// [regrowsInMs].
    public static Map<String, Object> nodeDepleted(long nodeId, long regrowsInMs) {
        return event("nodeDepleted", "nodeId", nodeId, "regrowsInMs", regrowsInMs);
    }

    /// New sticks dropped somewhere.
    public static Map<String, Object> nodeAdded(Map<String, Object> node) {
        return event("nodeAdded", "node", node);
    }

    /// Sticks were picked up, so they're gone.
    public static Map<String, Object> nodeRemoved(long nodeId) {
        return event("nodeRemoved", "nodeId", nodeId);
    }

    /// The player's own inventory, after it changed (or in their snapshot).
    public static Map<String, Object> inventory(Map<String, Object> inventory) {
        return event("inventory", "inventory", inventory);
    }

    /// A building changed, e.g. a lumberjack's storage was collected.
    public static Map<String, Object> buildingUpdated(BuildingDto building) {
        return event("buildingUpdated", "building", building);
    }

    /// Someone died and came back at [position] (the home island).
    public static Map<String, Object> playerRespawned(long userId, Point position) {
        return event("playerRespawned", "userId", userId, "position", position);
    }

    /// Only to the player who died: why, and what happened to their bag -
    /// `KEPT`, `DROPPED` (into the pile [pileId]), `LOST`, or `EMPTY` if there
    /// was nothing in it.
    public static Map<String, Object> youDied(String cause, String bag, Long pileId) {
        return event("youDied", "cause", cause, "bag", bag, "pileId", pileId);
    }

    /// An animal as clients draw it: where it is, how hurt it is, how fast
    /// it moves, and the walk it's on (if any).
    public static Map<String, Object> animal(
            long id, AnimalType type, double x, double y, int health, int maxHealth, double speed, Movement movement
    ) {
        return fields(
                "id", id, "type", type, "x", x, "y", y, "health", health, "maxHealth", maxHealth,
                "speed", speed, "movement", movement(movement)
        );
    }

    public static Map<String, Object> animalAdded(Map<String, Object> animal) {
        return event("animalAdded", "animal", animal);
    }

    /// An animal set off somewhere new (or stopped: [movement] then ends
    /// where it is).
    public static Map<String, Object> animalMoved(long animalId, Movement movement, Point restingAt) {
        return event("animalMoved", "animalId", animalId, "movement", movement(movement), "restingAt", restingAt);
    }

    /// Only to the player who got hurt, e.g. by a boar.
    public static Map<String, Object> hurt(double amount, String by) {
        return event("hurt", "amount", amount, "by", by);
    }

    public static Map<String, Object> error(String message) {
        return event("error", "message", message);
    }

    /// A player as other clients need to draw them. [movement] is the walk
    /// in progress, if any; [boatId] is set while they're aboard a boat.
    public static Map<String, Object> player(
            long userId, String username, String color, double x, double y, Long boatId, Movement movement
    ) {
        return fields(
                "userId", userId, "username", username, "color", color,
                "x", x, "y", y, "boatId", boatId, "movement", movement(movement)
        );
    }

    /// A boat as clients need to draw it. [movement] is the voyage in
    /// progress, if any.
    public static Map<String, Object> boat(long id, long ownerId, double x, double y, Movement movement) {
        return fields("id", id, "ownerId", ownerId, "x", x, "y", y, "movement", movement(movement));
    }

    /// A tree, bush, sticks, stones or item pile. [regrowsInMs] is 0 when it
    /// can be gathered from right now. [ownerId] and [expiresInMs] are only
    /// set for item piles: whose it is, and when it disappears.
    public static Map<String, Object> node(
            long id, ResourceNodeType type, double x, double y, long regrowsInMs, Long ownerId, Long expiresInMs
    ) {
        return fields(
                "id", id, "type", type, "x", x, "y", y, "regrowsInMs", regrowsInMs,
                "ownerId", ownerId, "expiresInMs", expiresInMs
        );
    }

    private static Map<String, Object> movement(Movement movement) {
        if (movement == null) {
            return null;
        }
        return fields("from", movement.from(), "path", movement.path(), "elapsedMs", movement.elapsedMillis());
    }

    private static Map<String, Object> event(String type, Object... keysAndValues) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.putAll(fields(keysAndValues));
        return event;
    }

    /// Like `Map.of`, but keeps insertion order and allows null values.
    private static Map<String, Object> fields(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }
}
