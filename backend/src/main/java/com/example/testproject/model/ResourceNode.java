package com.example.testproject.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;

/// Something natural on an island - a tree, a berry bush, a pile of sticks.
/// Shared by everyone in the world: once someone gathers from it, it's gone
/// for everyone until it regrows.
@Entity
public class ResourceNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long worldId;
    private Long islandId;

    @Enumerated(EnumType.STRING)
    private ResourceNodeType type;

    private double x;
    private double y;

    /// When it can be gathered from again, or null if it can be right now.
    /// Nothing ticks on the server when this passes - it's simply compared
    /// against the current time whenever someone looks.
    private Instant depletedUntil;

    protected ResourceNode() {
    }

    public ResourceNode(Long worldId, Long islandId, ResourceNodeType type, double x, double y) {
        this.worldId = worldId;
        this.islandId = islandId;
        this.type = type;
        this.x = x;
        this.y = y;
    }

    public Long getId() {
        return id;
    }

    public Long getWorldId() {
        return worldId;
    }

    public Long getIslandId() {
        return islandId;
    }

    public ResourceNodeType getType() {
        return type;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public Instant getDepletedUntil() {
        return depletedUntil;
    }

    public void setDepletedUntil(Instant depletedUntil) {
        this.depletedUntil = depletedUntil;
    }

    public boolean isAvailable(Instant now) {
        return depletedUntil == null || !depletedUntil.isAfter(now);
    }
}
