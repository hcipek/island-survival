package com.example.testproject.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/// A boat built at a port. Its position is reported by its owner's client
/// while sailing, so every player in the world sees it where it really is.
@Entity
public class Boat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long worldId;
    private Long ownerId;

    private double x;
    private double y;

    protected Boat() {
    }

    public Boat(Long worldId, Long ownerId, double x, double y) {
        this.worldId = worldId;
        this.ownerId = ownerId;
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

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }
}
