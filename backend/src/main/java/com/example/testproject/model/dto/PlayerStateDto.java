package com.example.testproject.model.dto;

/// How a player is doing in a world. Only ever sent to that player.
///
/// [hungerDrainPerSecond] is how fast [hunger] is dropping right now - zero
/// while they're not in the world - so the client can count it down between
/// updates.
public record PlayerStateDto(double hunger, double hungerDrainPerSecond) {
}
