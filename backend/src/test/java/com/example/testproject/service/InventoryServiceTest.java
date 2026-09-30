package com.example.testproject.service;

import com.example.testproject.model.InventorySlot;
import com.example.testproject.model.ItemType;
import com.example.testproject.model.req.CreateUserRequest;
import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.repository.InventorySlotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Transactional
class InventoryServiceTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventorySlotRepository inventorySlotRepository;

    @Autowired
    private WorldService worldService;

    @Autowired
    private UserService userService;

    private long worldId;
    private long aliceId;

    @BeforeEach
    void setUp() {
        worldId = worldService.createWorld(new CreateWorldRequest("Inventory")).id();
        aliceId = userService.createUser(new CreateUserRequest("Alice")).id();
    }

    @Test
    void resourcesStackUpTo50PerSlotStartingInTheQuickAccessBar() {
        inventoryService.add(worldId, aliceId, ItemType.STICK, 120);

        assertThat(slots()).extracting(InventorySlot::getSlotIndex, InventorySlot::getItem, InventorySlot::getQuantity)
                .containsExactly(
                        tuple(0, ItemType.STICK, 50),
                        tuple(1, ItemType.STICK, 50),
                        tuple(2, ItemType.STICK, 20));
    }

    @Test
    void addingTopsUpExistingStacksBeforeUsingNewSlots() {
        inventoryService.add(worldId, aliceId, ItemType.STICK, 30);
        inventoryService.add(worldId, aliceId, ItemType.STONE, 5);

        inventoryService.add(worldId, aliceId, ItemType.STICK, 30);

        assertThat(slots()).extracting(InventorySlot::getSlotIndex, InventorySlot::getItem, InventorySlot::getQuantity)
                .containsExactly(
                        tuple(0, ItemType.STICK, 50),
                        tuple(1, ItemType.STONE, 5),
                        tuple(2, ItemType.STICK, 10));
    }

    @Test
    void toolsTakeOneSlotEach() {
        inventoryService.add(worldId, aliceId, ItemType.AXE, 2);

        assertThat(slots()).extracting(InventorySlot::getQuantity).containsExactly(1, 1);
    }

    @Test
    void theBagHolds50SlotsInAll() {
        inventoryService.add(worldId, aliceId, ItemType.STONE, 50 * InventoryService.SLOT_COUNT);

        assertThat(slots()).hasSize(50);
        assertStatus(HttpStatus.CONFLICT, () -> inventoryService.add(worldId, aliceId, ItemType.STICK, 1));
    }

    @Test
    void anAddThatDoesNotFullyFitChangesNothing() {
        inventoryService.add(worldId, aliceId, ItemType.STONE, 50 * (InventoryService.SLOT_COUNT - 1));
        inventoryService.add(worldId, aliceId, ItemType.STICK, 40);

        assertStatus(HttpStatus.CONFLICT, () -> inventoryService.add(worldId, aliceId, ItemType.STICK, 20));

        assertThat(inventoryService.count(worldId, aliceId, ItemType.STICK)).isEqualTo(40);
        assertThat(inventoryService.roomFor(worldId, aliceId, ItemType.STICK)).isEqualTo(10);
    }

    @Test
    void removingTakesFromSeveralStacksAndEmptiesSlots() {
        inventoryService.add(worldId, aliceId, ItemType.WOOD, 70);

        inventoryService.remove(worldId, aliceId, ItemType.WOOD, 60, "to build");

        assertThat(inventoryService.count(worldId, aliceId, ItemType.WOOD)).isEqualTo(10);
        assertThat(slots()).hasSize(1);
    }

    @Test
    void removingMoreThanYouHaveChangesNothing() {
        inventoryService.add(worldId, aliceId, ItemType.WOOD, 5);

        assertThatThrownBy(() -> inventoryService.remove(worldId, aliceId, ItemType.WOOD, 20, "to build a lumberjack"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Not enough wood to build a lumberjack (need 20, have 5)");
        assertThat(inventoryService.count(worldId, aliceId, ItemType.WOOD)).isEqualTo(5);
    }

    @Test
    void movingToAnEmptySlot() {
        inventoryService.add(worldId, aliceId, ItemType.STICK, 5);

        inventoryService.move(worldId, aliceId, 0, 20);

        assertThat(slots()).extracting(InventorySlot::getSlotIndex).containsExactly(20);
    }

    @Test
    void movingOntoTheSameItemMergesAsMuchAsFits() {
        inventoryService.add(worldId, aliceId, ItemType.STICK, 50);
        inventoryService.add(worldId, aliceId, ItemType.STICK, 30); // slot 1
        inventoryService.move(worldId, aliceId, 0, 5);               // 50 now in slot 5
        inventoryService.remove(worldId, aliceId, ItemType.STICK, 20, "");  // slot 5 -> 30, slot 1 stays 30

        inventoryService.move(worldId, aliceId, 1, 5);

        assertThat(slots()).extracting(InventorySlot::getSlotIndex, InventorySlot::getQuantity)
                .containsExactly(tuple(1, 10), tuple(5, 50));
    }

    @Test
    void movingOntoSomethingElseSwapsThem() {
        inventoryService.add(worldId, aliceId, ItemType.STICK, 5);  // slot 0
        inventoryService.add(worldId, aliceId, ItemType.STONE, 3);  // slot 1

        inventoryService.move(worldId, aliceId, 0, 1);

        assertThat(slots()).extracting(InventorySlot::getSlotIndex, InventorySlot::getItem)
                .containsExactly(tuple(0, ItemType.STONE), tuple(1, ItemType.STICK));
    }

    @Test
    void whatYouHoldIsTheSelectedQuickAccessSlot() {
        inventoryService.add(worldId, aliceId, ItemType.STICK, 5);  // slot 0
        inventoryService.add(worldId, aliceId, ItemType.HAMMER, 1); // slot 1

        assertThat(inventoryService.held(worldId, aliceId)).isEqualTo(ItemType.STICK);
        inventoryService.select(worldId, aliceId, 1);
        assertThat(inventoryService.held(worldId, aliceId)).isEqualTo(ItemType.HAMMER);
        inventoryService.select(worldId, aliceId, 2);
        assertThat(inventoryService.held(worldId, aliceId)).isNull();
    }

    @Test
    void onlyQuickAccessSlotsCanBeHeld() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> inventoryService.select(worldId, aliceId, 10));
    }

    @Test
    void discardingEmptiesTheSlot() {
        inventoryService.add(worldId, aliceId, ItemType.STICK, 5);

        inventoryService.discard(worldId, aliceId, 0);

        assertThat(slots()).isEmpty();
    }

    private List<InventorySlot> slots() {
        return inventorySlotRepository.findAllByWorldIdAndUserIdOrderBySlotIndex(worldId, aliceId);
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }
}
