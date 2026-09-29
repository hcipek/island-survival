package com.example.testproject.live;

import com.example.testproject.model.BuildingType;
import com.example.testproject.model.dto.BoatDto;
import com.example.testproject.model.dto.BuildingDto;
import com.example.testproject.model.req.BuildingRequest;
import com.example.testproject.model.req.CreateBoatRequest;
import com.example.testproject.model.req.CreateUserRequest;
import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.repository.BoatRepository;
import com.example.testproject.service.UserService;
import com.example.testproject.service.WorldService;
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
        worldService.createBuilding(worldId, new BuildingRequest(BuildingType.PORT, 550, 0, aliceId));

        Map<String, Object> snapshot = connect(aliceId).expect("snapshot");

        assertThat(list(snapshot, "buildings")).hasSize(1);
        assertThat(list(snapshot, "boats")).isEmpty();
        assertThat(list(snapshot, "players")).isEmpty();
        assertThat(number(map(snapshot, "state"), "wood")).isEqualTo(20);
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
    void buildingsAndWoodChangesArePushed() throws Exception {
        TestClient bob = connect(bobId);
        bob.expect("snapshot");

        worldService.createBuilding(worldId, new BuildingRequest(BuildingType.LUMBERJACK, 100, 0, aliceId));

        assertThat(map(bob.expect("buildingAdded"), "building").get("type")).isEqualTo("LUMBERJACK");
        assertThat(number(map(bob.expect("state"), "state"), "woodPerSecond")).isEqualTo(0.5);
    }

    @Test
    void unknownWorldIsRejected() throws Exception {
        TestClient alice = connectTo(worldId + 999_999, aliceId);

        assertThat(alice.expect("error").get("message")).isEqualTo("World not found");
    }

    private BoatDto boatForAlice() {
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
        return client;
    }

    private TestClient connect(long userId) throws Exception {
        return connectTo(worldId, userId);
    }

    private TestClient connectTo(long worldId, long userId) throws Exception {
        TestClient client = new TestClient();
        client.session = new StandardWebSocketClient()
                .execute(client, "ws://localhost:{port}/ws/worlds/{worldId}?userId={userId}", port, worldId, userId)
                .get(5, TimeUnit.SECONDS);
        clients.add(client);
        return client;
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

        void expectNothing() throws InterruptedException {
            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }
    }
}
