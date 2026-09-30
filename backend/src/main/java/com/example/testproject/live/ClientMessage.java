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
/// - `select` - `slot`: holding what's in that quick-access slot
/// - `moveItem` - `fromSlot`, `toSlot`: rearranging the inventory
/// - `discard` - `slot`: throwing away what's in a slot
/// - `use` - no fields: using what you're holding (e.g. eating a berry)
/// - `collect` - `buildingId`: taking the wood your lumberjack has stored
/// - `craft` - `recipe`: making something, e.g. `AXE`
public record ClientMessage(
        String type,
        Point position,
        Point from,
        List<Point> path,
        Long boatId,
        Long nodeId,
        Integer slot,
        Integer fromSlot,
        Integer toSlot,
        Long buildingId,
        String recipe
) {
}
