package com.example.testproject.model;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/// One filled slot in a player's inventory in a world. Empty slots simply
/// have no row. Slots 0-9 are the quick-access bar, 10-49 the bag.
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"worldId", "userId", "slotIndex"}))
public class InventorySlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long worldId;
    private Long userId;
    private int slotIndex;

    @Enumerated(EnumType.STRING)
    private ItemType item;

    private int quantity;

    protected InventorySlot() {
    }

    public InventorySlot(Long worldId, Long userId, int slotIndex, ItemType item, int quantity) {
        this.worldId = worldId;
        this.userId = userId;
        this.slotIndex = slotIndex;
        this.item = item;
        this.quantity = quantity;
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

    public int getSlotIndex() {
        return slotIndex;
    }

    public void setSlotIndex(int slotIndex) {
        this.slotIndex = slotIndex;
    }

    public ItemType getItem() {
        return item;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
