package io.openskeleton.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openskeleton.backend.web.NotFoundException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Integration test for the OSK-84 soft-delete + optimistic-concurrency behaviour the
 * reusable {@code BaseEntity} adds to the {@code users} table, run against a real
 * Testcontainers Postgres (the {@code jdbc:tc:...} datasource).
 *
 * <p>As with the other {@code @SpringBootTest}s here, the context booting at all is
 * itself a proof point: Hibernate runs in {@code validate} mode, so the {@code User}
 * mapping (now inheriting {@code created_at}/{@code updated_at}/{@code deleted_at}/
 * {@code version} from {@code BaseEntity}) must match the V2+V3 Flyway schema exactly
 * or startup would fail before any test ran.
 *
 * <p>On top of that it exercises the three acceptance criteria against the real DB:
 * default reads exclude soft-deleted rows and a restore path brings them back (AC-3),
 * and a concurrent stale-version update is rejected with an optimistic-locking failure
 * rather than silently overwriting (AC-2 — mapped to HTTP 409 by the global handler,
 * see {@code GlobalExceptionHandlerTest}).
 */
@SpringBootTest
class UserSoftDeleteIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationV3AddedSoftDeleteAndVersionColumns() {
        var jdbc = new JdbcTemplate(dataSource);

        // Flyway recorded V3 as applied (V1 baseline + V2 users already proven elsewhere).
        Boolean v3Success =
                jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '3'", Boolean.class);
        assertThat(v3Success).isTrue();

        // deleted_at is a nullable TIMESTAMPTZ (null = active).
        Integer deletedAtCol = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'users' AND column_name = 'deleted_at' AND is_nullable = 'YES'",
                Integer.class);
        assertThat(deletedAtCol).isEqualTo(1);

        // version is a NOT NULL bigint (the @Version counter).
        Integer versionCol = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'users' AND column_name = 'version' AND is_nullable = 'NO'",
                Integer.class);
        assertThat(versionCol).isEqualTo(1);
    }

    @Test
    void newUserStartsActiveWithVersionZero() {
        var saved = userRepository.saveAndFlush(new User("uid-sd-fresh", "fresh@sd.example.com", "Fresh"));

        // Not deleted, and the @Version counter starts at 0 for a freshly inserted row.
        assertThat(saved.isDeleted()).isFalse();
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.getVersion()).isZero();
    }

    @Test
    void softDeleteHidesFromDefaultReadsThenRestoreShowsAgain() {
        var seeded = userRepository.saveAndFlush(new User("uid-sd-cycle", "cycle@sd.example.com", "Cycle"));
        UUID id = seeded.getId();
        long versionWhenActive = seeded.getVersion();

        // Active up front: visible to the domain finder, the inherited findById, and findAll.
        assertThat(userRepository.findByFirebaseUid("uid-sd-cycle")).isPresent();
        assertThat(userRepository.findById(id)).isPresent();
        assertThat(userRepository.findAll()).anyMatch(u -> u.getId().equals(id));

        // --- soft delete ---
        var deleted = userService.softDelete(id);
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.getDeletedAt()).isNotNull();
        // The soft-delete is a real UPDATE, so the optimistic-lock version advanced.
        assertThat(deleted.getVersion()).isGreaterThan(versionWhenActive);

        // Now excluded from EVERY default read (the @SQLRestriction("deleted_at is null")
        // is appended to the derived query, the PK load, and findAll alike).
        assertThat(userRepository.findByFirebaseUid("uid-sd-cycle")).isEmpty();
        assertThat(userRepository.findById(id)).isEmpty();
        assertThat(userRepository.findAll()).noneMatch(u -> u.getId().equals(id));
        // ...but the restore-path escape hatch still sees it.
        assertThat(userRepository.findByIdIncludingDeleted(id)).isPresent();

        // --- restore ---
        var restored = userService.restore(id);
        assertThat(restored.isDeleted()).isFalse();
        assertThat(restored.getDeletedAt()).isNull();
        // Restore is also an UPDATE, so the version advanced again.
        assertThat(restored.getVersion()).isGreaterThan(deleted.getVersion());

        // Visible again through the default read surface.
        assertThat(userRepository.findByFirebaseUid("uid-sd-cycle")).isPresent();
        assertThat(userRepository.findById(id)).isPresent();
        assertThat(userRepository.findAll()).anyMatch(u -> u.getId().equals(id));
    }

    @Test
    void softDeleteOfUnknownOrAlreadyDeletedUserThrowsNotFound() {
        // Nothing with this id -> 404-mapped NotFoundException.
        assertThatThrownBy(() -> userService.softDelete(UUID.randomUUID())).isInstanceOf(NotFoundException.class);

        // A second soft-delete cannot see the (now hidden) row either, so it also 404s
        // rather than silently succeeding.
        var seeded = userRepository.saveAndFlush(new User("uid-sd-twice", "twice@sd.example.com", "Twice"));
        userService.softDelete(seeded.getId());
        assertThatThrownBy(() -> userService.softDelete(seeded.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void restoreOfUnknownUserThrowsNotFound() {
        assertThatThrownBy(() -> userService.restore(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void findActiveByFirebaseUidReflectsSoftDeleteState() {
        var seeded = userRepository.saveAndFlush(new User("uid-sd-active", "active@sd.example.com", "Active"));
        assertThat(userService.findActiveByFirebaseUid("uid-sd-active")).isPresent();

        userService.softDelete(seeded.getId());
        assertThat(userService.findActiveByFirebaseUid("uid-sd-active")).isEmpty();
    }

    @Test
    void concurrentStaleUpdateIsRejectedWithOptimisticLockFailure() {
        var seeded = userRepository.saveAndFlush(new User("uid-concurrent", "conc@sd.example.com", "Concurrent"));
        UUID id = seeded.getId();

        // Two independent copies loaded at the same version — two racing requests.
        User first = userRepository.findById(id).orElseThrow();
        User second = userRepository.findById(id).orElseThrow();
        assertThat(first.getVersion()).isEqualTo(second.getVersion());

        // First writer commits: succeeds and bumps the stored version.
        first.setDisplayName("first writer");
        User afterFirst = userRepository.saveAndFlush(first);
        assertThat(afterFirst.getVersion()).isGreaterThan(seeded.getVersion());

        // Second writer still holds the pre-update (now stale) version: the write must
        // be rejected — NOT silently overwrite the first writer's change. This is the
        // exception the global handler maps to HTTP 409.
        second.setDisplayName("second writer (stale)");
        assertThatThrownBy(() -> userRepository.saveAndFlush(second))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // Proof the first writer's value stands and was never clobbered.
        assertThat(userRepository.findById(id).orElseThrow().getDisplayName()).isEqualTo("first writer");
    }
}
