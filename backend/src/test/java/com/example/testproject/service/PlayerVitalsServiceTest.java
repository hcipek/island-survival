package com.example.testproject.service;

import com.example.testproject.model.BuildingType;
import com.example.testproject.model.ItemType;
import com.example.testproject.model.PlayerVitals;
import com.example.testproject.model.ResourceNode;
import com.example.testproject.model.ResourceNodeType;
import com.example.testproject.model.dto.BuildingDto;
import com.example.testproject.model.dto.PlayerStateDto;
import com.example.testproject.model.req.BuildingRequest;
import com.example.testproject.model.req.CreateBoatRequest;
import com.example.testproject.model.req.CreateUserRequest;
import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.repository.PlayerVitalsRepository;
import com.example.testproject.repository.ResourceNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/// Hunger, eating and starving.
@SpringBootTest
@Transactional
class PlayerVitalsServiceTest {

    @Autowired
    private PlayerVitalsService playerVitalsService;

    @Autowired
    private PlayerVitalsRepository playerVitalsRepository;

    @Autowired
    private WorldService worldService;

    @Autowired
    private UserService userService;

    @Autowired
    private ResourceNodeService resourceNodeService;

    @Autowired
    private ResourceNodeRepository resourceNodeRepository;

    @Autowired
    private InventoryService inventoryService;

    private long worldId;
    private long aliceId;

    @BeforeEach
    void setUp() {
        worldId = worldService.createWorld(new CreateWorldRequest("Hungry world")).id();
        aliceId = userService.createUser(new CreateUserRequest("Alice")).id();
    }

    @Test
    void youStartFullAndNotHungryWhileAway() {
        PlayerStateDto state = state();

        assertThat(state.hunger()).isEqualTo(100);
        assertThat(state.hungerDrainPerSecond()).isZero();
    }

    @Test
    void hungerDropsWhileYouAreInTheWorld() {
        playerVitalsService.playerEntered(worldId, aliceId);
        pretendHungerLastSettledSecondsAgo(90);

        // 15 minutes from full to empty: 90 seconds is a tenth of that.
        assertThat(state().hunger()).isCloseTo(90, within(0.1));
        assertThat(state().hungerDrainPerSecond()).isPositive();
    }

    @Test
    void hungerPausesWhileYouAreAway() {
        playerVitalsService.playerEntered(worldId, aliceId);
        pretendHungerLastSettledSecondsAgo(90);
        playerVitalsService.playerLeft(worldId, aliceId);
        double whenLeaving = state().hunger();

        assertThat(state().hunger()).isEqualTo(whenLeaving);
        assertThat(state().hungerDrainPerSecond()).isZero();
    }

    @Test
    void timeAwayDoesNotCountWhenYouComeBack() {
        playerVitalsService.playerEntered(worldId, aliceId);
        playerVitalsService.playerLeft(worldId, aliceId);

        playerVitalsService.playerEntered(worldId, aliceId);

        assertThat(state().hunger()).isCloseTo(100, within(0.1));
    }

    @Test
    void hungerNeverGoesBelowZero() {
        playerVitalsService.playerEntered(worldId, aliceId);
        pretendHungerLastSettledSecondsAgo(10_000);

        assertThat(state().hunger()).isZero();
        assertThat(state().hungerDrainPerSecond()).isZero();
    }

    @Test
    void aBerryFillsYouUp() {
        setHunger(50);

        playerVitalsService.eatBerry(worldId, aliceId);

        assertThat(state().hunger()).isEqualTo(65);
    }

    @Test
    void eatingNeverGoesAboveFull() {
        setHunger(95);

        playerVitalsService.eatBerry(worldId, aliceId);

        assertThat(state().hunger()).isEqualTo(100);
    }

    @Test
    void youCannotEatWhenFull() {
        setHunger(100);

        assertStatus(HttpStatus.CONFLICT, () -> playerVitalsService.requireHungry(worldId, aliceId));
    }

    @Test
    void starvingPlayersCannotBuild() {
        BuildingDto port = worldService.createBuilding(
                worldId, new BuildingRequest(BuildingType.PORT, 550, 0, aliceId));
        inventoryService.add(worldId, aliceId, ItemType.WOOD, 50);
        setHunger(0);

        assertStatus(HttpStatus.CONFLICT, () -> worldService.createBuilding(
                worldId, new BuildingRequest(BuildingType.LUMBERJACK, 100, 0, aliceId)));
        assertStatus(HttpStatus.CONFLICT,
                () -> worldService.createBoat(worldId, new CreateBoatRequest(port.id(), aliceId)));
    }

    @Test
    void starvingPlayersCanStillPickBerriesButNotSticks() {
        setHunger(0);
        ResourceNode sticks = first(ResourceNodeType.STICKS);
        ResourceNode bush = first(ResourceNodeType.BERRY_BUSH);

        assertStatus(HttpStatus.CONFLICT,
                () -> resourceNodeService.gather(worldId, aliceId, sticks.getId(), sticks.getX(), sticks.getY()));
        resourceNodeService.gather(worldId, aliceId, bush.getId(), bush.getX(), bush.getY());

        assertThat(inventoryService.count(worldId, aliceId, ItemType.BERRY)).isEqualTo(3);
        assertThat(inventoryService.count(worldId, aliceId, ItemType.STICK)).isZero();
    }

    private PlayerStateDto state() {
        return playerVitalsService.stateOf(worldId, aliceId);
    }

    private PlayerVitals vitals() {
        state(); // creates them if needed
        return playerVitalsRepository.findByWorldIdAndUserId(worldId, aliceId).orElseThrow();
    }

    private void setHunger(double hunger) {
        PlayerVitals vitals = vitals();
        vitals.setHunger(hunger);
        playerVitalsRepository.save(vitals);
    }

    private void pretendHungerLastSettledSecondsAgo(long seconds) {
        PlayerVitals vitals = vitals();
        vitals.setHungerSettledAt(Instant.now().minusSeconds(seconds));
        playerVitalsRepository.save(vitals);
    }

    private ResourceNode first(ResourceNodeType type) {
        return resourceNodeRepository.findAllByWorldId(worldId).stream()
                .filter(n -> n.getType() == type).findFirst().orElseThrow();
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }
}
