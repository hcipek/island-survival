package com.example.testproject.service;

import com.example.testproject.model.Building;
import com.example.testproject.model.BuildingType;
import com.example.testproject.model.InventoryOnDeath;
import com.example.testproject.model.ItemType;
import com.example.testproject.model.dto.BoatDto;
import com.example.testproject.model.dto.BuildingDto;
import com.example.testproject.model.dto.WorldDto;
import com.example.testproject.model.dto.WorldSettingsDto;
import com.example.testproject.model.req.BuildingRequest;
import com.example.testproject.model.req.CreateBoatRequest;
import com.example.testproject.model.req.CreateUserRequest;
import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.model.req.WorldSettingsRequest;
import com.example.testproject.repository.BoatRepository;
import com.example.testproject.repository.BuildingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Every test runs in a transaction that is rolled back afterwards, so tests
/// don't see each other's worlds, users or buildings.
@SpringBootTest
@Transactional
class WorldServiceTest {

    // The home island is always centered at (0, 0) with radius 600, so its
    // grass ends at 504 and its sand ring runs from 504 to 600. Other islands
    // are generated at least 900 away from the center, so 700 is always sea.
    private static final double ON_GRASS = 100;
    private static final double ON_SAND = 550;
    private static final double IN_SEA = 700;

    @Autowired
    private WorldService worldService;

    @Autowired
    private UserService userService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private BoatRepository boatRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    private long worldId;
    private long aliceId;
    private long bobId;

    @BeforeEach
    void setUp() {
        worldId = worldService.createWorld(new CreateWorldRequest("Test world")).id();
        aliceId = userService.createUser(new CreateUserRequest("Alice")).id();
        bobId = userService.createUser(new CreateUserRequest("Bob")).id();
        // Building takes a hammer in your hand: into the first slot, which
        // is the one held unless another is picked.
        inventoryService.add(worldId, aliceId, ItemType.HAMMER, 1);
        inventoryService.add(worldId, bobId, ItemType.HAMMER, 1);
    }

    @Test
    void youNeedToHoldAHammerToBuild() {
        inventoryService.select(worldId, aliceId, 3); // an empty hand

        assertStatus(HttpStatus.CONFLICT, () -> build(BuildingType.PORT, ON_SAND));
        assertThat(worldService.getBuildings(worldId)).isEmpty();
    }

    @Test
    void youNeedToHoldAHammerToBuildABoat() {
        BuildingDto port = build(BuildingType.PORT, ON_SAND);
        give(aliceId, 10);
        inventoryService.select(worldId, aliceId, 3);

        assertStatus(HttpStatus.CONFLICT,
                () -> worldService.createBoat(worldId, new CreateBoatRequest(port.id(), aliceId)));
        assertThat(wood(aliceId)).isEqualTo(10);
    }

    @Test
    void aNewWorldGetsDefaultSettings() {
        WorldSettingsDto settings = worldService.getWorld(worldId).settings();

        assertThat(settings.animalAggression()).isTrue();
        assertThat(settings.inventoryOnDeath()).isEqualTo(InventoryOnDeath.DROP);
    }

    @Test
    void settingsCanBeChosenWhenCreatingAWorld() {
        long peaceful = worldService.createWorld(new CreateWorldRequest("Peaceful", false, "KEEP")).id();

        WorldSettingsDto settings = worldService.getWorld(peaceful).settings();
        assertThat(settings.animalAggression()).isFalse();
        assertThat(settings.inventoryOnDeath()).isEqualTo(InventoryOnDeath.KEEP);
    }

    @Test
    void aNewWorldHasNotStartedAndItsSettingsCanStillChange() {
        assertThat(worldService.getWorld(worldId).started()).isFalse();

        WorldDto changed = worldService.updateSettings(worldId, new WorldSettingsRequest(false, "LOSE"));

        assertThat(changed.settings().animalAggression()).isFalse();
        assertThat(changed.settings().inventoryOnDeath()).isEqualTo(InventoryOnDeath.LOSE);
    }

    @Test
    void settingsLeftOutStayAsTheyAre() {
        worldService.updateSettings(worldId, new WorldSettingsRequest(null, "KEEP"));

        WorldSettingsDto settings = worldService.getWorld(worldId).settings();
        assertThat(settings.animalAggression()).isTrue();
        assertThat(settings.inventoryOnDeath()).isEqualTo(InventoryOnDeath.KEEP);
    }

    @Test
    void onceAWorldHasStartedItsSettingsAreFixed() {
        worldService.markStarted(worldId);

        assertThat(worldService.getWorld(worldId).started()).isTrue();
        assertStatus(HttpStatus.CONFLICT,
                () -> worldService.updateSettings(worldId, new WorldSettingsRequest(false, null)));
        assertThat(worldService.getWorld(worldId).settings().animalAggression()).isTrue();
    }

    @Test
    void nobodyIsPlayingInANewWorld() {
        assertThat(worldService.getWorld(worldId).playing()).isEmpty();
    }

