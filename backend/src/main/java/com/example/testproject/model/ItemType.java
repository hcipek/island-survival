package com.example.testproject.model;

/// Everything that can go in an inventory slot, and how many fit in one.
public enum ItemType {
    STICK(Kind.RESOURCE),
    STONE(Kind.RESOURCE),
    BERRY(Kind.RESOURCE),
    WOOD(Kind.RESOURCE),
    AXE(Kind.TOOL),
    HAMMER(Kind.TOOL);

    public enum Kind {
        /// Basic materials - stack up to 50 per slot.
        RESOURCE(50),
        /// One per slot. Only does anything while held.
        TOOL(1);

        private final int maxStack;

        Kind(int maxStack) {
            this.maxStack = maxStack;
        }
    }

    private final Kind kind;

    ItemType(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public int maxStack() {
        return kind.maxStack;
    }

    /// For messages, e.g. "Not enough wood".
    public String displayName() {
        return name().toLowerCase();
    }
}
