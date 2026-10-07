package com.example.testproject.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/// A single independent save: its own island layout and buildings, and each
/// player's resources there (see [PlayerVitals]). Players pick or create
/// one of these on the welcome screen.
@Entity
public class World {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private Instant createdAt = Instant.now();

    /// The last time someone was playing in this world (or its sticks were
    /// last caught up) - so when someone comes back, the sticks that would
    /// have dropped in between can be added. Null if never.
    private Instant lastActiveAt;

    /// World settings, chosen when the world is created (see
    /// [com.example.testproject.model.dto.WorldSettingsDto]). The defaults
    /// apply to worlds made before settings existed.
    @ColumnDefault("true")
    private boolean animalAggression = true;

    // The setting's name as plain text - see D18 in docs/decisions.md.
    @ColumnDefault("'DROP'")
    @Column(length = 16)
    private String inventoryOnDeath = InventoryOnDeath.DROP.name();

    protected World() {
    }

    public World(String name, boolean animalAggression, InventoryOnDeath inventoryOnDeath) {
        this.name = name;
        this.animalAggression = animalAggression;
        this.inventoryOnDeath = inventoryOnDeath.name();
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

    public boolean isAnimalAggression() {
        return animalAggression;
    }

    public InventoryOnDeath getInventoryOnDeath() {
        return InventoryOnDeath.valueOf(inventoryOnDeath);
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }
}
