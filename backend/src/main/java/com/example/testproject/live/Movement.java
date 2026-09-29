package com.example.testproject.live;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/// A walk or voyage in progress: start at [from], then visit each point of
/// [path] in order at the mover's fixed speed. Clients simulate this
/// themselves, so the server only needs to know when it started - someone
/// joining mid-way is told how long ago that was and fast-forwards.
public record Movement(Point from, List<Point> path, Instant startedAt) {

    public Point destination() {
        return path.getLast();
    }

    public long elapsedMillis() {
        return Duration.between(startedAt, Instant.now()).toMillis();
    }
}
