package io.openskeleton.backend.audit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.PreUpdate;
import java.lang.reflect.Method;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test for the append-only audit log (OSK-80), run against a real
 * Testcontainers Postgres (the {@code jdbc:tc:...} datasource in
 * {@code src/test/resources/application.properties}).
 *
 * <p>Because it is a full {@code @SpringBootTest}, the mere fact the context boots
 * proves two things for free: the {@code V4__create_audit_events.sql} migration applied
 * on the container, and Hibernate — pinned to {@code validate} (see
 * {@code application.yml}) — asserted the {@link AuditEvent} mapping (including the
 * {@code JSONB metadata}) matches that Flyway-built schema. A mapping/schema mismatch
 * would fail context startup, so every test below would error before running.
 *
 * <p>On top of that, the tests exercise the acceptance criteria directly: that
 * {@code AuditService.record(...)} writes <b>exactly one</b> immutable event and that a
 * read returns it intact, and that the log has no update/delete path.
 */
@SpringBootTest
class AuditServiceIntegrationTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditRepository auditRepository;

    // The container-backed DataSource the context wired up; used to read Flyway's own
    // bookkeeping and the information_schema directly.
    @Autowired
    private DataSource dataSource;

    @Test
    void migrationV4AppliedAndContextBooted() {
        var jdbc = new JdbcTemplate(dataSource);

        // Flyway recorded V4 as a successful, applied migration — proof the audit_events
        // migration ran (the context booting already proved Hibernate validate passed,
        // including the JSONB mapping).
        Boolean v4Success =
                jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '4'", Boolean.class);
        assertThat(v4Success).isTrue();

        // The audit_events table physically exists in the migrated schema...
        Integer table = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'audit_events'",
                Integer.class);
        assertThat(table).isEqualTo(1);

        // ...and it is append-only at the schema level: there is deliberately NO
        // updated_at column (audit rows are immutable history, never updated).
        Integer updatedAtColumn = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'audit_events' AND column_name = 'updated_at'",
                Integer.class);
        assertThat(updatedAtColumn).isZero();
    }

    @Test
    void recordPersistsExactlyOneImmutableEventAndReadsItBack() {
        long before = auditRepository.count();

        // The metadata uses only benign, non-secret values (never tokens) and String
        // values so the JSONB round-trip is a clean equality check on read-back.
        var metadata = Map.<String, Object>of("changedField", "role", "from", "USER", "to", "ADMIN");
        var saved = auditService.record("actor-uid-1", AuditAction.ROLE_CHANGED, "USER", "user-42", metadata);

        // AC: exactly ONE event was appended by the single record(...) call.
        assertThat(auditRepository.count()).isEqualTo(before + 1);

        // The write assigned a UUID id (GenerationType.UUID) and stamped createdAt (@PrePersist).
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        // AC: a read returns the recorded event with every field intact, including the
        // JSONB metadata round-tripped through Postgres.
        var found = auditRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getActorFirebaseUid()).isEqualTo("actor-uid-1");
        assertThat(found.getAction()).isEqualTo(AuditAction.ROLE_CHANGED);
        assertThat(found.getTargetType()).isEqualTo("USER");
        assertThat(found.getTargetId()).isEqualTo("user-42");
        assertThat(found.getMetadata()).isEqualTo(metadata);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void recordWithoutMetadataStoresNullMetadata() {
        // The no-metadata convenience overload: action/target alone tell the story.
        var saved = auditService.record("actor-uid-2", AuditAction.USER_PROVISIONED, "USER", "user-99");

        var found = auditRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getAction()).isEqualTo(AuditAction.USER_PROVISIONED);
        assertThat(found.getActorFirebaseUid()).isEqualTo("actor-uid-2");
        assertThat(found.getTargetType()).isEqualTo("USER");
        assertThat(found.getTargetId()).isEqualTo("user-99");
        // No metadata was supplied, so the JSONB column is NULL and reads back as null.
        assertThat(found.getMetadata()).isNull();
    }

    @Test
    void recordAcceptsNullActorForSystemOriginatedEvents() {
        // A system-originated event has no human actor — the actor column is nullable.
        var saved = auditService.record(null, AuditAction.ACCOUNT_DISABLED, "USER", "user-7");

        var found = auditRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getActorFirebaseUid()).isNull();
        assertThat(found.getAction()).isEqualTo(AuditAction.ACCOUNT_DISABLED);
    }

    @Test
    void auditEventEntityExposesNoMutationPath() {
        // Reinforces the append-only design at the type level: the entity must expose no
        // setter and no @PreUpdate callback, so a persisted row cannot be mutated through
        // the mapping. This is the code-level counterpart to the "no updated_at" schema check.
        for (Method method : AuditEvent.class.getDeclaredMethods()) {
            assertThat(method.getName())
                    .as("AuditEvent must not expose setters (append-only)")
                    .doesNotStartWith("set");
            assertThat(method.isAnnotationPresent(PreUpdate.class))
                    .as("AuditEvent must not have a @PreUpdate callback (append-only)")
                    .isFalse();
        }
    }
}
