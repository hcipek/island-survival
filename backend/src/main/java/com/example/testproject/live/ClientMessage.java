package com.example.testproject.live;

import java.util.List;

/// Anything a client sends over the world socket. Which fields are set
/// depends on [type]:
///
/// - `join` - `position`: where the player appears
/// - `move` - `from`, `path`: walking on foot
/// - `board` - `boatId`: stepping onto one of your boats
/// - `sail` - `boatId`, `from`, `path`: sailing the boat you're aboard
/// - `land` - `boatId`, `position`: stepping off onto an island
/// - `gather` - `nodeId`: picking berries or sticks you're standing next to
public record ClientMessage(String type, Point position, Point from, List<Point> path, Long boatId, Long nodeId) {
}
