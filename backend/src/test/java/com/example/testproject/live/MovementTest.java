package com.example.testproject.live;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MovementTest {

    private final Instant start = Instant.parse("2026-10-07T10:00:00Z");

    @Test
    void moversFollowThePathAtTheirSpeed() {
        Movement walk = new Movement(new Point(0, 0), List.of(new Point(300, 0), new Point(300, 300)), start);

        Point after1s = walk.positionAt(start.plusSeconds(1), 300);
        Point after1point5s = walk.positionAt(start.plusMillis(1500), 300);

        assertThat(after1s.x()).isCloseTo(300, within(0.001));
        assertThat(after1s.y()).isCloseTo(0, within(0.001));
        assertThat(after1point5s.x()).isCloseTo(300, within(0.001));
        assertThat(after1point5s.y()).isCloseTo(150, within(0.001));
    }

    @Test
    void theyStopAtTheEnd() {
        Movement walk = new Movement(new Point(0, 0), List.of(new Point(100, 0)), start);

        assertThat(walk.positionAt(start.plusSeconds(60), 300)).isEqualTo(new Point(100, 0));
    }

    @Test
    void beforeItStartsTheyAreAtTheBeginning() {
        Movement walk = new Movement(new Point(5, 5), List.of(new Point(100, 0)), start);

        assertThat(walk.positionAt(start.minusSeconds(1), 300)).isEqualTo(new Point(5, 5));
    }
}
