package com.example.testproject.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/// Where a player is in a given world, and which boat they're aboard.
/// Whether they're online is tracked by their open socket instead - see
/// `WorldSessionRegistry`.
@Entity
public class PlayerPresence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long worldId;
    private Long userId;
    private double x;
    private double y;

    /// The boat this player is aboard, or null when on foot. While set, the
    /// player's position is the boat's.
    private Long boatId;

    protected PlayerPresence() {
    }

    public PlayerPresence(Long worldId, Long userId) {
        this.worldId = worldId;
        this.userId = userId;
    }

    public Long getId() {
        return id;
    }

    public Long getWorldId() {
        return worldId;
    }

    public Long getUserId() {
        return userId;
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

    public Long getBoatId() {
        return boatId;
    }

    public void setBoatId(Long boatId) {
        this.boatId = boatId;
    }
}
