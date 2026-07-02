package io.openskeleton.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test proving the persistence layer is wired against a real Postgres
 * and that Flyway ran cleanly (OSK-29 sample integration test + OSK-32 migration
 * proof).
 *
 * <p>The datasource comes from {@code src/test/resources/application.properties},
 * whose {@code jdbc:tc:...} URL makes Testcontainers start a throwaway PostgreSQL
 * container for the test context — so no {@code @Testcontainers}/{@code @Container}
 * boilerplate is needed here. Because the container is real Postgres, Boot runs the
 * {@code db/migration} scripts on startup before this test executes.
 */
@SpringBootTest
class FlywayMigrationIntegrationTest {

    // Autowire the DataSource (rather than a pre-built JdbcTemplate) to assert the
    // container-backed datasource is the one the context actually wired up.
    @Autowired
    private DataSource dataSource;

    @Test
    void runsAgainstRealPostgresWithFlywayApplied() {
        var jdbc = new JdbcTemplate(dataSource);

        // (a) We are on a genuine PostgreSQL engine, not an in-memory H2 substitute.
        // version() only exists on Postgres and its banner starts with "PostgreSQL".
        String version = jdbc.queryForObject("SELECT version()", String.class);
        assertThat(version).contains("PostgreSQL");

        // (b) Flyway recorded migration V1 as a successful, applied migration. This
        // proves the migration engine ran and the baseline script executed cleanly.
        Boolean v1Success =
                jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);
        assertThat(v1Success).isTrue();

        // (c) The pgcrypto extension the baseline migration enables is present, so a
        // later migration can rely on gen_random_uuid()/crypto helpers.
        Integer pgcryptoCount =
                jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'pgcrypto'", Integer.class);
        assertThat(pgcryptoCount).isEqualTo(1);
    }
}
