package com.example.testproject.model.dto;

import com.example.testproject.model.World;

import java.time.Instant;
import java.util.List;

/// A world for the welcome screen and the snapshot. [started] says whether
/// anyone has played in it yet (until then its settings can be changed),
/// and [playing] who is in it right now.
public record WorldDto(
        long id,
        String name,
        Instant createdAt,
        WorldSettingsDto settings,
        boolean started,
        List<UserDto> playing
) {

    public static WorldDto from(World world, List<UserDto> playing) {
        return new WorldDto(
                world.getId(),
                world.getName(),
                world.getCreatedAt(),
                new WorldSettingsDto(world.isAnimalAggression(), world.getInventoryOnDeath()),
                world.getStartedAt() != null,
                playing
        );
    }
}
