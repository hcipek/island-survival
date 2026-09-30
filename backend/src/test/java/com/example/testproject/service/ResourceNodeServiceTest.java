package com.example.testproject.service;

import com.example.testproject.model.ResourceNode;
import com.example.testproject.model.ResourceNodeType;
import com.example.testproject.model.WorldIsland;
import com.example.testproject.model.req.CreateUserRequest;
import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.repository.ResourceNodeRepository;
import com.example.testproject.repository.WorldIslandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest
@Transactional
class ResourceNodeServiceTest {

    @Autowired
    private WorldService worldService;

    @Autowired
    private ResourceNodeService resourceNodeService;

    @Autowired
    private ResourceNodeRepository resourceNodeRepository;

    @Autowired
    private WorldIslandRepository worldIslandRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private PlayerResourcesService playerResourcesService;

    private long worldId;
    private long aliceId;
    private long bobId;
    private List<WorldIsland> islands;

    @BeforeEach
    void setUp() {
        worldId = worldService.createWorld(new CreateWorldRequest("Nodes")).id();
        aliceId = userService.createUser(new CreateUserRequest("Alice")).id();
        bobId = userService.createUser(new CreateUserRequest("Bob")).id();
        islands = worldIslandRepository.findAllByWorldId(worldId);
    }

    @Test
    void everyIslandGetsTreesBushesAndSticks() {
        for (WorldIsland island : islands) {
            Map<ResourceNodeType, Long> counts = nodes().stream()
                    .filter(n -> n.getIslandId().equals(island.getId()))
                    .collect(Collectors.groupingBy(ResourceNode::getType, Collectors.counting()));
            assertThat(counts).as("island %s", island.getId())
                    .containsOnlyKeys(ResourceNodeType.TREE, ResourceNodeType.BERRY_BUSH, ResourceNodeType.STICKS);
        }
    }

    @Test
    void homeIslandIsKeptSparse() {
        WorldIsland home = islands.stream().filter(WorldIsland::isHome).findFirst().orElseThrow();
        Map<ResourceNodeType, Long> counts = nodes().stream()
                .filter(n -> n.getIslandId().equals(home.getId()))
                .collect(Collectors.groupingBy(ResourceNode::getType, Collectors.counting()));

        assertThat(counts).containsEntry(ResourceNodeType.TREE, 5L)
                .containsEntry(ResourceNodeType.BERRY_BUSH, 2L)
                .containsEntry(ResourceNodeType.STICKS, 2L);
    }

    @Test
    void nodesAreOnTheGrassAndNotOnTopOfEachOther() {
        List<ResourceNode> nodes = nodes();
        for (ResourceNode node : nodes) {
            WorldIsland island = islands.stream()
                    .filter(i -> i.getId().equals(node.getIslandId())).findFirst().orElseThrow();
            assertThat(island.isOnGrass(node.getX(), node.getY())).isTrue();
            for (ResourceNode other : nodes) {
                if (other != node) {
                    assertThat(Math.hypot(other.getX() - node.getX(), other.getY() - node.getY()))
                            .isGreaterThanOrEqualTo(70);
                }
            }
        }
    }

    @Test
    void nothingGrowsWherePlayersSpawn() {
        assertThat(nodes()).allSatisfy(node ->
                assertThat(Math.hypot(node.getX(), node.getY())).isGreaterThanOrEqualTo(100));
    }

    @Test
    void worldsFromBeforeNodesExistedGetThemWhenLoaded() {
        resourceNodeService.deleteAllIn(worldId);
        assertThat(nodes()).isEmpty();

        resourceNodeService.ensureNodes(worldId);

        assertThat(nodes()).isNotEmpty();
    }

    @Test
    void ensuringTwiceDoesNotAddMore() {
        int before = nodes().size();

        resourceNodeService.ensureNodes(worldId);

        assertThat(nodes()).hasSize(before);
    }

    @Test
    void pickingBerriesGivesFood() {
        ResourceNode bush = first(ResourceNodeType.BERRY_BUSH);

        gatherStandingOn(aliceId, bush);

        assertThat(playerResourcesService.stateOf(worldId, aliceId).food()).isEqualTo(3);
        assertThat(playerResourcesService.stateOf(worldId, aliceId).wood()).isEqualTo(20);
    }

    @Test
    void pickingUpSticksGivesWood() {
        gatherStandingOn(aliceId, first(ResourceNodeType.STICKS));

        assertThat(playerResourcesService.stateOf(worldId, aliceId).wood()).isCloseTo(22, within(0.01));
    }

    @Test
    void aPickedBushIsGoneForEveryoneUntilItRegrows() {
        ResourceNode bush = first(ResourceNodeType.BERRY_BUSH);
        gatherStandingOn(aliceId, bush);

        assertStatus(HttpStatus.CONFLICT, () -> gatherStandingOn(bobId, bush));

        // Three minutes later...
        ResourceNode picked = resourceNodeRepository.findById(bush.getId()).orElseThrow();
        picked.setDepletedUntil(Instant.now().minusSeconds(1));
        resourceNodeRepository.save(picked);

        gatherStandingOn(bobId, bush);
        assertThat(playerResourcesService.stateOf(worldId, bobId).food()).isEqualTo(3);
    }

    @Test
    void youHaveToBeStandingNextToIt() {
        ResourceNode bush = first(ResourceNodeType.BERRY_BUSH);

        assertStatus(HttpStatus.CONFLICT,
                () -> resourceNodeService.gather(worldId, aliceId, bush.getId(), bush.getX() + 200, bush.getY()));
    }

    @Test
    void treesNeedAnAxe() {
        assertStatus(HttpStatus.CONFLICT, () -> gatherStandingOn(aliceId, first(ResourceNodeType.TREE)));
    }

    @Test
    void gatheringHasAShortCooldown() {
        List<ResourceNode> bushes = nodes().stream()
                .filter(n -> n.getType() == ResourceNodeType.BERRY_BUSH).toList();
        gatherStandingOn(aliceId, bushes.get(0));

        assertStatus(HttpStatus.CONFLICT, () -> gatherStandingOn(aliceId, bushes.get(1)));
    }

    @Test
    void nodesInOtherWorldsCannotBeGathered() {
        long otherWorld = worldService.createWorld(new CreateWorldRequest("Other")).id();
        ResourceNode bush = first(ResourceNodeType.BERRY_BUSH);

        assertStatus(HttpStatus.NOT_FOUND,
                () -> resourceNodeService.gather(otherWorld, aliceId, bush.getId(), bush.getX(), bush.getY()));
    }

    private void gatherStandingOn(long userId, ResourceNode node) {
        resourceNodeService.gather(worldId, userId, node.getId(), node.getX(), node.getY());
    }

    private ResourceNode first(ResourceNodeType type) {
        return nodes().stream().filter(n -> n.getType() == type).findFirst().orElseThrow();
    }

    private static void assertStatus(HttpStatus expected, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }

    private List<ResourceNode> nodes() {
        return resourceNodeRepository.findAllByWorldId(worldId);
    }
}
