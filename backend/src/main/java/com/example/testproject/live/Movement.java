package com.example.testproject.live;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/// A walk or voyage in progress: start at [from], then visit each point of
/// [path] in order at the mover's fixed speed. Clients simulate this
/// themselves, so the server only needs to know when it started - someone
/// joining mid-way is told how long ago that was and fast-forwards.
public record Movement(Point from, List<Point> path, Instant startedAt) {

    /// World units per second. These must match the client's speeds
    /// (`PlayerComponent` and `BoatComponent`), or the server and clients
    /// would disagree about where someone is.
    public static final double PLAYER_SPEED = 300;
    public static final double BOAT_SPEED = 360;

    public Point destination() {
        return path.getLast();
    }

    public long elapsedMillis() {
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    /// Where the mover is at [now], moving at [speed] - the same walk along
    /// the path the clients do.
    public Point positionAt(Instant now, double speed) {
        double budget = speed * Math.max(0, Duration.between(startedAt, now).toMillis()) / 1000.0;
        Point at = from;
        for (Point next : path) {
            double distance = Math.hypot(next.x() - at.x(), next.y() - at.y());
            if (distance > budget) {
                double fraction = budget / distance;
                return new Point(at.x() + (next.x() - at.x()) * fraction, at.y() + (next.y() - at.y()) * fraction);
            }
            budget -= distance;
            at = next;
        }
        return at;
    }
}
