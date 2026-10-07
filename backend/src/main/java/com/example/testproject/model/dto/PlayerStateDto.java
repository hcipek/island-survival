package com.example.testproject.model.dto;

/// How a player is doing in a world. Only ever sent to that player.
///
/// The per-second rates say how [hunger] and [health] are changing right now
/// (zero while the player isn't in the world), so the client can count them
/// between updates.
public record PlayerStateDto(
        double hunger,
        double hungerDrainPerSecond,
        double health,
        double healthChangePerSecond
) {
}
