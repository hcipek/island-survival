package com.example.testproject.service;

import com.example.testproject.live.LiveEvents;
import com.example.testproject.live.WorldSessionRegistry;
import com.example.testproject.model.InventorySlot;
import com.example.testproject.model.ItemType;
import com.example.testproject.repository.InventorySlotRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Each player's inventory in each world: a quick-access bar of 10 slots
/// (whatever's selected there is what they're holding) and a bag of 40.
///
/// Everything here is all-or-nothing: if an add doesn't fully fit, or a
/// removal can't be fully paid, nothing changes.
@Service
public class InventoryService {

    public static final int HOTBAR_SIZE = 10;
    public static final int BAG_SIZE = 40;
    public static final int SLOT_COUNT = HOTBAR_SIZE + BAG_SIZE;

    private final InventorySlotRepository inventorySlotRepository;
    private final WorldSessionRegistry sessionRegistry;

    /// Which quick-access slot each player (by world and user) is holding.
    /// Kept in memory: the client sends it again when it (re)joins.
    private final Map<String, Integer> selectedSlots = new ConcurrentHashMap<>();

    public InventoryService(InventorySlotRepository inventorySlotRepository, WorldSessionRegistry sessionRegistry) {
        this.inventorySlotRepository = inventorySlotRepository;
        this.sessionRegistry = sessionRegistry;
    }

    public synchronized int count(long worldId, long userId, ItemType item) {
        return slotsOf(worldId, userId).stream()
                .filter(slot -> slot.getItem() == item)
                .mapToInt(InventorySlot::getQuantity)
                .sum();
    }

    /// How much more of [item] fits, in stacks of it that aren't full plus
    /// empty slots.
    public synchronized int roomFor(long worldId, long userId, ItemType item) {
        Map<Integer, InventorySlot> byIndex = new HashMap<>();
        slotsOf(worldId, userId).forEach(slot -> byIndex.put(slot.getSlotIndex(), slot));
        return spaceFor(item, byIndex);
    }

    /// Adds [quantity] of [item]: first topping up stacks of it that aren't
    /// full, then filling empty slots, quick-access bar first.
    public synchronized void add(long worldId, long userId, ItemType item, int quantity) {
        List<InventorySlot> slots = slotsOf(worldId, userId);
        Map<Integer, InventorySlot> byIndex = new HashMap<>();
        slots.forEach(slot -> byIndex.put(slot.getSlotIndex(), slot));

        if (spaceFor(item, byIndex) < quantity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Your bag is full");
        }

        int remaining = quantity;
        for (InventorySlot slot : slots) {
            if (remaining == 0) {
                break;
            }
            if (slot.getItem() == item && slot.getQuantity() < item.maxStack()) {
                int moved = Math.min(remaining, item.maxStack() - slot.getQuantity());
                slot.setQuantity(slot.getQuantity() + moved);
                inventorySlotRepository.save(slot);
                remaining -= moved;
            }
        }
        for (int index = 0; index < SLOT_COUNT && remaining > 0; index++) {
            if (!byIndex.containsKey(index)) {
                int moved = Math.min(remaining, item.maxStack());
                inventorySlotRepository.save(new InventorySlot(worldId, userId, index, item, moved));
                remaining -= moved;
            }
        }
    }