    @Test
    void unknownSettingValuesAreRejected() {
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> worldService.createWorld(new CreateWorldRequest("Odd", true, "BURN")));
    }

    @Test
    void everyoneStartsWithoutWood() {
        assertThat(wood(aliceId)).isZero();
        assertThat(wood(bobId)).isZero();
    }

    @Test
    void aLumberjackCostsWoodFromYourInventory() {
        give(aliceId, 25);

        build(BuildingType.LUMBERJACK, ON_GRASS);

        assertThat(wood(aliceId)).isEqualTo(5);
        assertThat(wood(bobId)).isZero();
    }

    @Test
    void lumberjackNeedsEnoughWood() {
        give(aliceId, 5);

        assertStatus(HttpStatus.CONFLICT, () -> build(BuildingType.LUMBERJACK, ON_GRASS));
        assertThat(wood(aliceId)).isEqualTo(5);
    }

    @Test
    void onlyOneLumberjackPerPlayer() {
        give(aliceId, 100);
        build(BuildingType.LUMBERJACK, ON_GRASS);

        assertStatus(HttpStatus.CONFLICT, () -> build(BuildingType.LUMBERJACK, -ON_GRASS));
    }

    @Test
    void someoneElsesLumberjackDoesNotUseUpYourLimit() {
        give(aliceId, 20);
        give(bobId, 20);
        build(BuildingType.LUMBERJACK, ON_GRASS);

        BuildingDto bobs = worldService.createBuilding(
                worldId, new BuildingRequest(BuildingType.LUMBERJACK, -ON_GRASS, 0, bobId));

        assertThat(bobs.ownerId()).isEqualTo(bobId);
    }

    @Test
    void aLumberjackSlowlyFillsItsOwnStorage() {
        BuildingDto lumberjack = lumberjackForAlice();
        pretendProducingForSeconds(lumberjack, 100);

        // 100 seconds * 0.1 wood per second
        BuildingDto now = buildingNamed(lumberjack.id());
        assertThat(now.storedWood()).isCloseTo(10, within(0.1));
        assertThat(now.woodPerSecond()).isEqualTo(0.1);
        assertThat(wood(aliceId)).isZero(); // nothing arrives until collected
    }

    @Test
    void storageStopsFillingWhenFull() {
        BuildingDto lumberjack = lumberjackForAlice();
        pretendProducingForSeconds(lumberjack, 100_000);

        assertThat(buildingNamed(lumberjack.id()).storedWood()).isEqualTo(50);
    }

    @Test
    void collectingMovesTheStoredWoodIntoYourInventory() {
        BuildingDto lumberjack = lumberjackForAlice();
        pretendProducingForSeconds(lumberjack, 100);

        BuildingDto after = worldService.collectFromLumberjack(worldId, aliceId, lumberjack.id(), ON_GRASS, 30);

        assertThat(wood(aliceId)).isEqualTo(10);
        assertThat(after.storedWood()).isCloseTo(0, within(0.1));
    }

    @Test
    void collectingOnlyTakesWhatFitsInYourBag() {
        BuildingDto lumberjack = lumberjackForAlice();
        pretendProducingForSeconds(lumberjack, 100);
        // Fill every other slot with stones (the hammer has one), and the
        // last with 45 wood.
        for (int i = 0; i < InventoryService.SLOT_COUNT - 2; i++) {
            inventoryService.add(worldId, aliceId, ItemType.STONE, 50);
        }
        give(aliceId, 45);

        BuildingDto after = worldService.collectFromLumberjack(worldId, aliceId, lumberjack.id(), ON_GRASS, 30);

        assertThat(wood(aliceId)).isEqualTo(50);
        assertThat(after.storedWood()).isCloseTo(5, within(0.1)); // the rest waits there
    }

    @Test
    void youHaveToBeNextToYourLumberjackToCollect() {
        BuildingDto lumberjack = lumberjackForAlice();
        pretendProducingForSeconds(lumberjack, 100);

        assertStatus(HttpStatus.CONFLICT,
                () -> worldService.collectFromLumberjack(worldId, aliceId, lumberjack.id(), -400, 0));
    }

    @Test
    void youCannotCollectFromSomeoneElsesLumberjack() {
        BuildingDto lumberjack = lumberjackForAlice();
        pretendProducingForSeconds(lumberjack, 100);

        assertStatus(HttpStatus.FORBIDDEN,
                () -> worldService.collectFromLumberjack(worldId, bobId, lumberjack.id(), ON_GRASS, 30));
    }

    @Test
    void thereHasToBeSomethingToCollect() {
        BuildingDto lumberjack = lumberjackForAlice();

        assertStatus(HttpStatus.CONFLICT,
                () -> worldService.collectFromLumberjack(worldId, aliceId, lumberjack.id(), ON_GRASS, 30));
    }

    @Test
    void portCanBeBuiltOnSand() {
        BuildingDto port = build(BuildingType.PORT, ON_SAND);

        assertThat(port.type()).isEqualTo(BuildingType.PORT);
        assertThat(worldService.getBuildings(worldId)).hasSize(1);
    }

    @Test
    void portCannotBeBuiltOnGrass() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> build(BuildingType.PORT, ON_GRASS));
    }

    @Test
    void lumberjackCannotBeBuiltOnSand() {
        give(aliceId, 20);

        assertStatus(HttpStatus.BAD_REQUEST, () -> build(BuildingType.LUMBERJACK, ON_SAND));
    }

    @Test
    void nothingCanBeBuiltInTheSea() {
        give(aliceId, 20);

        assertStatus(HttpStatus.BAD_REQUEST, () -> build(BuildingType.PORT, IN_SEA));
        assertStatus(HttpStatus.BAD_REQUEST, () -> build(BuildingType.LUMBERJACK, IN_SEA));
        assertThat(worldService.getBuildings(worldId)).isEmpty();
    }

    @Test
    void ownerCanRemoveTheirBuilding() {
        BuildingDto port = build(BuildingType.PORT, ON_SAND);

        worldService.deleteBuilding(worldId, port.id(), aliceId);

        assertThat(worldService.getBuildings(worldId)).isEmpty();
    }

    @Test
    void otherPlayersCannotRemoveSomeoneElsesBuilding() {
        BuildingDto port = build(BuildingType.PORT, ON_SAND);

        assertStatus(HttpStatus.FORBIDDEN, () -> worldService.deleteBuilding(worldId, port.id(), bobId));
        assertThat(worldService.getBuildings(worldId)).hasSize(1);
    }

    @Test
    void boatIsBuiltJustOffTheCoastNextToThePort() {
        BuildingDto port = build(BuildingType.PORT, ON_SAND);
        give(aliceId, 10);

        BoatDto boat = worldService.createBoat(worldId, new CreateBoatRequest(port.id(), aliceId));

        // Port is due east of the home island's center, so the boat is too,
        // at 1.05 * the island's radius of 600.
        assertThat(boat.x()).isCloseTo(630, within(0.01));
        assertThat(boat.y()).isCloseTo(0, within(0.01));
        assertThat(boat.ownerId()).isEqualTo(aliceId);
        assertThat(boatRepository.findAllByWorldId(worldId)).hasSize(1);
    }

    @Test
    void aBoatCostsWoodFromYourInventory() {
        BuildingDto port = build(BuildingType.PORT, ON_SAND);
        give(aliceId, 15);

        worldService.createBoat(worldId, new CreateBoatRequest(port.id(), aliceId));

        assertThat(wood(aliceId)).isEqualTo(5);
    }

    @Test
    void boatNeedsEnoughWood() {
        BuildingDto port = build(BuildingType.PORT, ON_SAND);
        give(aliceId, 5);

        assertStatus(HttpStatus.CONFLICT,
                () -> worldService.createBoat(worldId, new CreateBoatRequest(port.id(), aliceId)));
        assertThat(boatRepository.findAllByWorldId(worldId)).isEmpty();
    }

    @Test
    void boatCanOnlyBeBuiltAtYourOwnPort() {
        BuildingDto alicesPort = build(BuildingType.PORT, ON_SAND);
        give(bobId, 10);

        assertStatus(HttpStatus.FORBIDDEN,
                () -> worldService.createBoat(worldId, new CreateBoatRequest(alicesPort.id(), bobId)));
    }

    @Test
    void boatCannotBeBuiltAtALumberjack() {
        BuildingDto lumberjack = lumberjackForAlice();
        give(aliceId, 10);

        assertStatus(HttpStatus.NOT_FOUND,
                () -> worldService.createBoat(worldId, new CreateBoatRequest(lumberjack.id(), aliceId)));
    }

    @Test
    void unknownWorldIsNotFound() {
        assertStatus(HttpStatus.NOT_FOUND, () -> worldService.getState(worldId + 999, aliceId));
    }

    /// Builds as Alice at (x, 0), i.e. a point that far east of the home
    /// island's center.
    private BuildingDto build(BuildingType type, double x) {
        return worldService.createBuilding(worldId, new BuildingRequest(type, x, 0, aliceId));
    }

    private BuildingDto lumberjackForAlice() {
        give(aliceId, 20);
        return build(BuildingType.LUMBERJACK, ON_GRASS);
    }

    private BuildingDto buildingNamed(long buildingId) {
        return worldService.getBuildings(worldId).stream()
                .filter(b -> b.id() == buildingId)
                .findFirst()
                .orElseThrow();
    }

    private void pretendProducingForSeconds(BuildingDto dto, long seconds) {
        Building building = buildingRepository.findById(dto.id()).orElseThrow();
        building.setProducedUntil(Instant.now().minusSeconds(seconds));
        buildingRepository.save(building);
    }

    private void give(long userId, int wood) {
        inventoryService.add(worldId, userId, ItemType.WOOD, wood);
    }

    private int wood(long userId) {
        return inventoryService.count(worldId, userId, ItemType.WOOD);
    }

    private static void assertStatus(HttpStatus expected, Executable action) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, action);
        assertThat(ex.getStatusCode()).isEqualTo(expected);
    }
}
