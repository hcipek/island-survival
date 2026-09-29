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

    /// Width of the sand ring as a fraction of the island's diameter. Must
    /// match `IslandComponent._sandBandFraction` in the Flutter client, so
    /// the server accepts exactly the spots the client shows as buildable.
    public static final double SAND_BAND_FRACTION = 0.08;

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

    /// Radius of the inland grass, i.e. the island minus its sand ring.
    public double grassRadius() {
        return radius - radius * 2 * SAND_BAND_FRACTION;
    }

    /// Whether (x, y) falls on the sand ring - the only place a port can go.
    public boolean isOnSand(double x, double y) {
        double distance = distanceFromCenter(x, y);
        return distance >= grassRadius() && distance <= radius;
    }

    /// Whether (x, y) falls on the inland grass - the only place a
    /// lumberjack can go.
    public boolean isOnGrass(double x, double y) {
        return distanceFromCenter(x, y) <= grassRadius();
    }

    private double distanceFromCenter(double x, double y) {
        return Math.hypot(x - centerX, y - centerY);
    }
}
