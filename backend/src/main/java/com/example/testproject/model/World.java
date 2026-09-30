package com.example.testproject.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/// A single independent save: its own island layout and buildings, and each
/// player's resources there (see [PlayerVitals]). Players pick or create
/// one of these on the welcome screen.
@Entity
public class World {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private Instant createdAt = Instant.now();

    /// The last time someone was playing in this world (or its sticks were
    /// last caught up) - so when someone comes back, the sticks that would
    /// have dropped in between can be added. Null if never.
    private Instant lastActiveAt;

    protected World() {
    }

    public World(String name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }
}
