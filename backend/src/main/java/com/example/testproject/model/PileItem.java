package com.example.testproject.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/// One stack of items in an item pile (a [ResourceNode] of type
/// `ITEM_PILE`).
@Entity
public class PileItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long nodeId;

    // The item's name as plain text - see D18 in docs/decisions.md.
    @Column(length = 32)
    private String item;

    private int quantity;

    protected PileItem() {
    }

    public PileItem(Long nodeId, ItemType item, int quantity) {
        this.nodeId = nodeId;
        this.item = item.name();
        this.quantity = quantity;
    }

    public Long getNodeId() {
        return nodeId;
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
