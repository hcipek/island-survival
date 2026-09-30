package com.example.testproject.config;

import com.example.testproject.model.req.CreateWorldRequest;
import com.example.testproject.service.WorldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LegacySchemaCleanupTest {

    @Autowired
    private LegacySchemaCleanup cleanup;

    @Autowired
    private WorldService worldService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void dropsTheOldSharedWoodColumnSoWorldsCanBeCreatedAgain() throws Exception {
        // Recreate what an older database looks like: the shared wood pool
        // as a NOT NULL column the code no longer fills in.
        jdbcTemplate.execute("ALTER TABLE world ADD COLUMN wood DOUBLE PRECISION DEFAULT 0 NOT NULL");
        jdbcTemplate.execute("ALTER TABLE world ALTER COLUMN wood DROP DEFAULT");
        jdbcTemplate.execute("ALTER TABLE world ADD COLUMN last_collected_at TIMESTAMP");
        assertThatThrownBy(() -> worldService.createWorld(new CreateWorldRequest("Before cleanup")))
                .isInstanceOf(DataIntegrityViolationException.class);

        cleanup.run(null);

        assertThat(worldService.createWorld(new CreateWorldRequest("After cleanup")).id()).isPositive();
        assertThat(columnExists("WOOD")).isFalse();
        assertThat(columnExists("LAST_COLLECTED_AT")).isFalse();
    }

    @Test
    void doesNothingOnAnUpToDateDatabase() throws Exception {
        cleanup.run(null);
        cleanup.run(null);

        assertThat(columnExists("WOOD")).isFalse();
    }

    private boolean columnExists(String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'WORLD' AND COLUMN_NAME = ?",
                Integer.class, column);
        return count != null && count > 0;
    }
}
