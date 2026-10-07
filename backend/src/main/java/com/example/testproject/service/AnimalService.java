package com.example.testproject.service;

import com.example.testproject.live.LiveEvents;
import com.example.testproject.live.Movement;
import com.example.testproject.live.Point;
import com.example.testproject.model.AnimalType;
import com.example.testproject.model.WorldIsland;
import com.example.testproject.repository.WorldIslandRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/// The animals on each island and how they behave. The server decides what
/// every animal does; clients are told where each one is walking and
/// simulate it, just like other players' movement.
///
/// Animals live in memory only: they move every fraction of a second, and
/// after a server restart they're simply placed again when someone enters.
@Service
public class AnimalService {

    /// The most of each kind on an island. The home island has a rabbit and
    /// no boars, so where everyone starts stays safe.
    private static final int HOME_RABBITS = 1;
    private static final int HOME_BOARS = 0;
    private static final int OUTER_RABBITS = 2;
    private static final int OUTER_BOARS = 1;

    /// Rabbits run when a player comes this close, this far.
    private static final double FLEE_RADIUS = 180;
    private static final double FLEE_DISTANCE = 220;

    /// Boars go after a player this close, and give up past the second.
    private static final double CHASE_RADIUS = 220;
    private static final double GIVE_UP_RADIUS = 350;
    private static final double BITE_RANGE = 45;
    private static final double BITE_DAMAGE = 10;
    private static final Duration BITE_COOLDOWN = Duration.ofMillis(1500);

    /// Wandering: a short walk every few seconds.
    private static final double WANDER_DISTANCE = 150;
    private static final long MIN_REST_MS = 3_000;
    private static final long MAX_REST_MS = 8_000;

    /// Animals stay on the grass, away from the coast.
    private static final double RANGE_AS_GRASS_FRACTION = 0.85;

    /// Each island below its limit gets a new animal with this chance on
    /// each slow tick (every 20 s).
    public static final double RESPAWN_CHANCE = 0.25;

    private final WorldIslandRepository worldIslandRepository;
    private final Random random;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, Map<Long, Animal>> animalsByWorld = new ConcurrentHashMap<>();

    @Autowired
    public AnimalService(WorldIslandRepository worldIslandRepository) {
        this(worldIslandRepository, new Random());
    }

    /// With a seeded [random], for tests.
    AnimalService(WorldIslandRepository worldIslandRepository, Random random) {
        this.worldIslandRepository = worldIslandRepository;
        this.random = random;
    }

    /// One animal. Mutable, and only ever touched while holding this
    /// service's lock.
    public static final class Animal {
        private final long id;
        private final long worldId;
        private final WorldIsland island;
        private final AnimalType type;
        private int health;
        private Point restingAt;
        private Movement movement;
        private Instant nextWanderAt;
        private Instant lastBiteAt;
        private Long chasing;

        Animal(long id, long worldId, WorldIsland island, AnimalType type, Point at, Instant now) {
            this.id = id;
            this.worldId = worldId;
            this.island = island;
            this.type = type;
            this.health = type.maxHealth();
            this.restingAt = at;
            this.nextWanderAt = now;
        }

        public long id() {
            return id;
        }

        public AnimalType type() {
            return type;
        }

        public int health() {
            return health;
        }

        public Movement movement() {
            return movement;
        }

        /// Where it ends up: the end of its walk, or where it's standing.
        public Point restingAt() {
            return restingAt;
        }

        public Point positionAt(Instant now) {
            return movement == null ? restingAt : movement.positionAt(now, type.speed());
        }

        /// Where it's walking to (or standing).
        Point destination() {
            return movement == null ? restingAt : movement.destination();
        }

        boolean isMoving(Instant now) {
            return movement != null && !positionAt(now).equals(movement.destination());
        }
    }

    /// A player an animal might react to.
    public record Target(long userId, Point position, boolean onFoot) {
    }

    /// What happened during one [think]: animals that set off somewhere
    /// new, and players that got bitten.
    public record Changes(List<Animal> moved, List<Bite> bites) {
    }

    public record Bite(long userId, double damage, AnimalType by) {
    }

