package com.example.testproject.service;

import com.example.testproject.model.BuildingType;
import com.example.testproject.model.World;
import com.example.testproject.model.dto.BoatDto;
import com.example.testproject.model.dto.BuildingDto;
import com.example.testproject.model.req.BuildingRequest;
import com.example.testproject.model.req.CreateBoatRequest;
import com.example.testproject.model.req.CreateUserRequest;
import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.model.req.UpdatePositionRequest;
import com.example.testproject.repository.WorldRepository;
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
    private WorldRepository worldRepository;

    private long worldId;
    private long aliceId;
    private long bobId;

    @BeforeEach
    void setUp() {
        worldId = worldService.createWorld(new CreateWorldRequest("Test world")).id();
        aliceId = userService.createUser(new CreateUserRequest("Alice")).id();
        bobId = userService.createUser(new CreateUserRequest("Bob")).id();
    }

    @Test
    void newWorldStartsWithEnoughWoodForOneLumberjack() {
        assertThat(worldService.getState(worldId).wood()).isEqualTo(20);
    }

    @Test
    void buildingALumberjackSpendsWood() {
        build(BuildingType.LUMBERJACK, ON_GRASS);

        assertThat(worldService.getState(worldId).wood()).isCloseTo(0, within(0.1));
        assertThat(worldService.getState(worldId).woodPerSecond()).isEqualTo(0.5);
    }

    @Test
    void lumberjackProducesWoodOverTime() {
        build(BuildingType.LUMBERJACK, ON_GRASS);
        pretendLastCollectedSecondsAgo(10);

        // 10 seconds * 0.5 wood per second
        assertThat(worldService.getState(worldId).wood()).isCloseTo(5, within(0.1));
    }

    @Test
    void lumberjackNeedsEnoughWood() {
        setWood(5);

        assertStatus(HttpStatus.CONFLICT, () -> build(BuildingType.LUMBERJACK, ON_GRASS));
        assertThat(worldService.getState(worldId).wood()).isCloseTo(5, within(0.1));
    }

    @Test
    void onlyOneLumberjackPerWorld() {
        setWood(100);
        build(BuildingType.LUMBERJACK, ON_GRASS);

        assertStatus(HttpStatus.CONFLICT, () -> build(BuildingType.LUMBERJACK, -ON_GRASS));
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
        assertStatus(HttpStatus.BAD_REQUEST, () -> build(BuildingType.LUMBERJACK, ON_SAND));
    }

    @Test
    void nothingCanBeBuiltInTheSea() {
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

        BoatDto boat = worldService.createBoat(worldId, new CreateBoatRequest(port.id(), aliceId));

        // Port is due east of the home island's center, so the boat is too,
        // at 1.05 * the island's radius of 600.
        assertThat(boat.x()).isCloseTo(630, within(0.01));
        assertThat(boat.y()).isCloseTo(0, within(0.01));
        assertThat(boat.ownerId()).isEqualTo(aliceId);
        assertThat(worldService.getBoats(worldId)).hasSize(1);
    }

    @Test
    void buildingABoatSpendsWood() {
        BuildingDto port = build(BuildingType.PORT, ON_SAND);

        worldService.createBoat(worldId, new CreateBoatRequest(port.id(), aliceId));

        assertThat(worldService.getState(worldId).wood()).isCloseTo(10, within(0.1));
    }

    @Test
    void boatNeedsEnoughWood() {
        BuildingDto port = build(BuildingType.PORT, ON_SAND);
        setWood(5);

        assertStatus(HttpStatus.CONFLICT,
                () -> worldService.createBoat(worldId, new CreateBoatRequest(port.id(), aliceId)));
        assertThat(worldService.getBoats(worldId)).isEmpty();
    }

    @Test
    void boatCanOnlyBeBuiltAtYourOwnPort() {
        BuildingDto alicesPort = build(BuildingType.PORT, ON_SAND);

        assertStatus(HttpStatus.FORBIDDEN,
                () -> worldService.createBoat(worldId, new CreateBoatRequest(alicesPort.id(), bobId)));
    }

    @Test
    void boatCannotBeBuiltAtALumberjack() {
        BuildingDto lumberjack = build(BuildingType.LUMBERJACK, ON_GRASS);

        assertStatus(HttpStatus.NOT_FOUND,
                () -> worldService.createBoat(worldId, new CreateBoatRequest(lumberjack.id(), aliceId)));
    }

    @Test
    void ownerCanSailTheirBoat() {
        BoatDto boat = buildBoatForAlice();

        worldService.updateBoatPosition(worldId, boat.id(), aliceId, new UpdatePositionRequest(1000, 200));

        BoatDto moved = worldService.getBoats(worldId).getFirst();
        assertThat(moved.x()).isEqualTo(1000);
        assertThat(moved.y()).isEqualTo(200);
    }

    @Test
    void otherPlayersCannotSailSomeoneElsesBoat() {
        BoatDto boat = buildBoatForAlice();

        assertStatus(HttpStatus.FORBIDDEN, () -> worldService.updateBoatPosition(
                worldId, boat.id(), bobId, new UpdatePositionRequest(1000, 200)));
    }

    @Test
    void unknownWorldIsNotFound() {
        assertStatus(HttpStatus.NOT_FOUND, () -> worldService.getState(worldId + 999));
    }

    /// Builds as Alice at (x, 0), i.e. a point that far east of the home
    /// island's center.
    private BuildingDto build(BuildingType type, double x) {
        return worldService.createBuilding(worldId, new BuildingRequest(type, x, 0, aliceId));
    }

    private BoatDto buildBoatForAlice() {
        BuildingDto port = build(BuildingType.PORT, ON_SAND);
        return worldService.createBoat(worldId, new CreateBoatRequest(port.id(), aliceId));
    }

    private void setWood(double wood) {
        World world = worldRepository.findById(worldId).orElseThrow();
        world.setWood(wood);
        worldRepository.save(world);
    }

    private void pretendLastCollectedSecondsAgo(long seconds) {
        World world = worldRepository.findById(worldId).orElseThrow();
        world.setLastCollectedAt(Instant.now().minusSeconds(seconds));
        worldRepository.save(world);
    }

    private static void assertStatus(HttpStatus expected, Executable action) {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, action);
        assertThat(ex.getStatusCode()).isEqualTo(expected);
    }
}
