package com.example.testproject.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/// A single independent save: its own island layout, buildings, and shared
/// wood pool. Players pick or create one of these on the welcome screen.
@Entity
public class World {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private Instant createdAt = Instant.now();

    private double wood;
    private Instant lastCollectedAt = Instant.now();

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

    public double getWood() {
        return wood;
    }

    public void setWood(double wood) {
        this.wood = wood;
    }

    public Instant getLastCollectedAt() {
        return lastCollectedAt;
    }

    public void setLastCollectedAt(Instant lastCollectedAt) {
        this.lastCollectedAt = lastCollectedAt;
    }
}