    /// Fills every island of [worldId] up to its limits. Returns the new
    /// animals.
    public synchronized List<Animal> ensureAnimals(long worldId) {
        List<Animal> added = new ArrayList<>();
        Instant now = Instant.now();
        for (WorldIsland island : worldIslandRepository.findAllByWorldId(worldId)) {
            for (AnimalType type : AnimalType.values()) {
                for (int i = countOn(worldId, island, type); i < limit(island, type); i++) {
                    added.add(spawn(worldId, island, type, randomSpotOn(island), now));
                }
            }
        }
        return added;
    }

    /// Each island below its limit for a kind has [chance] of a new one.
    /// Returns the new animals.
    public synchronized List<Animal> respawn(long worldId, double chance) {
        List<Animal> added = new ArrayList<>();
        Instant now = Instant.now();
        for (WorldIsland island : worldIslandRepository.findAllByWorldId(worldId)) {
            for (AnimalType type : AnimalType.values()) {
                if (countOn(worldId, island, type) < limit(island, type) && random.nextDouble() < chance) {
                    added.add(spawn(worldId, island, type, randomSpotOn(island), now));
                }
            }
        }
        return added;
    }

    public synchronized Collection<Animal> animalsIn(long worldId) {
        return List.copyOf(world(worldId).values());
    }

    public synchronized Animal animal(long worldId, long animalId) {
        return world(worldId).get(animalId);
    }

    /// Decides what every animal in [worldId] does next, given where the
    /// players are. [aggressive] is the world's animal aggression setting.
    public synchronized Changes think(long worldId, List<Target> players, boolean aggressive, Instant now) {
        List<Animal> moved = new ArrayList<>();
        List<Bite> bites = new ArrayList<>();
        for (Animal animal : world(worldId).values()) {
            Point at = animal.positionAt(now);
            List<Target> nearby = players.stream()
                    .filter(Target::onFoot)
                    .filter(p -> animal.island.isOnLand(p.position().x(), p.position().y()))
                    .toList();

            boolean acted = switch (animal.type) {
                case RABBIT -> flee(animal, at, nearby, now, moved);
                case BOAR -> aggressive && chase(animal, at, nearby, now, moved, bites);
            };
            if (!acted) {
                wander(animal, at, now, moved);
            }
        }
        return new Changes(moved, bites);
    }

    /// Takes [amount] of health from an animal. Returns its health
    /// afterwards; at 0 it's gone.
    public synchronized int hurt(long worldId, long animalId, int amount) {
        Animal animal = world(worldId).get(animalId);
        if (animal == null) {
            return 0;
        }
        animal.health = Math.max(0, animal.health - amount);
        if (animal.health == 0) {
            world(worldId).remove(animalId);
        }
        return animal.health;
    }

    /// An animal as clients need to draw and move it.
    public static Map<String, Object> view(Animal animal) {
        Point at = animal.positionAt(Instant.now());
        return LiveEvents.animal(
                animal.id, animal.type, at.x(), at.y(), animal.health, animal.type.maxHealth(),
                animal.type.speed(), animal.isMoving(Instant.now()) ? animal.movement : null);
    }

    public void forgetWorld(long worldId) {
        animalsByWorld.remove(worldId);
    }

    /// Places an animal at a known spot (used by tests).
    public synchronized Animal spawnAt(long worldId, AnimalType type, Point at) {
        WorldIsland island = worldIslandRepository.findAllByWorldId(worldId).stream()
                .filter(i -> i.isOnLand(at.x(), at.y()))
                .findFirst()
                .orElseThrow();
        return spawn(worldId, island, type, at, Instant.now());
    }

    private boolean flee(Animal rabbit, Point at, List<Target> players, Instant now, List<Animal> moved) {
        Target closest = closest(players, at, FLEE_RADIUS);
        if (closest == null) {
            return false;
        }
        // Already running away from roughly here? Keep going.
        if (rabbit.isMoving(now) && distance(rabbit.destination(), closest.position()) > FLEE_RADIUS) {
            return true;
        }
        double dx = at.x() - closest.position().x();
        double dy = at.y() - closest.position().y();
        double length = Math.max(1e-6, Math.hypot(dx, dy));
        Point away = new Point(at.x() + dx / length * FLEE_DISTANCE, at.y() + dy / length * FLEE_DISTANCE);
        walk(rabbit, at, keepOnIsland(rabbit.island, away), now, moved);
        return true;
    }

