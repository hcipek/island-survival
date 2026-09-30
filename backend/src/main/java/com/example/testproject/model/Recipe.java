package com.example.testproject.model;

import java.util.List;

/// Something a player can make from what's in their inventory.
public enum Recipe {
    AXE(ItemType.AXE, List.of(new Ingredient(ItemType.STICK, 3), new Ingredient(ItemType.STONE, 2))),
    HAMMER(ItemType.HAMMER, List.of(new Ingredient(ItemType.STICK, 2), new Ingredient(ItemType.STONE, 3)));

    public record Ingredient(ItemType item, int quantity) {
    }

    private final ItemType output;
    private final List<Ingredient> ingredients;

    Recipe(ItemType output, List<Ingredient> ingredients) {
        this.output = output;
        this.ingredients = ingredients;
    }

    public ItemType output() {
        return output;
    }

    public List<Ingredient> ingredients() {
        return ingredients;
    }
}
