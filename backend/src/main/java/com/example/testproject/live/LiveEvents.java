package com.example.testproject.live;

import com.example.testproject.model.ResourceNodeType;
import com.example.testproject.model.dto.BoatDto;
import com.example.testproject.model.dto.BuildingDto;
import com.example.testproject.model.dto.WorldStateDto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Builds the JSON messages the server pushes over a world socket. Each one
/// is a map with a `type` plus that event's fields, serialized as-is.
public final class LiveEvents {

    private LiveEvents() {
    }

    public static Map<String, Object> snapshot(
            WorldStateDto state,
            List<BuildingDto> buildings,
            List<Map<String, Object>> boats,
            List<Map<String, Object>> players,
            List<Map<String, Object>> nodes
    ) {
        return event(
                "snapshot", "state", state, "buildings", buildings, "boats", boats, "players", players,
                "nodes", nodes
        );
    }

    public static Map<String, Object> state(WorldStateDto state) {
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

    /// A tree, bush or pile of sticks. [regrowsInMs] is 0 when it can be
    /// gathered from right now.
    public static Map<String, Object> node(long id, ResourceNodeType type, double x, double y, long regrowsInMs) {
        return fields("id", id, "type", type, "x", x, "y", y, "regrowsInMs", regrowsInMs);
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
