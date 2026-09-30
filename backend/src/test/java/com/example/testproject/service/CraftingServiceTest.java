package com.example.testproject.service;

import com.example.testproject.model.ItemType;
import com.example.testproject.model.req.CreateUserRequest;
import com.example.testproject.model.req.CreateWorldRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class CraftingServiceTest {

    @Autowired
    private CraftingService craftingService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private WorldService worldService;

    @Autowired
    private UserService userService;

    private long worldId;
    private long aliceId;

    @BeforeEach
    void setUp() {
        worldId = worldService.createWorld(new CreateWorldRequest("Workshop")).id();
        aliceId = userService.createUser(new CreateUserRequest("Alice")).id();
    }

    @Test
    void anAxeIsMadeFromThreeSticksAndTwoStones() {
        give(ItemType.STICK, 5);
        give(ItemType.STONE, 2);

        craftingService.craft(worldId, aliceId, "AXE");

        assertThat(count(ItemType.AXE)).isEqualTo(1);
        assertThat(count(ItemType.STICK)).isEqualTo(2);
        assertThat(count(ItemType.STONE)).isZero();
    }

    @Test
    void aHammerIsMadeFromTwoSticksAndThreeStones() {
        give(ItemType.STICK, 2);
        give(ItemType.STONE, 3);

        craftingService.craft(worldId, aliceId, "HAMMER");

        assertThat(count(ItemType.HAMMER)).isEqualTo(1);
        assertThat(count(ItemType.STICK)).isZero();
        assertThat(count(ItemType.STONE)).isZero();
    }

    @Test
    void missingIngredientsMeanNothingIsUsedUp() {
        give(ItemType.STICK, 3);
        give(ItemType.STONE, 1);

        assertThatThrownBy(() -> craftingService.craft(worldId, aliceId, "AXE"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Not enough stone to make axe (need 2, have 1)");
        assertThat(count(ItemType.STICK)).isEqualTo(3);
        assertThat(count(ItemType.STONE)).isEqualTo(1);
    }

    @Test
    void aFullBagMeansNothingIsUsedUp() {
        give(ItemType.STICK, 50 * 25);
        give(ItemType.STONE, 50 * 25); // every slot is a full stack now

        assertThatThrownBy(() -> craftingService.craft(worldId, aliceId, "AXE"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getReason()).isEqualTo("Make room in your bag first"));
        assertThat(count(ItemType.STICK)).isEqualTo(50 * 25);
    }

    @Test
    void unknownRecipesAreRejected() {
        assertThatThrownBy(() -> craftingService.craft(worldId, aliceId, "CASTLE"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void recipesAreListedForTheClient() {
        assertThat(craftingService.recipesView())
                .extracting(r -> r.get("id"))
                .containsExactly("AXE", "HAMMER");
    }

    private void give(ItemType item, int quantity) {
        inventoryService.add(worldId, aliceId, item, quantity);
    }

    private int count(ItemType item) {
        return inventoryService.count(worldId, aliceId, item);
    }
}
