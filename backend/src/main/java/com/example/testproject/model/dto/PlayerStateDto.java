package com.example.testproject.model.dto;

/// One player's own resources in a world. Only ever sent to that player.
///
/// [hungerDrainPerSecond] is how fast [hunger] is dropping right now - zero
/// while they're not in the world - so the client can count it down between
/// updates, like wood counting up.
public record PlayerStateDto(
        double wood,
        double woodPerSecond,
        double food,
        double hunger,
        double hungerDrainPerSecond
) {
}
