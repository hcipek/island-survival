package com.example.testproject.model.dto;

import com.example.testproject.model.WorldIsland;

public record IslandLocationDto(long id, double centerX, double centerY, double radius, boolean isHome) {

    public static IslandLocationDto from(WorldIsland island) {
        return new IslandLocationDto(
                island.getId(),
                island.getCenterX(),
                island.getCenterY(),
                island.getRadius(),
                island.isHome()
        );
    }
}
