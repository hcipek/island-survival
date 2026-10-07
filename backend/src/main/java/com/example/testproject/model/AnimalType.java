package com.example.testproject.model;

/// What kind of animal. Rabbits run away; boars fight back (in worlds with
/// animal aggression on).
public enum AnimalType {
    RABBIT(1, 200, 1),
    BOAR(3, 250, 3);

    /// How many hits it takes.
    private final int maxHealth;
    /// How much meat it gives.
    private final int meat;
    /// World units per second - players walk at 300, so you can always get
    /// away from a boar, and catch a rabbit if you corner it.
    private final double speed;

    AnimalType(int maxHealth, double speed, int meat) {
        this.maxHealth = maxHealth;
        this.speed = speed;
        this.meat = meat;
    }

    public int meat() {
        return meat;
    }

    public int maxHealth() {
        return maxHealth;
    }

    public double speed() {
        return speed;
    }
}
