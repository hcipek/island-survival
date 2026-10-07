package com.example.testproject.model;

/// A world setting: what happens to a player's bag when they die. The
/// quick-access bar is always kept.
public enum InventoryOnDeath {
    /// Nothing is lost.
    KEEP,
    /// The bag's contents are left in a pile where they died, which they
    /// can go back for before it disappears.
    DROP,
    /// The bag's contents are gone.
    LOSE
}
