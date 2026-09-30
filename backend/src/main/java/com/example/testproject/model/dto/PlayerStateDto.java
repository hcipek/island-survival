package com.example.testproject.model.dto;

/// One player's own resources in a world. Only ever sent to that player.
public record PlayerStateDto(double wood, double woodPerSecond, double food) {
}
