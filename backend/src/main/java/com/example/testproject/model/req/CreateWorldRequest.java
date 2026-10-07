package com.example.testproject.model.req;

/// [animalAggression] and [inventoryOnDeath] are optional; left out, the
/// world gets the defaults (aggressive animals, drop the bag).
public record CreateWorldRequest(String name, Boolean animalAggression, String inventoryOnDeath) {

    public CreateWorldRequest(String name) {
        this(name, null, null);
    }
}
