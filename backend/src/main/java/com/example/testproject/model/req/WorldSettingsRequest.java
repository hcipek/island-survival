package com.example.testproject.model.req;

/// New settings for a world that hasn't started yet. Left-out values stay
/// as they are.
public record WorldSettingsRequest(Boolean animalAggression, String inventoryOnDeath) {
}
