package com.example.testproject.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/// Turns on the world's regular background ticks (see `StickSpawner`).
/// Tests switch it off with `game.ticks.enabled: false`, so random events
/// can't interfere with them.
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "game.ticks.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
