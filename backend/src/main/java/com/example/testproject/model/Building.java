package com.example.testproject.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Entity
public class Building {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long worldId;
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    private BuildingType type;

    private double x;
    private double y;

    /// Wood a lumberjack has produced and is holding until its owner comes
    /// to collect it, as of [producedUntil]. (Always 0 for other buildings.)
    @ColumnDefault("0")
    private double storedWood;
    private Instant producedUntil;

    protected Building() {
    }

    public Building(Long worldId, Long ownerId, BuildingType type, double x, double y) {
        this.worldId = worldId;
        this.ownerId = ownerId;
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

    public Long getOwnerId() {
        return ownerId;
    }

    public BuildingType getType() {
        return type;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getStoredWood() {
        return storedWood;
    }

    public void setStoredWood(double storedWood) {
        this.storedWood = storedWood;
    }

    public Instant getProducedUntil() {
        return producedUntil;
    }

    public void setProducedUntil(Instant producedUntil) {
        this.producedUntil = producedUntil;
    }
}
