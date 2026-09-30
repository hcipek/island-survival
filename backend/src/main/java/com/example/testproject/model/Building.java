package com.example.testproject.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    // The enum's name as plain text. Mapping the enum directly would make
    // Hibernate restrict the column to the values that exist when the table
    // is created (as a database enum or a check constraint) and never
    // update that - so values added to the game later would be refused.
    @Column(length = 32)
    private String type;

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
        this.type = type.name();
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
        return BuildingType.valueOf(type);
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
