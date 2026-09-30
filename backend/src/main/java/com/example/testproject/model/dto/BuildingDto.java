package com.example.testproject.model.dto;

import com.example.testproject.model.BuildingType;

/// A building as clients draw it. For a lumberjack, [storedWood] is what
/// it's holding right now and [woodPerSecond] how fast that grows (up to
/// [woodCapacity]), so clients can count it up between updates.
public record BuildingDto(
        long id,
        BuildingType type,
        double x,
        double y,
        long ownerId,
        String ownerName,
        String ownerColor,
        double storedWood,
        double woodPerSecond,
        double woodCapacity
) {
}
