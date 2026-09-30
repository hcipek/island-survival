package com.example.testproject.service;

import com.example.testproject.model.BuildingType;
import com.example.testproject.model.PlayerResources;
import com.example.testproject.model.ResourceNode;
import com.example.testproject.model.ResourceNodeType;
import com.example.testproject.model.dto.BuildingDto;
import com.example.testproject.model.dto.PlayerStateDto;
import com.example.testproject.model.req.BuildingRequest;
import com.example.testproject.model.req.CreateBoatRequest;
import com.example.testproject.model.req.CreateUserRequest;
import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.repository.PlayerResourcesRepository;
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

/// Hunger and eating. (Wood is covered in WorldServiceTest.)
@SpringBootTest
@Transactional
class PlayerResourcesServiceTest {

    @Autowired
    private PlayerResourcesService playerResourcesService;

    @Autowired
    private PlayerResourcesRepository playerResourcesRepository;

    @Autowired
    private WorldService worldService;

    @Autowired
    private UserService userService;

    @Autowired
    private ResourceNodeService resourceNodeService;

    @Autowired
    private ResourceNodeRepository resourceNodeRepository;

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
        assertThat(state.food()).isZero();
        assertThat(state.hungerDrainPerSecond()).isZero();
    }

    @Test
    void hungerDropsWhileYouAreInTheWorld() {
        playerResourcesService.playerEntered(worldId, aliceId);
        pretendHungerLastSettledSecondsAgo(90);

        // 15 minutes from full to empty: 90 seconds is a tenth of that.
        assertThat(state().hunger()).isCloseTo(90, within(0.1));
        assertThat(state().hungerDrainPerSecond()).isPositive();
    }

    @Test
    void hungerPausesWhileYouAreAway() {
        playerResourcesService.playerEntered(worldId, aliceId);
        pretendHungerLastSettledSecondsAgo(90);
        playerResourcesService.playerLeft(worldId, aliceId);
        double whenLeaving = state().hunger();

        PlayerResources resources = resources();
        resources.setLastCollectedAt(Instant.now().minusSeconds(3600));
        playerResourcesRepository.save(resources);

        assertThat(state().hunger()).isEqualTo(whenLeaving);
        assertThat(state().hungerDrainPerSecond()).isZero();
    }

    @Test
    void timeAwayDoesNotCountWhenYouComeBack() {
        playerResourcesService.playerEntered(worldId, aliceId);
        playerResourcesService.playerLeft(worldId, aliceId);

        playerResourcesService.playerEntered(worldId, aliceId);

        assertThat(state().hunger()).isCloseTo(100, within(0.1));
    }

    @Test
    void hungerNeverGoesBelowZero() {
        playerResourcesService.playerEntered(worldId, aliceId);
        pretendHungerLastSettledSecondsAgo(10_000);

        assertThat(state().hunger()).isZero();
        assertThat(state().hungerDrainPerSecond()).isZero();
    }

    @Test
    void eatingUsesOneFoodAndFillsYouUp() {
        setFoodAndHunger(3, 50);

        playerResourcesService.eat(worldId, aliceId);

        assertThat(state().food()).isEqualTo(2);
        assertThat(state().hunger()).isEqualTo(65);
    }

    @Test
    void eatingNeverGoesAboveFull() {
        setFoodAndHunger(1, 95);

        playerResourcesService.eat(worldId, aliceId);

        assertThat(state().hunger()).isEqualTo(100);
    }

    @Test
    void youCannotEatWithoutFoodOrWhenFull() {
        setFoodAndHunger(0, 50);
        assertStatus(HttpStatus.CONFLICT, () -> playerResourcesService.eat(worldId, aliceId));

        setFoodAndHunger(5, 100);
        assertStatus(HttpStatus.CONFLICT, () -> playerResourcesService.eat(worldId, aliceId));
    }

    @Test
    void starvingPlayersCannotBuild() {
        BuildingDto port = worldService.createBuilding(
                worldId, new BuildingRequest(BuildingType.PORT, 550, 0, aliceId));
        setFoodAndHunger(0, 0);

        assertStatus(HttpStatus.CONFLICT, () -> worldService.createBuilding(
                worldId, new BuildingRequest(BuildingType.LUMBERJACK, 100, 0, aliceId)));
        assertStatus(HttpStatus.CONFLICT,
                () -> worldService.createBoat(worldId, new CreateBoatRequest(port.id(), aliceId)));
    }

    @Test
    void starvingPlayersCanStillPickBerriesButNotSticks() {
        setFoodAndHunger(0, 0);
        ResourceNode sticks = first(ResourceNodeType.STICKS);
        ResourceNode bush = first(ResourceNodeType.BERRY_BUSH);

        assertStatus(HttpStatus.CONFLICT,
                () -> resourceNodeService.gather(worldId, aliceId, sticks.getId(), sticks.getX(), sticks.getY()));
        resourceNodeService.gather(worldId, aliceId, bush.getId(), bush.getX(), bush.getY());

        assertThat(state().food()).isEqualTo(3);
    }

    private PlayerStateDto state() {
        return playerResourcesService.stateOf(worldId, aliceId);
    }

    private PlayerResources resources() {
        state(); // creates them if needed
        return playerResourcesRepository.findByWorldIdAndUserId(worldId, aliceId).orElseThrow();
    }

    private void setFoodAndHunger(double food, double hunger) {
        PlayerResources resources = resources();
        resources.setFood(food);
        resources.setHunger(hunger);
        playerResourcesRepository.save(resources);
    }

    private void pretendHungerLastSettledSecondsAgo(long seconds) {
        PlayerResources resources = resources();
        resources.setHungerSettledAt(Instant.now().minusSeconds(seconds));
        playerResourcesRepository.save(resources);
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
