package com.example.testproject.service;

import com.example.testproject.live.Point;
import com.example.testproject.model.AnimalType;
import com.example.testproject.model.WorldIsland;
import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.repository.WorldIslandRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AnimalServiceTest {

    private static final long ALICE = 1;

    @Autowired
    private AnimalService animalService;

    @Autowired
    private WorldService worldService;

    @Autowired
    private WorldIslandRepository worldIslandRepository;

    private long worldId;
    private WorldIsland outer;

    @BeforeEach
    void setUp() {
        worldId = worldService.createWorld(new CreateWorldRequest("Wild")).id();
        outer = worldIslandRepository.findAllByWorldId(worldId).stream()
                .filter(i -> !i.isHome()).findFirst().orElseThrow();
    }

    @Test
    void islandsGetRabbitsAndOuterIslandsABoar() {
        animalService.ensureAnimals(worldId);

        for (WorldIsland island : worldIslandRepository.findAllByWorldId(worldId)) {
            Map<AnimalType, Long> counts = animalService.animalsIn(worldId).stream()
                    .filter(a -> island.isOnLand(a.positionAt(Instant.now()).x(), a.positionAt(Instant.now()).y()))
                    .collect(Collectors.groupingBy(AnimalService.Animal::type, Collectors.counting()));
            if (island.isHome()) {
                assertThat(counts).containsExactlyEntriesOf(Map.of(AnimalType.RABBIT, 1L)); // safe where you start
            } else {
                assertThat(counts).containsEntry(AnimalType.RABBIT, 2L).containsEntry(AnimalType.BOAR, 1L);
            }
        }
    }

    @Test
    void fillingTwiceDoesNotAddMore() {
        animalService.ensureAnimals(worldId);
        int before = animalService.animalsIn(worldId).size();

        assertThat(animalService.ensureAnimals(worldId)).isEmpty();
        assertThat(animalService.animalsIn(worldId)).hasSize(before);
    }

    @Test
    void rabbitsRunFromPlayersOnFoot() {
        Point start = new Point(200, 0);
        AnimalService.Animal rabbit = animalService.spawnAt(worldId, AnimalType.RABBIT, start);
        Point player = new Point(100, 0);

        AnimalService.Changes changes = think(List.of(onFoot(player)), true, Instant.now());

        assertThat(changes.moved()).contains(rabbit);
        assertThat(distance(rabbit.restingAt(), player)).isGreaterThan(distance(start, player));
    }

    @Test
    void rabbitsIgnorePlayersInBoats() {
        AnimalService.Animal rabbit = animalService.spawnAt(worldId, AnimalType.RABBIT, new Point(500, 0));

        // Far enough from anything else, and not a long rest yet: nothing to run from.
        AnimalService.Changes changes = think(List.of(aboard(new Point(560, 0))), true, Instant.now().minusSeconds(60));

        assertThat(changes.moved()).doesNotContain(rabbit);
    }

    @Test
    void boarsChaseAndBitePlayersNearby() {
        Point center = new Point(outer.getCenterX(), outer.getCenterY());
        AnimalService.Animal boar = animalService.spawnAt(worldId, AnimalType.BOAR, center);
        Instant now = Instant.now();

        // A player close by: the boar heads for them.
        Point near = new Point(center.x() + 150, center.y());
        assertThat(think(List.of(onFoot(near)), true, now).moved()).contains(boar);
        assertThat(distance(boar.restingAt(), near)).isLessThan(60);

        // Right next to it: a bite.
        Point touching = boar.positionAt(now);
        AnimalService.Changes bitten = think(List.of(onFoot(touching)), true, now);
        assertThat(bitten.bites()).singleElement()
                .satisfies(bite -> assertThat(bite.userId()).isEqualTo(ALICE));

        // Not again straight away...
        assertThat(think(List.of(onFoot(touching)), true, now.plusMillis(500)).bites()).isEmpty();
        // ...but again a little later.
        assertThat(think(List.of(onFoot(touching)), true, now.plusMillis(1600)).bites()).hasSize(1);
    }

    @Test
    void inAPeacefulWorldBoarsNeverAttack() {
        Point center = new Point(outer.getCenterX(), outer.getCenterY());
        animalService.spawnAt(worldId, AnimalType.BOAR, center);

        assertThat(think(List.of(onFoot(center)), false, Instant.now()).bites()).isEmpty();
    }

    @Test
    void boarsLeavePlayersInBoatsAlone() {
        Point center = new Point(outer.getCenterX(), outer.getCenterY());
        animalService.spawnAt(worldId, AnimalType.BOAR, center);

        assertThat(think(List.of(aboard(center)), true, Instant.now()).bites()).isEmpty();
    }

    @Test
    void animalsStayOnTheirIsland() {
        animalService.ensureAnimals(worldId);
        Instant now = Instant.now();

        for (int step = 0; step < 200; step++) {
            now = now.plusSeconds(5);
            think(List.of(), true, now);
        }

        List<WorldIsland> islands = worldIslandRepository.findAllByWorldId(worldId);
        for (AnimalService.Animal animal : animalService.animalsIn(worldId)) {
            Point at = animal.restingAt();
            assertThat(islands).anySatisfy(island -> assertThat(island.isOnGrass(at.x(), at.y())).isTrue());
        }
    }

    @Test
    void hurtAnimalsAreGoneAtZero() {
        AnimalService.Animal boar = animalService.spawnAt(
                worldId, AnimalType.BOAR, new Point(outer.getCenterX(), outer.getCenterY()));

        assertThat(animalService.hurt(worldId, boar.id(), 1)).isEqualTo(2);
        assertThat(animalService.hurt(worldId, boar.id(), 2)).isZero();
        assertThat(animalService.animal(worldId, boar.id())).isNull();
    }

    @Test
    void newAnimalsAppearUpToTheLimits() {
        for (int i = 0; i < 20; i++) {
            animalService.respawn(worldId, 1.0);
        }
        int full = animalService.animalsIn(worldId).size();

        animalService.respawn(worldId, 1.0);

        assertThat(animalService.animalsIn(worldId)).hasSize(full);
        assertThat(full).isEqualTo(1 + 3 * 3); // home rabbit, plus 2 rabbits and a boar on each outer island
    }

    private AnimalService.Changes think(List<AnimalService.Target> players, boolean aggressive, Instant now) {
        return animalService.think(worldId, players, aggressive, now);
    }

    private static AnimalService.Target onFoot(Point at) {
        return new AnimalService.Target(ALICE, at, true);
    }

    private static AnimalService.Target aboard(Point at) {
        return new AnimalService.Target(ALICE, at, false);
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }
}