    /// Takes [quantity] of [item] from wherever it is - [forWhat] is used in
    /// the message if there isn't enough, e.g. "to build a boat".
    public synchronized void remove(long worldId, long userId, ItemType item, int quantity, String forWhat) {
        int have = count(worldId, userId, item);
        if (have < quantity) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Not enough " + item.displayName() + " " + forWhat + " (need " + quantity + ", have " + have + ")"
            );
        }
        int remaining = quantity;
        // Take from the fullest-numbered slots first, so the quick-access
        // bar is left alone where possible.
        List<InventorySlot> slots = slotsOf(worldId, userId).reversed();
        for (InventorySlot slot : slots) {
            if (remaining == 0) {
                break;
            }
            if (slot.getItem() == item) {
                int taken = Math.min(remaining, slot.getQuantity());
                reduce(slot, taken);
                remaining -= taken;
            }
        }
    }

    /// Takes one from the slot the player is holding, which must contain
    /// [item].
    public synchronized void removeOneHeld(long worldId, long userId, ItemType item) {
        InventorySlot slot = slotAt(worldId, userId, selectedSlot(worldId, userId));
        if (slot == null || slot.getItem() != item) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You're not holding any " + item.displayName());
        }
        reduce(slot, 1);
    }

    /// Moves what's in [from] to [to]: into it if empty, onto it if it's the
    /// same item (as much as fits), or swapping the two otherwise.
    public synchronized void move(long worldId, long userId, int from, int to) {
        requireSlotIndex(from);
        requireSlotIndex(to);
        if (from == to) {
            return;
        }
        InventorySlot source = slotAt(worldId, userId, from);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That slot is empty");
        }
        InventorySlot target = slotAt(worldId, userId, to);

        if (target == null) {
            source.setSlotIndex(to);
            inventorySlotRepository.save(source);
        } else if (target.getItem() == source.getItem() && target.getQuantity() < target.getItem().maxStack()) {
            int moved = Math.min(source.getQuantity(), target.getItem().maxStack() - target.getQuantity());
            target.setQuantity(target.getQuantity() + moved);
            inventorySlotRepository.save(target);
            reduce(source, moved);
        } else {
            // Swap. Park one out of the way first so the unique slot index
            // constraint never sees two rows in the same slot.
            source.setSlotIndex(-1);
            inventorySlotRepository.saveAndFlush(source);
            target.setSlotIndex(from);
            inventorySlotRepository.saveAndFlush(target);
            source.setSlotIndex(to);
            inventorySlotRepository.save(source);
        }
    }

    /// Throws away everything in [slot].
    public synchronized void discard(long worldId, long userId, int slot) {
        requireSlotIndex(slot);
        InventorySlot existing = slotAt(worldId, userId, slot);
        if (existing != null) {
            inventorySlotRepository.delete(existing);
        }
    }

    public void select(long worldId, long userId, int slot) {
        if (slot < 0 || slot >= HOTBAR_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only quick-access slots can be held");
        }
        selectedSlots.put(key(worldId, userId), slot);
    }

    public int selectedSlot(long worldId, long userId) {
        return selectedSlots.getOrDefault(key(worldId, userId), 0);
    }

    /// What the player has in their hand, or null if nothing.
    public synchronized ItemType held(long worldId, long userId) {
        InventorySlot slot = slotAt(worldId, userId, selectedSlot(worldId, userId));
        return slot == null ? null : slot.getItem();
    }

    /// Everything the player's client needs to draw their inventory.
    public synchronized Map<String, Object> view(long worldId, long userId) {
        List<Map<String, Object>> slots = slotsOf(worldId, userId).stream()
                .map(slot -> Map.<String, Object>of(
                        "slot", slot.getSlotIndex(), "item", slot.getItem(), "quantity", slot.getQuantity()))
                .toList();
        return Map.of(
                "slots", slots,
                "selectedSlot", selectedSlot(worldId, userId),
                "hotbarSize", HOTBAR_SIZE,
                "bagSize", BAG_SIZE
        );
    }

    /// Inventories are private, so this goes only to that player's own
    /// connections.
    public void sendTo(long worldId, long userId) {
        sessionRegistry.sendToUser(worldId, userId, LiveEvents.inventory(view(worldId, userId)));
    }

    public void deleteAllIn(long worldId) {
        inventorySlotRepository.deleteAll(inventorySlotRepository.findAllByWorldId(worldId));
    }

    private int spaceFor(ItemType item, Map<Integer, InventorySlot> byIndex) {
        int space = 0;
        for (int index = 0; index < SLOT_COUNT; index++) {
            InventorySlot slot = byIndex.get(index);
            if (slot == null) {
                space += item.maxStack();
            } else if (slot.getItem() == item) {
                space += item.maxStack() - slot.getQuantity();
            }
        }
        return space;
    }

    private void reduce(InventorySlot slot, int amount) {
        if (slot.getQuantity() <= amount) {
            inventorySlotRepository.delete(slot);
        } else {
            slot.setQuantity(slot.getQuantity() - amount);
            inventorySlotRepository.save(slot);
        }
    }

    private List<InventorySlot> slotsOf(long worldId, long userId) {
        return inventorySlotRepository.findAllByWorldIdAndUserIdOrderBySlotIndex(worldId, userId);
    }

    private InventorySlot slotAt(long worldId, long userId, int index) {
        return slotsOf(worldId, userId).stream()
                .filter(slot -> slot.getSlotIndex() == index)
                .findFirst()
                .orElse(null);
    }

    private static void requireSlotIndex(int index) {
        if (index < 0 || index >= SLOT_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such slot: " + index);
        }
    }

    private static String key(long worldId, long userId) {
        return worldId + ":" + userId;
    }
}
