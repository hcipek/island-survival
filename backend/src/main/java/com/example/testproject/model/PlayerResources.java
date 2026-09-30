package com.example.testproject.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/// One player's stock in one world. Created the first time it's needed, with
/// the starting amount, so players who were in a world before resources
/// became per-player simply start fresh.
///
/// Production isn't ticked on a timer: whenever the stock is read or spent,
/// everything earned since [lastCollectedAt] is added first.
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"worldId", "userId"}))
public class PlayerResources {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long worldId;
    private Long userId;

    private double wood;
    private Instant lastCollectedAt = Instant.now();

    /// The defaults let these columns be added to rows that already exist.
    @ColumnDefault("0")
    private double food;

    /// 100 is full, 0 is starving.
    @ColumnDefault("100")
    private double hunger = 100;

    /// When hunger was last brought up to date, while the player is in the
    /// world - or null while they're away, since hunger doesn't drop then.
    private Instant hungerSettledAt;

    protected PlayerResources() {
    }

    public PlayerResources(Long worldId, Long userId, double wood) {
        this.worldId = worldId;
        this.userId = userId;
        this.wood = wood;
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

    public double getWood() {
        return wood;
    }

    public void setWood(double wood) {
        this.wood = wood;
    }

    public double getFood() {
        return food;
    }

    public void setFood(double food) {
        this.food = food;
    }

    public double getHunger() {
        return hunger;
    }

    public void setHunger(double hunger) {
        this.hunger = hunger;
    }

    public Instant getHungerSettledAt() {
        return hungerSettledAt;
    }

    public void setHungerSettledAt(Instant hungerSettledAt) {
        this.hungerSettledAt = hungerSettledAt;
    }

    public Instant getLastCollectedAt() {
        return lastCollectedAt;
    }

    public void setLastCollectedAt(Instant lastCollectedAt) {
        this.lastCollectedAt = lastCollectedAt;
    }
}
