package com.example.testproject.service;

import com.example.testproject.model.ResourceNode;
import com.example.testproject.model.ResourceNodeType;
import com.example.testproject.model.WorldIsland;
import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.repository.ResourceNodeRepository;
import com.example.testproject.repository.WorldIslandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

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

    private long worldId;
    private List<WorldIsland> islands;

    @BeforeEach
    void setUp() {
        worldId = worldService.createWorld(new CreateWorldRequest("Nodes")).id();
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

    private List<ResourceNode> nodes() {
        return resourceNodeRepository.findAllByWorldId(worldId);
    }
}
