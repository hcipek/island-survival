package com.example.testproject.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/// Removes database columns the code no longer has. Hibernate's `update`
/// mode adds new columns but never drops old ones - and the old shared
/// `world.wood` was created NOT NULL, so leaving it would make creating a
/// world fail.
///
/// This is a stand-in for a real migration tool (e.g. Flyway), fine while
/// the database is a local H2 file. Safe to run on every start.
@Component
public class LegacySchemaCleanup implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public LegacySchemaCleanup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Wood used to be one pool per world; it's per player now (see
        // PlayerVitals), and existing players start fresh.
        jdbcTemplate.execute("ALTER TABLE world DROP COLUMN IF EXISTS wood");
        jdbcTemplate.execute("ALTER TABLE world DROP COLUMN IF EXISTS last_collected_at");
        // Wood and food moved into inventories (see InventorySlot), and
        // everyone starts empty-handed.
        jdbcTemplate.execute("ALTER TABLE player_resources DROP COLUMN IF EXISTS wood");
        jdbcTemplate.execute("ALTER TABLE player_resources DROP COLUMN IF EXISTS food");
        jdbcTemplate.execute("ALTER TABLE player_resources DROP COLUMN IF EXISTS last_collected_at");
    }
}
