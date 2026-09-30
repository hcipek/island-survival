package com.example.testproject.service;

import com.example.testproject.model.Recipe;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/// Making things from what's in your inventory. Instant, and possible
/// anywhere for now.
@Service
public class CraftingService {

    private final InventoryService inventoryService;

    public CraftingService(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /// Uses up [recipeName]'s ingredients and adds what it makes. Checks
    /// everything first, so either all of it happens or none of it does.
    public synchronized Recipe craft(long worldId, long userId, String recipeName) {
        Recipe recipe = recipeNamed(recipeName);
        for (Recipe.Ingredient ingredient : recipe.ingredients()) {
            int have = inventoryService.count(worldId, userId, ingredient.item());
            if (have < ingredient.quantity()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Not enough " + ingredient.item().displayName() + " to make "
                                + recipe.output().displayName() + " (need " + ingredient.quantity()
                                + ", have " + have + ")");
            }
        }
        if (inventoryService.roomFor(worldId, userId, recipe.output()) < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Make room in your bag first");
        }

        for (Recipe.Ingredient ingredient : recipe.ingredients()) {
            inventoryService.remove(worldId, userId, ingredient.item(), ingredient.quantity(), "");
        }
        inventoryService.add(worldId, userId, recipe.output(), 1);
        return recipe;
    }

    /// Every recipe, as clients need to show them.
    public List<Map<String, Object>> recipesView() {
        return Arrays.stream(Recipe.values())
                .map(recipe -> Map.<String, Object>of(
                        "id", recipe.name(),
                        "output", recipe.output(),
                        "ingredients", recipe.ingredients().stream()
                                .map(i -> Map.of("item", i.item(), "quantity", i.quantity()))
                                .toList()))
                .toList();
    }

    private static Recipe recipeNamed(String name) {
        try {
            return Recipe.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such recipe: " + name);
        }
    }
}
