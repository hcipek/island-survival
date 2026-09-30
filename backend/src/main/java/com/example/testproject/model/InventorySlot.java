package com.example.testproject.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    // The enum's name as plain text. Mapping the enum directly would make
    // Hibernate restrict the column to the values that exist when the table
    // is created (as a database enum or a check constraint) and never
    // update that - so values added to the game later would be refused.
    @Column(length = 32)
    private String item;

    private int quantity;

    protected InventorySlot() {
    }

    public InventorySlot(Long worldId, Long userId, int slotIndex, ItemType item, int quantity) {
        this.worldId = worldId;
        this.userId = userId;
        this.slotIndex = slotIndex;
        this.item = item.name();
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
        return ItemType.valueOf(item);
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
