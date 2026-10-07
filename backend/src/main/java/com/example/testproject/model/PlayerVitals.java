package com.example.testproject.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/// How a player is doing in one world: hunger and health. (What they carry
/// is in their inventory; see [InventorySlot].)
///
/// Nothing ticks per player: whenever it's read, what changed since
/// [hungerSettledAt] is worked out first.
@Entity
// The table keeps its old name, so existing players' hunger carries over.
@Table(name = "player_resources", uniqueConstraints = @UniqueConstraint(columnNames = {"worldId", "userId"}))
public class PlayerVitals {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long worldId;
    private Long userId;

    /// 100 is full, 0 is starving. The default lets the column be added to
    /// rows that already exist.
    @ColumnDefault("100")
    private double hunger = 100;

    /// When hunger was last brought up to date, while the player is in the
    /// world - or null while they're away, since hunger doesn't drop then.
    private Instant hungerSettledAt;

    /// 100 is full health, 0 is dead.
    @ColumnDefault("100")
    private double health = 100;

    protected PlayerVitals() {
    }

    public PlayerVitals(Long worldId, Long userId) {
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

    public double getHunger() {
        return hunger;
    }

    public void setHunger(double hunger) {
        this.hunger = hunger;
    }

    public double getHealth() {
        return health;
    }

    public void setHealth(double health) {
        this.health = health;
    }

    public Instant getHungerSettledAt() {
        return hungerSettledAt;
    }

    public void setHungerSettledAt(Instant hungerSettledAt) {
        this.hungerSettledAt = hungerSettledAt;
    }
}