    private boolean chase(
            Animal boar, Point at, List<Target> players, Instant now, List<Animal> moved, List<Bite> bites
    ) {
        Target target = boar.chasing == null ? null : players.stream()
                .filter(p -> p.userId() == boar.chasing && distance(p.position(), at) <= GIVE_UP_RADIUS)
                .findFirst()
                .orElse(null);
        if (target == null) {
            target = closest(players, at, CHASE_RADIUS);
        }
        if (target == null) {
            boar.chasing = null;
            return false;
        }
        boar.chasing = target.userId();

        if (distance(at, target.position()) <= BITE_RANGE) {
            if (boar.movement != null) {
                boar.restingAt = at;
                boar.movement = null;
                moved.add(boar);
            }
            if (boar.lastBiteAt == null || Duration.between(boar.lastBiteAt, now).compareTo(BITE_COOLDOWN) >= 0) {
                boar.lastBiteAt = now;
                bites.add(new Bite(target.userId(), BITE_DAMAGE, AnimalType.BOAR));
            }
            return true;
        }
        // Head for the player, re-aiming only when they've moved a bit.
        Point goal = keepOnIsland(boar.island, target.position());
        if (!boar.isMoving(now) || distance(boar.destination(), goal) > 30) {
            walk(boar, at, goal, now, moved);
        }
        return true;
    }

    private void wander(Animal animal, Point at, Instant now, List<Animal> moved) {
        if (animal.isMoving(now) || now.isBefore(animal.nextWanderAt)) {
            return;
        }
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance = WANDER_DISTANCE * (0.4 + 0.6 * random.nextDouble());
        Point goal = keepOnIsland(animal.island,
                new Point(at.x() + Math.cos(angle) * distance, at.y() + Math.sin(angle) * distance));
        walk(animal, at, goal, now, moved);
        long walkMs = (long) (distance(at, goal) / animal.type.speed() * 1000);
        animal.nextWanderAt = now.plusMillis(walkMs + MIN_REST_MS + (long) (random.nextDouble() * (MAX_REST_MS - MIN_REST_MS)));
    }

    private static void walk(Animal animal, Point from, Point to, Instant now, List<Animal> moved) {
        animal.movement = new Movement(from, List.of(to), now);
        animal.restingAt = to;
        moved.add(animal);
    }

    private Animal spawn(long worldId, WorldIsland island, AnimalType type, Point at, Instant now) {
        Animal animal = new Animal(nextId.getAndIncrement(), worldId, island, type, at, now);
        // Don't all set off at the same moment.
        animal.nextWanderAt = now.plusMillis((long) (random.nextDouble() * MAX_REST_MS));
        world(worldId).put(animal.id, animal);
        return animal;
    }

    private Map<Long, Animal> world(long worldId) {
        return animalsByWorld.computeIfAbsent(worldId, id -> new ConcurrentHashMap<>());
    }

    private int countOn(long worldId, WorldIsland island, AnimalType type) {
        return (int) world(worldId).values().stream()
                .filter(a -> a.type == type && a.island.getId().equals(island.getId()))
                .count();
    }

    private static int limit(WorldIsland island, AnimalType type) {
        return switch (type) {
            case RABBIT -> island.isHome() ? HOME_RABBITS : OUTER_RABBITS;
            case BOAR -> island.isHome() ? HOME_BOARS : OUTER_BOARS;
        };
    }

    private Point randomSpotOn(WorldIsland island) {
        double distance = Math.sqrt(random.nextDouble()) * island.grassRadius() * RANGE_AS_GRASS_FRACTION;
        double angle = random.nextDouble() * 2 * Math.PI;
        return new Point(island.getCenterX() + Math.cos(angle) * distance,
                island.getCenterY() + Math.sin(angle) * distance);
    }

    /// [point], pulled in onto the island's grass if it's beyond it.
    private static Point keepOnIsland(WorldIsland island, Point point) {
        double max = island.grassRadius() * RANGE_AS_GRASS_FRACTION;
        double dx = point.x() - island.getCenterX();
        double dy = point.y() - island.getCenterY();
        double length = Math.hypot(dx, dy);
        if (length <= max) {
            return point;
        }
        return new Point(island.getCenterX() + dx / length * max, island.getCenterY() + dy / length * max);
    }

    private static Target closest(List<Target> players, Point at, double within) {
        Target best = null;
        double bestDistance = within;
        for (Target player : players) {
            double d = distance(player.position(), at);
            if (d <= bestDistance) {
                best = player;
                bestDistance = d;
            }
        }
        return best;
    }

    private static double distance(Point a, Point b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }
}
