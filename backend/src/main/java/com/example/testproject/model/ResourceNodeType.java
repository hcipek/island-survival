package com.example.testproject.model;

/// What a [ResourceNode] is.
///
/// Trees and berry bushes stay where they are and regrow after being used.
/// Sticks and stones are "loose": picking them up removes them, and new
/// ones drop at random free spots over time, up to a limit per island.
///
/// An item pile is what a player's bag leaves behind when they die (in a
/// world set to drop it): only they can pick it up, until it disappears.
public enum ResourceNodeType {
    TREE,
    BERRY_BUSH,
    STICKS,
    STONES,
    ITEM_PILE;

    public boolean isLoose() {
        return this == STICKS || this == STONES;
    }
}
