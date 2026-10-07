package com.example.testproject.model.dto;

import com.example.testproject.model.InventoryOnDeath;

/// Options chosen when a world is created. They can't be changed afterwards.
public record WorldSettingsDto(boolean animalAggression, InventoryOnDeath inventoryOnDeath) {
}
