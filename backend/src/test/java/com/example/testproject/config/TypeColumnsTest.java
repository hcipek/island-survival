package com.example.testproject.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/// Type columns (building type, node type, item) must accept values added
/// to the game later. Hibernate would otherwise create them as H2 enum
/// columns fixed to the values that existed at the time - and its update
/// mode never changes an existing column, so e.g. adding stones broke every
/// world created before stones existed.
@SpringBootTest
@Transactional
class TypeColumnsTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void typeColumnsArePlainTextSoNewValuesFit() {
        assertThat(dataType("BUILDING", "TYPE")).isEqualTo("CHARACTER VARYING");
        assertThat(dataType("RESOURCE_NODE", "TYPE")).isEqualTo("CHARACTER VARYING");
        assertThat(dataType("INVENTORY_SLOT", "ITEM")).isEqualTo("CHARACTER VARYING");
    }

    @Test
    void aValueThatDoesNotExistYetIsAccepted() {
        jdbcTemplate.update("INSERT INTO resource_node (world_id, island_id, type, x, y) VALUES (1, 1, 'SOME_FUTURE_TYPE', 0, 0)");
        jdbcTemplate.update("INSERT INTO building (world_id, owner_id, type, x, y, stored_wood) VALUES (1, 1, 'SOME_FUTURE_TYPE', 0, 0, 0)");
        jdbcTemplate.update("INSERT INTO inventory_slot (world_id, user_id, slot_index, item, quantity) VALUES (1, 1, 0, 'SOME_FUTURE_ITEM', 1)");
    }

    @Test
    void cleanupTurnsOldEnumColumnsIntoText() throws Exception {
        // What an older database has: an enum of the building types back then.
        jdbcTemplate.execute("ALTER TABLE building ALTER COLUMN type SET DATA TYPE ENUM('PORT', 'LUMBERJACK')");
        assertThat(dataType("BUILDING", "TYPE")).isEqualTo("ENUM");

        new LegacySchemaCleanup(jdbcTemplate).run(null);

        assertThat(dataType("BUILDING", "TYPE")).isEqualTo("CHARACTER VARYING");
        jdbcTemplate.update("INSERT INTO building (world_id, owner_id, type, x, y, stored_wood) VALUES (1, 1, 'SOME_FUTURE_TYPE', 0, 0, 0)");
    }

    @Test
    void cleanupTurnsOldSticksIntoWood() throws Exception {
        jdbcTemplate.update(
                "INSERT INTO inventory_slot (world_id, user_id, slot_index, item, quantity) VALUES (1, 1, 7, 'STICK', 12)");

        new LegacySchemaCleanup(jdbcTemplate).run(null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT item FROM inventory_slot WHERE world_id = 1 AND user_id = 1 AND slot_index = 7", String.class))
                .isEqualTo("WOOD");
    }

    private String dataType(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = ? AND COLUMN_NAME = ?",
                String.class, table, column);
    }
}
