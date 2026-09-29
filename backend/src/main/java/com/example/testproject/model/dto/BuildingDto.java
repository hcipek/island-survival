package com.example.testproject.model.dto;

import com.example.testproject.model.BuildingType;

public record BuildingDto(
        long id,
        BuildingType type,
        double x,
        double y,
        long ownerId,
        String ownerName,
        String ownerColor
) {
}
