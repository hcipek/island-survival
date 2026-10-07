package com.example.testproject.live;

import com.example.testproject.model.AnimalType;
import com.example.testproject.model.BuildingType;
import com.example.testproject.model.ItemType;
import com.example.testproject.model.dto.BoatDto;
import com.example.testproject.model.dto.BuildingDto;
import com.example.testproject.model.req.BuildingRequest;
import com.example.testproject.model.req.CreateBoatRequest;
import com.example.testproject.model.req.CreateUserRequest;
import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.repository.BoatRepository;
import com.example.testproject.repository.BuildingRepository;
import com.example.testproject.repository.PlayerVitalsRepository;
import com.example.testproject.repository.ResourceNodeRepository;
import com.example.testproject.repository.WorldRepository;
import com.example.testproject.service.AnimalService;
import com.example.testproject.service.InventoryService;
import com.example.testproject.service.UserService;
import com.example.testproject.service.WorldService;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/// End-to-end tests of the world socket: a real server on a random port,
/// with real WebSocket clients playing Alice and Bob.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveWorldTest {

    // On the home island, which is centered at (0, 0) with radius 600.
    private static final Map<String, Object> ON_SAND = point(550, 0);

    @LocalServerPort
    private int port;

    @Autowired
    private WorldService worldService;

    @Autowired
    private UserService userService;

    @Autowired
    private BoatRepository boatRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private WorldSessionRegistry registry;

    @Autowired
    private WorldTicker worldTicker;

    @Autowired
    private LiveWorldService liveWorldService;

    @Autowired
    private AnimalService animalService;

    @Autowired
    private ResourceNodeRepository resourceNodeRepository;

    @Autowired
    private WorldRepository worldRepository;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private PlayerVitalsRepository playerVitalsRepository;

    private final List<TestClient> clients = new ArrayList<>();
    private long worldId;
    private long aliceId;
    private long bobId;

    @BeforeEach
    void setUp() {
        // Not transactional (the server runs on its own threads), so give
        // every test its own world and users instead.
        worldId = worldService.createWorld(new CreateWorldRequest("Live test world")).id();
        aliceId = userService.createUser(new CreateUserRequest("Alice-" + UUID.randomUUID())).id();
        bobId = userService.createUser(new CreateUserRequest("Bob-" + UUID.randomUUID())).id();
    }

    @AfterEach
    void closeClients() throws Exception {
        for (TestClient client : clients) {
            client.session.close();
        }
    }

    @Test
    void connectingSendsASnapshotOfTheWorld() throws Exception {
        holdHammer(bobId);
        worldService.createBuilding(worldId, new BuildingRequest(BuildingType.PORT, 550, 0, bobId));

        Map<String, Object> snapshot = connect(aliceId).expect("snapshot");

        assertThat(list(snapshot, "buildings")).hasSize(1);
        assertThat(list(snapshot, "boats")).isEmpty();
        assertThat(list(snapshot, "players")).isEmpty();
        assertThat(number(map(snapshot, "state"), "hunger")).isEqualTo(100);
        assertThat(list(map(snapshot, "inventory"), "slots")).isEmpty(); // everyone starts empty-handed
        assertThat(list(snapshot, "recipes")).extracting(r -> r.get("id")).containsExactly("AXE", "HAMMER", "SPEAR");
        Map<String, Object> settings = map(map(snapshot, "world"), "settings");
        assertThat(settings.get("animalAggression")).isEqualTo(true);
        assertThat(settings.get("inventoryOnDeath")).isEqualTo("DROP");
        assertThat(list(snapshot, "nodes")).isNotEmpty()
                .allSatisfy(node -> assertThat(number(node, "regrowsInMs")).isZero());
    }

    @Test
    void otherPlayersSeeYouJoinAndWalk() throws Exception {
        TestClient alice = connectAndJoin(aliceId);
        TestClient bob = connect(bobId);

        List<Map<String, Object>> players = list(bob.expect("snapshot"), "players");
        assertThat(players).singleElement().satisfies(p -> assertThat(number(p, "userId")).isEqualTo(aliceId));

        alice.send(Map.of("type", "move", "from", point(0, 0), "path", List.of(point(100, 0))));

        Map<String, Object> moved = bob.expect("playerMoved");
        assertThat(number(moved, "userId")).isEqualTo(aliceId);
        Map<String, Object> movement = map(moved, "movement");
        assertThat(number(list(movement, "path").getFirst(), "x")).isEqualTo(100);
        alice.expectNothing(); // you don't get your own movement back
    }

    @Test
    void boardingSailingAndLandingAreSharedWithEveryone() throws Exception {
        BoatDto boat = boatForAlice();
        TestClient alice = connectAndJoin(aliceId);
        TestClient bob = connectAndJoin(bobId);
        alice.expect("playerJoined"); // Bob arriving

        alice.send(Map.of("type", "board", "boatId", boat.id()));
        assertThat(number(bob.expect("playerBoarded"), "boatId")).isEqualTo(boat.id());

        alice.send(Map.of(
                "type", "sail", "boatId", boat.id(),
                "from", point(boat.x(), boat.y()), "path", List.of(point(1000, 1000))
        ));
        assertThat(number(bob.expect("boatMoved"), "boatId")).isEqualTo(boat.id());
        assertThat(boatRepository.findById(boat.id()).orElseThrow().getX()).isEqualTo(1000);

        // Someone arriving mid-voyage sees Alice aboard and the boat's
        // movement so far, so they can catch up.
        TestClient carol = connect(userService.createUser(new CreateUserRequest("Carol-" + UUID.randomUUID())).id());
        Map<String, Object> snapshot = carol.expect("snapshot");
        assertThat(map(list(snapshot, "boats").getFirst(), "movement")).isNotNull();
        assertThat(number(list(snapshot, "players").stream()
                .filter(p -> number(p, "userId") == aliceId).findFirst().orElseThrow(), "boatId"))
                .isEqualTo(boat.id());

        alice.send(Map.of("type", "land", "boatId", boat.id(), "position", point(1200, 1200)));
        assertThat(number(map(bob.expect("playerLanded"), "position"), "x")).isEqualTo(1200);
        assertThat(number(carol.expect("playerLanded"), "userId")).isEqualTo(aliceId);
    }

    @Test
    void youCannotBoardSomeoneElsesBoat() throws Exception {
        BoatDto alicesBoat = boatForAlice();
        TestClient alice = connectAndJoin(aliceId);
        TestClient bob = connectAndJoin(bobId);
        alice.expect("playerJoined");

        bob.send(Map.of("type", "board", "boatId", alicesBoat.id()));

        assertThat(bob.expect("error").get("message")).isEqualTo("That's another player's boat");
        alice.expectNothing();
    }

    @Test
    void youCannotSailWithoutBoardingFirst() throws Exception {
        BoatDto boat = boatForAlice();
        TestClient alice = connectAndJoin(aliceId);

        alice.send(Map.of("type", "sail", "boatId", boat.id(), "from", point(0, 0), "path", List.of(point(9, 9))));

        assertThat(alice.expect("error").get("message")).isEqualTo("You're not aboard that boat");
    }

    @Test
    void movingBeforeJoiningIsRejected() throws Exception {
        TestClient alice = connect(aliceId);
        alice.expect("snapshot");

        alice.send(Map.of("type", "move", "from", point(0, 0), "path", List.of(point(1, 1))));

        assertThat(alice.expect("error").get("message")).isEqualTo("Send join first");
    }

    @Test
    void othersSeeYouLeave() throws Exception {
        TestClient alice = connectAndJoin(aliceId);
        TestClient bob = connectAndJoin(bobId);
        alice.expect("playerJoined");

        alice.session.close();

        assertThat(number(bob.expect("playerLeft"), "userId")).isEqualTo(aliceId);
    }

    @Test
    void buildingsArePushedToEveryoneButTheCostOnlyToItsOwner() throws Exception {
        holdHammer(aliceId);
        inventoryService.add(worldId, aliceId, ItemType.WOOD, 25);
        TestClient alice = connect(aliceId);
        alice.expect("snapshot");
        TestClient bob = connect(bobId);
        bob.expect("snapshot");

        worldService.createBuilding(worldId, new BuildingRequest(BuildingType.LUMBERJACK, 100, 0, aliceId));

        Map<String, Object> lumberjack = map(alice.expect("buildingAdded"), "building");
        assertThat(lumberjack.get("type")).isEqualTo("LUMBERJACK");
        assertThat(number(lumberjack, "woodPerSecond")).isEqualTo(0.1);
        assertThat(number(slotWith("WOOD", alice.expect("inventory")), "quantity")).isEqualTo(5);

        assertThat(map(bob.expect("buildingAdded"), "building").get("type")).isEqualTo("LUMBERJACK");
        bob.expectNothing(); // Alice's inventory is none of Bob's business
    }

    @Test
    void collectingFromYourLumberjackIsSeenByEveryone() throws Exception {
        holdHammer(aliceId);
        inventoryService.add(worldId, aliceId, ItemType.WOOD, 20);
        BuildingDto lumberjack = worldService.createBuilding(
                worldId, new BuildingRequest(BuildingType.LUMBERJACK, 100, 0, aliceId));
        var building = buildingRepository.findById(lumberjack.id()).orElseThrow();
        building.setProducedUntil(java.time.Instant.now().minusSeconds(100));
        buildingRepository.save(building);
        TestClient bob = connectAndJoin(bobId);
        TestClient alice = connect(aliceId);
        alice.expect("snapshot");
        alice.send(Map.of("type", "join", "position", point(130, 0)));
        alice.expect("state");
        bob.expect("playerJoined");

        alice.send(Map.of("type", "collect", "buildingId", lumberjack.id()));

        assertThat(number(map(bob.expect("buildingUpdated"), "building"), "storedWood")).isCloseTo(0, within(0.1));
        alice.expect("buildingUpdated");
        assertThat(number(slotWith("WOOD", alice.expect("inventory")), "quantity")).isEqualTo(10);
    }

    @Test
    void gatheringIsSeenByEveryoneButOnlyTheGathererGetsTheBerries() throws Exception {
        TestClient bob = connectAndJoin(bobId);
        TestClient alice = connect(aliceId);
        Map<String, Object> bush = list(alice.expect("snapshot"), "nodes").stream()
                .filter(n -> "BERRY_BUSH".equals(n.get("type"))).findFirst().orElseThrow();
        alice.send(Map.of("type", "join", "position", point(number(bush, "x"), number(bush, "y"))));
        alice.expect("state");
        bob.expect("playerJoined");

        alice.send(Map.of("type", "gather", "nodeId", number(bush, "id")));

        Map<String, Object> depleted = bob.expect("nodeDepleted");
        assertThat(number(depleted, "nodeId")).isEqualTo(number(bush, "id"));
        assertThat(number(depleted, "regrowsInMs")).isPositive();
        alice.expect("nodeDepleted");
        Map<String, Object> slot = list(map(alice.expect("inventory"), "inventory"), "slots").getFirst();
        assertThat(slot.get("item")).isEqualTo("BERRY");
        assertThat(number(slot, "quantity")).isEqualTo(3);
        bob.expectNothing();
    }

    @Test
    void pickedUpSticksDisappearForEveryoneAndNewOnesDropLater() throws Exception {
        TestClient bob = connectAndJoin(bobId);
        TestClient alice = connect(aliceId);
        Map<String, Object> sticks = list(alice.expect("snapshot"), "nodes").stream()
                .filter(n -> "STICKS".equals(n.get("type"))).findFirst().orElseThrow();
        alice.send(Map.of("type", "join", "position", point(number(sticks, "x"), number(sticks, "y"))));
        alice.expect("state");
        bob.expect("playerJoined");

        alice.send(Map.of("type", "gather", "nodeId", number(sticks, "id")));

        assertThat(number(bob.expect("nodeRemoved"), "nodeId")).isEqualTo(number(sticks, "id"));
        alice.expect("nodeRemoved");
        alice.expect("inventory");

        worldTicker.tickActiveWorlds(1.0);

        Map<String, Object> dropped = map(bob.expect("nodeAdded"), "node");
        // Everything else was already at its limit when the world was made.
        assertThat(dropped.get("type")).isEqualTo("STICKS");
        assertThat(number(dropped, "id")).isNotEqualTo(number(sticks, "id"));
        assertThat(map(alice.expect("nodeAdded"), "node").get("id")).isEqualTo(dropped.get("id"));
    }

    @Test
    void leavingAndComingStraightBackDoesNotRefillSticks() throws Exception {
        TestClient alice = connect(aliceId);
        List<Map<String, Object>> before = sticksIn(alice.expect("snapshot"));
        Map<String, Object> sticks = before.getFirst();
        alice.send(Map.of("type", "join", "position", point(number(sticks, "x"), number(sticks, "y"))));
        alice.expect("state");
        alice.send(Map.of("type", "gather", "nodeId", number(sticks, "id")));
        alice.expect("nodeRemoved");
        alice.session.close();
        awaitNobodyIn(worldId);

        TestClient again = connect(aliceId);

        assertThat(sticksIn(again.expect("snapshot"))).hasSize(before.size() - 1);
    }

    @Test
    void comingBackAfterALongTimeRefillsSticks() throws Exception {
        TestClient alice = connect(aliceId);
        List<Map<String, Object>> full = sticksIn(alice.expect("snapshot"));
        Map<String, Object> sticks = full.getFirst();
        alice.send(Map.of("type", "join", "position", point(number(sticks, "x"), number(sticks, "y"))));
        alice.expect("state");
        alice.send(Map.of("type", "gather", "nodeId", number(sticks, "id")));
        alice.expect("nodeRemoved");
        alice.session.close();
        awaitNobodyIn(worldId);

        var world = worldRepository.findById(worldId).orElseThrow();
        world.setLastActiveAt(java.time.Instant.now().minusSeconds(3600));
        worldRepository.save(world);
        TestClient again = connect(aliceId);

        // An hour is plenty of missed ticks to fill the islands again.
        assertThat(sticksIn(again.expect("snapshot"))).hasSize(full.size());
    }

    @Test
    void youCannotGatherFromABoat() throws Exception {
        BoatDto boat = boatForAlice();
        TestClient alice = connectAndJoin(aliceId);
        alice.send(Map.of("type", "board", "boatId", boat.id()));

        alice.send(Map.of("type", "gather", "nodeId", 1));

        assertThat(alice.expect("error").get("message")).isEqualTo("Land on the island first");
    }

    @Test
    void youEatTheBerryYouAreHolding() throws Exception {
        inventoryService.add(worldId, aliceId, ItemType.BERRY, 3);
        TestClient bob = connectAndJoin(bobId);
        TestClient alice = connectAndJoin(aliceId);
        bob.expect("playerJoined");
        var vitals = playerVitalsRepository.findByWorldIdAndUserId(worldId, aliceId).orElseThrow();
        vitals.setHunger(50);
        playerVitalsRepository.save(vitals);

        alice.send(Map.of("type", "use"));

        assertThat(number(map(alice.expect("state"), "state"), "hunger")).isCloseTo(65, within(0.1));
        assertThat(number(list(map(alice.expect("inventory"), "inventory"), "slots").getFirst(), "quantity"))
                .isEqualTo(2);
        bob.expectNothing();
    }

    @Test
    void youCanOnlyEatWhatYouAreHolding() throws Exception {
        inventoryService.add(worldId, aliceId, ItemType.BERRY, 3); // lands in slot 0
        TestClient alice = connectAndJoin(aliceId);

        alice.send(Map.of("type", "select", "slot", 1));
        assertThat(number(map(alice.expect("inventory"), "inventory"), "selectedSlot")).isEqualTo(1);
        alice.send(Map.of("type", "use"));

        assertThat(alice.expect("error").get("message")).isEqualTo("You're not holding anything");
    }

    @Test
    void rearrangingAndThrowingAwayItems() throws Exception {
        inventoryService.add(worldId, aliceId, ItemType.WOOD, 5); // slot 0
        TestClient alice = connectAndJoin(aliceId);

        alice.send(Map.of("type", "moveItem", "fromSlot", 0, "toSlot", 15));
        Map<String, Object> moved = list(map(alice.expect("inventory"), "inventory"), "slots").getFirst();
        assertThat(number(moved, "slot")).isEqualTo(15);

        alice.send(Map.of("type", "discard", "slot", 15));
        assertThat(list(map(alice.expect("inventory"), "inventory"), "slots")).isEmpty();
    }

    @Test
    void craftingAnAxeOverTheSocket() throws Exception {
        inventoryService.add(worldId, aliceId, ItemType.WOOD, 3);
        inventoryService.add(worldId, aliceId, ItemType.STONE, 2);
        TestClient alice = connectAndJoin(aliceId);

        alice.send(Map.of("type", "craft", "recipe", "AXE"));

        assertThat(list(map(alice.expect("inventory"), "inventory"), "slots"))
                .singleElement().satisfies(slot -> assertThat(slot.get("item")).isEqualTo("AXE"));
    }

    @Test
    void comingBackPutsYouWhereYouLeft() throws Exception {
        TestClient first = connect(aliceId);
        assertThat(first.expect("snapshot").get("you")).isNull(); // first time here
        first.send(Map.of("type", "join", "position", point(0, 0)));
        first.expect("state");
        first.send(Map.of("type", "move", "from", point(0, 0), "path", List.of(point(120, 80))));
        first.expectNothing(); // let the server take it in
        first.session.close();
        awaitNotPlaying(aliceId);

        Map<String, Object> you = map(connect(aliceId).expect("snapshot"), "you");

        assertThat(number(you, "x")).isEqualTo(120);
        assertThat(number(you, "y")).isEqualTo(80);
        assertThat(you.get("boatId")).isNull();
    }

    @Test
    void leavingWhileAboardKeepsYouOnYourBoat() throws Exception {
        BoatDto boat = boatForAlice();
        TestClient first = connectAndJoin(aliceId);
        first.send(Map.of("type", "board", "boatId", boat.id()));
        first.expectNothing();
        first.session.close();
        awaitNotPlaying(aliceId);

        Map<String, Object> you = map(connect(aliceId).expect("snapshot"), "you");

        assertThat(number(you, "boatId")).isEqualTo(boat.id());
    }

    @Test
    void starvingToDeathBringsYouBackHomeForEveryoneToSee() throws Exception {
        TestClient bob = connectAndJoin(bobId);
        TestClient alice = joinAt(worldId, aliceId, point(100, 0));
        bob.expect("playerJoined");
        aboutToStarve(worldId, aliceId);

        liveWorldService.checkVitals(worldId, aliceId);

        Map<String, Object> respawned = bob.expect("playerRespawned");
        assertThat(number(respawned, "userId")).isEqualTo(aliceId);
        assertThat(number(map(respawned, "position"), "x")).isZero();
        alice.expect("playerRespawned");
        Map<String, Object> died = alice.expect("youDied");
        assertThat(died.get("cause")).isEqualTo("You starved");
        assertThat(died.get("bag")).isEqualTo("EMPTY");
        Map<String, Object> state = map(alice.expect("state"), "state");
        assertThat(number(state, "health")).isEqualTo(100);
        assertThat(number(state, "hunger")).isEqualTo(50);
    }

    @Test
    void inADropWorldYourBagIsLeftInAPileOnlyYouCanPickUp() throws Exception {
        fillQuickAccessBarWithAxes(worldId, aliceId);
        inventoryService.add(worldId, aliceId, ItemType.WOOD, 30); // into the bag
        TestClient bob = connectAndJoin(bobId);
        TestClient alice = joinAt(worldId, aliceId, point(100, 0));
        bob.expect("playerJoined");
        aboutToStarve(worldId, aliceId);

        liveWorldService.checkVitals(worldId, aliceId);

        Map<String, Object> pile = map(bob.expect("nodeAdded"), "node");
        assertThat(pile.get("type")).isEqualTo("ITEM_PILE");
        assertThat(number(pile, "ownerId")).isEqualTo(aliceId);
        assertThat(number(pile, "x")).isEqualTo(100);
        assertThat(number(pile, "expiresInMs")).isPositive();
        alice.expect("nodeAdded");
        alice.expect("playerRespawned");
        assertThat(alice.expect("youDied").get("bag")).isEqualTo("DROPPED");
        alice.expect("state");
        assertThat(list(map(alice.expect("inventory"), "inventory"), "slots")).hasSize(10); // the axes stay
        bob.expect("playerRespawned");

        // Bob can't take it...
        bob.send(Map.of("type", "move", "from", point(0, 0), "path", List.of(point(100, 0))));
        bob.expectNothing();
        bob.send(Map.of("type", "gather", "nodeId", number(pile, "id")));
        assertThat(bob.expect("error").get("message")).isEqualTo("Those are someone else's things");

        // ...but Alice can go back for it.
        alice.expect("playerMoved"); // (Bob walking over)
        alice.send(Map.of("type", "move", "from", point(0, 0), "path", List.of(point(100, 0))));
        alice.send(Map.of("type", "gather", "nodeId", number(pile, "id")));
        assertThat(number(alice.expect("nodeRemoved"), "nodeId")).isEqualTo(number(pile, "id"));
        assertThat(number(slotWith("WOOD", alice.expect("inventory")), "quantity")).isEqualTo(30);
    }

    @Test
    void dyingAtSeaLeavesThePileOnTheNearestShore() throws Exception {
        BoatDto boat = boatForAlice(); // just off the home island's east coast
        inventoryService.add(worldId, aliceId, ItemType.STONE, 50 * 10); // fill the bar...
        inventoryService.add(worldId, aliceId, ItemType.BERRY, 5);       // ...so these go in the bag
        TestClient alice = connectAndJoin(aliceId);
        alice.send(Map.of("type", "board", "boatId", boat.id()));
        alice.expectNothing();
        aboutToStarve(worldId, aliceId);

        liveWorldService.checkVitals(worldId, aliceId);

        // The home island has radius 600 and its sand starts at 552.
        Map<String, Object> pile = map(alice.expect("nodeAdded"), "node");
        assertThat(number(pile, "x")).isCloseTo(552, within(0.5));
        assertThat(number(pile, "y")).isCloseTo(0, within(0.5));
    }

    @Test
    void inAKeepWorldNothingIsLost() throws Exception {
        long keepWorld = worldService.createWorld(new CreateWorldRequest("Kind", true, "KEEP")).id();
        fillQuickAccessBarWithAxes(keepWorld, aliceId);
        inventoryService.add(keepWorld, aliceId, ItemType.WOOD, 30);
        TestClient alice = joinAt(keepWorld, aliceId, point(100, 0));
        aboutToStarve(keepWorld, aliceId);

        liveWorldService.checkVitals(keepWorld, aliceId);

        alice.expect("playerRespawned");
        assertThat(alice.expect("youDied").get("bag")).isEqualTo("KEPT");
        alice.expect("state");
        assertThat(number(slotWith("WOOD", alice.expect("inventory")), "quantity")).isEqualTo(30);
    }

    @Test
    void inALoseWorldTheBagIsGoneButTheQuickAccessBarStays() throws Exception {
        long harshWorld = worldService.createWorld(new CreateWorldRequest("Harsh", true, "LOSE")).id();
        fillQuickAccessBarWithAxes(harshWorld, aliceId);
        inventoryService.add(harshWorld, aliceId, ItemType.WOOD, 30);
        TestClient alice = joinAt(harshWorld, aliceId, point(100, 0));
        aboutToStarve(harshWorld, aliceId);

        liveWorldService.checkVitals(harshWorld, aliceId);

        alice.expect("playerRespawned");
        assertThat(alice.expect("youDied").get("bag")).isEqualTo("LOST");
        alice.expect("state");
        assertThat(list(map(alice.expect("inventory"), "inventory"), "slots"))
                .hasSize(10)
                .allSatisfy(slot -> assertThat(slot.get("item")).isEqualTo("AXE"));
    }

    @Test
    void pilesDisappearAfterAWhile() throws Exception {
        fillQuickAccessBarWithAxes(worldId, aliceId);
        inventoryService.add(worldId, aliceId, ItemType.WOOD, 30);
        TestClient alice = joinAt(worldId, aliceId, point(100, 0));
        aboutToStarve(worldId, aliceId);
        liveWorldService.checkVitals(worldId, aliceId);
        long pileId = (long) number(map(alice.expect("nodeAdded"), "node"), "id");

        var pile = resourceNodeRepository.findById(pileId).orElseThrow();
        pile.setExpiresAt(java.time.Instant.now().minusSeconds(1));
        resourceNodeRepository.save(pile);
        worldTicker.tickActiveWorlds(0);

        Map<String, Object> removed;
        do {
            removed = alice.expectAny();
        } while (!"nodeRemoved".equals(removed.get("type")));
        assertThat(number(removed, "nodeId")).isEqualTo(pileId);
        assertThat(resourceNodeRepository.findById(pileId)).isEmpty();
    }

    @Test
    void theSnapshotListsTheAnimals() throws Exception {
        List<Map<String, Object>> animals = list(connect(aliceId).expect("snapshot"), "animals");

        assertThat(animals).isNotEmpty();
        assertThat(animals).extracting(a -> a.get("type")).contains("RABBIT", "BOAR");
        assertThat(animals).allSatisfy(a -> assertThat(number(a, "speed")).isPositive());
    }

    @Test
    void aBoarBitesAPlayerStandingNextToIt() throws Exception {
        TestClient alice = joinAt(worldId, aliceId, point(100, 0));
        animalService.spawnAt(worldId, AnimalType.BOAR, new Point(100, 0));

        liveWorldService.tickAnimals(worldId);

        Map<String, Object> hurt = alice.expectIgnoringAnimals("hurt");
        assertThat(hurt.get("by")).isEqualTo("BOAR");
        assertThat(number(map(alice.expectIgnoringAnimals("state"), "state"), "health")).isCloseTo(90, within(0.5));
    }

    @Test
    void boarsInAPeacefulWorldLeaveYouAlone() throws Exception {
        long peaceful = worldService.createWorld(new CreateWorldRequest("Peaceful", false, "DROP")).id();
        TestClient alice = joinAt(peaceful, aliceId, point(100, 0));
        animalService.spawnAt(peaceful, AnimalType.BOAR, new Point(100, 0));

        liveWorldService.tickAnimals(peaceful);

        alice.expectNoneIgnoringAnimals("hurt");
    }

    @Test
    void aBoarCanKillYou() throws Exception {
        TestClient alice = joinAt(worldId, aliceId, point(100, 0));
        var vitals = playerVitalsRepository.findByWorldIdAndUserId(worldId, aliceId).orElseThrow();
        vitals.setHealth(5);
        playerVitalsRepository.save(vitals);
        animalService.spawnAt(worldId, AnimalType.BOAR, new Point(100, 0));

        liveWorldService.tickAnimals(worldId);

        alice.expectIgnoringAnimals("playerRespawned");
        assertThat(alice.expectIgnoringAnimals("youDied").get("cause")).isEqualTo("A boar got you");
    }

    @Test
    void huntingARabbitGivesMeatAndEveryoneSeesItGo() throws Exception {
        inventoryService.add(worldId, aliceId, ItemType.SPEAR, 1); // slot 0, held
        TestClient bob = connectAndJoin(bobId);
        TestClient alice = joinAt(worldId, aliceId, point(100, 0));
        bob.expectIgnoringAnimals("playerJoined");
        long rabbit = animalService.spawnAt(worldId, AnimalType.RABBIT, new Point(200, 0)).id();

        alice.send(Map.of("type", "attack", "animalId", rabbit));

        Map<String, Object> killed = bob.expectIgnoringAnimalMoves("animalKilled");
        assertThat(number(killed, "animalId")).isEqualTo(rabbit);
        assertThat(number(killed, "byUserId")).isEqualTo(aliceId);
        alice.expectIgnoringAnimalMoves("animalKilled");
        assertThat(number(slotWith("MEAT", alice.expectIgnoringAnimals("inventory")), "quantity")).isEqualTo(1);
        assertThat(animalService.animal(worldId, rabbit)).isNull();
    }

    @Test
    void aBoarTakesThreeHitsAndGivesMoreMeat() throws Exception {
        inventoryService.add(worldId, aliceId, ItemType.SPEAR, 1);
        TestClient alice = joinAt(worldId, aliceId, point(100, 0));
        long boar = animalService.spawnAt(worldId, AnimalType.BOAR, new Point(200, 0)).id();

        alice.send(Map.of("type", "attack", "animalId", boar));
        assertThat(number(alice.expectIgnoringAnimalMoves("animalHurt"), "health")).isEqualTo(2);
        alice.send(Map.of("type", "attack", "animalId", boar));
        assertThat(alice.expectIgnoringAnimals("error").get("message")).isEqualTo("Still getting ready");

        for (int hit = 0; hit < 2; hit++) {
            Thread.sleep(1050); // the spear's cooldown
            alice.send(Map.of("type", "attack", "animalId", boar));
        }

        alice.expectIgnoringAnimalMoves("animalHurt");
        alice.expectIgnoringAnimalMoves("animalKilled");
        assertThat(number(slotWith("MEAT", alice.expectIgnoringAnimals("inventory")), "quantity")).isEqualTo(3);
    }

    @Test
    void huntingNeedsAHeldSpearAndToBeCloseEnough() throws Exception {
        inventoryService.add(worldId, aliceId, ItemType.WOOD, 5); // slot 0, held - not a spear
        inventoryService.add(worldId, aliceId, ItemType.SPEAR, 1); // slot 1
        TestClient alice = joinAt(worldId, aliceId, point(100, 0));
        long near = animalService.spawnAt(worldId, AnimalType.RABBIT, new Point(200, 0)).id();
        long far = animalService.spawnAt(worldId, AnimalType.RABBIT, new Point(-400, 0)).id();

        alice.send(Map.of("type", "attack", "animalId", near));
        assertThat(alice.expectIgnoringAnimals("error").get("message")).isEqualTo("Hold a spear to hunt");

        alice.send(Map.of("type", "select", "slot", 1));
        alice.expectIgnoringAnimals("inventory");
        alice.send(Map.of("type", "attack", "animalId", far));
        assertThat(alice.expectIgnoringAnimals("error").get("message")).isEqualTo("Too far away - get closer");
    }

    @Test
    void aKillThatWouldNotFitInYourBagIsRefused() throws Exception {
        inventoryService.add(worldId, aliceId, ItemType.SPEAR, 1);
        inventoryService.add(worldId, aliceId, ItemType.STONE, 50 * 49); // every other slot full
        TestClient alice = joinAt(worldId, aliceId, point(100, 0));
        long rabbit = animalService.spawnAt(worldId, AnimalType.RABBIT, new Point(200, 0)).id();

        alice.send(Map.of("type", "attack", "animalId", rabbit));

        assertThat(alice.expectIgnoringAnimals("error").get("message")).isEqualTo("Make room in your bag first");
        assertThat(animalService.animal(worldId, rabbit)).isNotNull();
    }

    @Test
    void meatIsEatenLikeBerries() throws Exception {
        inventoryService.add(worldId, aliceId, ItemType.MEAT, 2);
        TestClient alice = connectAndJoin(aliceId);
        var vitals = playerVitalsRepository.findByWorldIdAndUserId(worldId, aliceId).orElseThrow();
        vitals.setHunger(40);
        playerVitalsRepository.save(vitals);

        alice.send(Map.of("type", "use"));

        assertThat(number(map(alice.expectIgnoringAnimals("state"), "state"), "hunger")).isCloseTo(75, within(0.5));
    }

    @Test
    void onlyOneTabCanPlayAsAUserAtATime() throws Exception {
        TestClient alice = connectAs(worldId, aliceId, "tab-1");
        alice.expect("snapshot");

        TestClient someoneElse = connectAs(worldId, aliceId, "tab-2");

        assertThat((String) someoneElse.expect("error").get("message")).endsWith("is already playing");
        awaitClosed(someoneElse);
        assertThat(alice.session.isOpen()).isTrue();
    }

    @Test
    void theSameTabReconnectingTakesOver() throws Exception {
        TestClient before = connectAs(worldId, aliceId, "tab-1");
        before.expect("snapshot");

        TestClient after = connectAs(worldId, aliceId, "tab-1");

        after.expect("snapshot");
        awaitClosed(before);
    }

    @Test
    void aUserIsFreeAgainOnceTheyLeave() throws Exception {
        TestClient alice = connectAs(worldId, aliceId, "tab-1");
        alice.expect("snapshot");
        alice.session.close();
        awaitNotPlaying(aliceId);

        TestClient someoneElse = connectAs(worldId, aliceId, "tab-2");

        someoneElse.expect("snapshot");
    }

    @Test
    void theUserListShowsWhoIsPlaying() throws Exception {
        TestClient alice = connect(aliceId);
        alice.expect("snapshot");

        assertThat(userService.listUsers())
                .filteredOn(u -> u.id() == aliceId).singleElement()
                .satisfies(u -> assertThat(u.playing()).isTrue());
        assertThat(userService.listUsers())
                .filteredOn(u -> u.id() == bobId).singleElement()
                .satisfies(u -> assertThat(u.playing()).isFalse());
    }

    @Test
    void unknownWorldIsRejected() throws Exception {
        TestClient alice = connectTo(worldId + 999_999, aliceId);

        assertThat(alice.expect("error").get("message")).isEqualTo("World not found");
    }

    /// Gives [userId] a hammer in their (still empty) first slot, which is
    /// the one they hold - building needs it.
    private void holdHammer(long userId) {
        inventoryService.add(worldId, userId, ItemType.HAMMER, 1);
    }

    private static Map<String, Object> slotWith(String item, Map<String, Object> inventoryEvent) {
        return list(map(inventoryEvent, "inventory"), "slots").stream()
                .filter(slot -> item.equals(slot.get("item")))
                .findFirst()
                .orElseThrow();
    }

    private static List<Map<String, Object>> sticksIn(Map<String, Object> snapshot) {
        return list(snapshot, "nodes").stream().filter(n -> "STICKS".equals(n.get("type"))).toList();
    }

    /// Connects and joins [userId] in [worldId], standing at [position].
    private TestClient joinAt(long worldId, long userId, Map<String, Object> position) throws Exception {
        TestClient client = connectTo(worldId, userId);
        client.expect("snapshot");
        client.send(Map.of("type", "join", "position", position));
        client.expect("state");
        return client;
    }

    /// One more vitals check and they've starved.
    private void aboutToStarve(long worldId, long userId) {
        var vitals = playerVitalsRepository.findByWorldIdAndUserId(worldId, userId).orElseThrow();
        vitals.setHunger(0);
        vitals.setHealth(0.5);
        vitals.setHungerSettledAt(java.time.Instant.now().minusSeconds(10));
        playerVitalsRepository.save(vitals);
    }

    /// Takes up all ten quick-access slots, so anything added next goes in
    /// the bag.
    private void fillQuickAccessBarWithAxes(long worldId, long userId) {
        inventoryService.add(worldId, userId, ItemType.AXE, 10);
    }

    private void awaitClosed(TestClient client) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (client.session.isOpen()) {
            assertThat(System.currentTimeMillis()).as("connection closed").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private void awaitNotPlaying(long userId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (registry.playingUserIds().contains(userId)) {
            assertThat(System.currentTimeMillis()).as("user released").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private void awaitNobodyIn(long worldId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!registry.joinedUserIds(worldId).isEmpty()) {
            assertThat(System.currentTimeMillis()).as("everyone left").isLessThan(deadline);
            Thread.sleep(10);
        }
    }

    private BoatDto boatForAlice() {
        holdHammer(aliceId);
        inventoryService.add(worldId, aliceId, ItemType.WOOD, 10);
        BuildingDto port = worldService.createBuilding(
                worldId, new BuildingRequest(BuildingType.PORT, number(ON_SAND, "x"), 0, aliceId));
        return worldService.createBoat(worldId, new CreateBoatRequest(port.id(), aliceId));
    }

    private TestClient connectAndJoin(long userId) throws Exception {
        TestClient client = connect(userId);
        client.expect("snapshot");
        client.send(Map.of("type", "join", "position", point(0, 0)));
        // Join has no reply, so wait until the server has processed it.
        long deadline = System.currentTimeMillis() + 5_000;
        while (!registry.joinedUserIds(worldId).contains(userId)) {
            assertThat(System.currentTimeMillis()).as("join processed").isLessThan(deadline);
            Thread.sleep(10);
        }
        // Joining starts your hunger clock, and you're told your state.
        assertThat(number(map(client.expect("state"), "state"), "hungerDrainPerSecond")).isPositive();
        return client;
    }

    private TestClient connect(long userId) throws Exception {
        return connectTo(worldId, userId);
    }

    private TestClient connectTo(long worldId, long userId) throws Exception {
        return connectAs(worldId, userId, UUID.randomUUID().toString());
    }

    /// Connects as the browser tab [clientId].
    private TestClient connectAs(long worldId, long userId, String clientId) throws Exception {
        TestClient client = new TestClient();
        client.session = new StandardWebSocketClient(webSocketContainer())
                .execute(client, "ws://localhost:{port}/ws/worlds/{worldId}?userId={userId}&clientId={clientId}",
                        port, worldId, userId, clientId)
                .get(5, TimeUnit.SECONDS);
        clients.add(client);
        return client;
    }

    /// The Java WebSocket client only takes messages up to 8 KB by default,
    /// and a snapshot is bigger than that. (Browsers have no such limit.)
    private static WebSocketContainer webSocketContainer() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        container.setDefaultMaxTextMessageBufferSize(1024 * 1024);
        return container;
    }

    private static Map<String, Object> point(double x, double y) {
        return Map.of("x", x, "y", y);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> source, String key) {
        return (List<Map<String, Object>>) source.get(key);
    }

    private static double number(Map<String, Object> source, String key) {
        return ((Number) source.get(key)).doubleValue();
    }

    /// A WebSocket client that queues up everything the server sends it.
    private class TestClient extends TextWebSocketHandler {

        private final BlockingQueue<Map<String, Object>> received = new LinkedBlockingQueue<>();
        private WebSocketSession session;

        @Override
        @SuppressWarnings("unchecked")
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            received.add(jsonMapper.readValue(message.getPayload(), Map.class));
        }

        void send(Map<String, Object> message) throws Exception {
            session.sendMessage(new TextMessage(jsonMapper.writeValueAsString(message)));
        }

        /// The next message, which must be of [type].
        Map<String, Object> expect(String type) throws InterruptedException {
            Map<String, Object> message = received.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("expected a %s message", type).isNotNull();
            assertThat(message.get("type")).as("message: %s", message).isEqualTo(type);
            return message;
        }

        /// The next message of [type], skipping animal events - animals move
        /// on their own and can turn up at any point.
        Map<String, Object> expectIgnoringAnimals(String type) throws InterruptedException {
            while (true) {
                Map<String, Object> message = expectAny();
                String actual = (String) message.get("type");
                if (actual.startsWith("animal")) {
                    continue;
                }
                assertThat(actual).as("message: %s", message).isEqualTo(type);
                return message;
            }
        }

        /// The next message of [type], skipping animals walking about (but
        /// not animals getting hurt or killed).
        Map<String, Object> expectIgnoringAnimalMoves(String type) throws InterruptedException {
            while (true) {
                Map<String, Object> message = expectAny();
                String actual = (String) message.get("type");
                if (actual.equals("animalMoved") || actual.equals("animalAdded")) {
                    continue;
                }
                assertThat(actual).as("message: %s", message).isEqualTo(type);
                return message;
            }
        }

        /// No message of [type] for a moment (animal events don't count).
        void expectNoneIgnoringAnimals(String type) throws InterruptedException {
            long until = System.currentTimeMillis() + 300;
            while (System.currentTimeMillis() < until) {
                Map<String, Object> message = received.poll(50, TimeUnit.MILLISECONDS);
                if (message != null) {
                    assertThat(message.get("type")).as("message: %s", message).isNotEqualTo(type);
                }
            }
        }

        /// The next message, whatever it is.
        Map<String, Object> expectAny() throws InterruptedException {
            Map<String, Object> message = received.poll(5, TimeUnit.SECONDS);
            assertThat(message).as("expected a message").isNotNull();
            return message;
        }

        void expectNothing() throws InterruptedException {
            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }
}
