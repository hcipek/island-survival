package com.example.testproject.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/// A geographic island belonging to a particular [World] - generated once
/// per world and persisted, so every player in that world sees the same
/// map. Not to be confused with [World] itself, which also tracks the
/// shared resource pool.
@Entity
public class WorldIsland {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long worldId;

    private double centerX;
    private double centerY;
    private double radius;
    private boolean home;

    protected WorldIsland() {
    }

    public WorldIsland(Long worldId, double centerX, double centerY, double radius, boolean home) {
        this.worldId = worldId;
        this.centerX = centerX;
        this.centerY = centerY;
        this.radius = radius;
        this.home = home;
    }

    public Long getId() {
        return id;
    }

    public Long getWorldId() {
        return worldId;
    }

    public double getCenterX() {
        return centerX;
    }

    public double getCenterY() {
        return centerY;
    }

    public double getRadius() {
        return radius;
    }

    public boolean isHome() {
        return home;
    }
}
