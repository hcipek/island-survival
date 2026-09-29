package com.example.testproject.model.dto;

import com.example.testproject.model.World;

import java.time.Instant;

public record WorldDto(long id, String name, Instant createdAt) {

    public static WorldDto from(World world) {
        return new WorldDto(world.getId(), world.getName(), world.getCreatedAt());
    }
}
