package com.example.testproject.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/// Brings older databases in line with the code: removes columns it no
/// longer has, and loosens columns that would reject newer values. Hibernate's `update`
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

        // Type columns used to be created as database enums, fixed to the
        // values that existed back then - so newer ones (like stones) were
        // refused. Plain text accepts whatever the game adds later.
        jdbcTemplate.execute("ALTER TABLE building ALTER COLUMN type SET DATA TYPE VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE resource_node ALTER COLUMN type SET DATA TYPE VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE inventory_slot ALTER COLUMN item SET DATA TYPE VARCHAR(32)");

        // Worlds someone has already played in (they have a stored player
        // position) count as started, so their settings stay fixed.
        jdbcTemplate.update("""
                UPDATE world SET started_at = created_at
                WHERE started_at IS NULL
                  AND EXISTS (SELECT 1 FROM player_presence p WHERE p.world_id = world.id)
                """);

        // Sticks used to be their own item; they're wood now. (Each stack
        // stays in its slot, just as wood.)
        jdbcTemplate.update("UPDATE inventory_slot SET item = 'WOOD' WHERE item = 'STICK'");
    }
}
