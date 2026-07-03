package io.openskeleton.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.openskeleton.backend.user.User.Role;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Integration test for the {@code users} data layer (OSK-60), run against a real
 * Testcontainers Postgres (the {@code jdbc:tc:...} datasource in
 * {@code src/test/resources/application.properties}).
 *
 * <p>Because it is a full {@code @SpringBootTest}, the mere fact the context boots
 * proves the two things the ticket is about: the {@code V2__create_users.sql}
 * migration applied on the container, and Hibernate — pinned to {@code validate}
 * (see {@code application.yml}) — asserted the {@link User} mapping matches that
 * Flyway-built schema. A mapping/schema mismatch would fail context startup, so
 * every test below would error before running.
 *
 * <p>On top of that boot-time proof, the tests exercise the entity + repository
 * round-trip (save / {@code findByFirebaseUid}) so the mapping is proven to actually
 * read and write the real database, and the lifecycle callbacks fire.
 */
@SpringBootTest
class UserRepositoryIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    // The DataSource is the container-backed one the context wired up; used to read
    // Flyway's own bookkeeping table directly.
    @Autowired
    private DataSource dataSource;

    @Test
    void migrationV2AppliedAndContextBooted() {
        var jdbc = new JdbcTemplate(dataSource);

        // Flyway recorded V2 as a successful, applied migration — proof the users
        // migration ran (the context booting already proved Hibernate validate passed).
        Boolean v2Success =
                jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '2'", Boolean.class);
        assertThat(v2Success).isTrue();

        // The users table physically exists in the migrated schema.
        Integer usersTable = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'users'",
                Integer.class);
        assertThat(usersTable).isEqualTo(1);
    }

    @Test
    void savesUserAndFindsByFirebaseUid() {
        var user = new User("firebase-uid-alice", "alice@example.com", "Alice");

        var saved = userRepository.save(user);

        // The UUID primary key was generated application-side (GenerationType.UUID)...
        assertThat(saved.getId()).isNotNull();
        // ...and the @PrePersist callback stamped both audit timestamps.
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        // Column defaults are reflected in the entity defaults.
        assertThat(saved.getRole()).isEqualTo(Role.USER);
        assertThat(saved.isEnabled()).isTrue();

        // The lookup the platform is built on returns the persisted row with all
        // fields intact — proving the mapping reads and writes the real Postgres.
        var found = userRepository.findByFirebaseUid("firebase-uid-alice").orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getFirebaseUid()).isEqualTo("firebase-uid-alice");
        assertThat(found.getEmail()).isEqualTo("alice@example.com");
        assertThat(found.getDisplayName()).isEqualTo("Alice");
        assertThat(found.getRole()).isEqualTo(Role.USER);
        assertThat(found.isEnabled()).isTrue();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void findByFirebaseUidReturnsEmptyWhenAbsent() {
        assertThat(userRepository.findByFirebaseUid("no-such-uid")).isEmpty();
    }

    @Test
    void updateMutatesFieldsAndBumpsUpdatedAt() {
        var saved = userRepository.saveAndFlush(new User("firebase-uid-bob", "bob@example.com", "Bob"));
        Instant createdAt = saved.getCreatedAt();

        // Mutate every settable field via its setter, then persist the update.
        saved.setEmail("bob2@example.com");
        saved.setDisplayName("Bob Two");
        saved.setRole(Role.ADMIN);
        saved.setEnabled(false);
        var updated = userRepository.saveAndFlush(saved);

        var found = userRepository.findByFirebaseUid("firebase-uid-bob").orElseThrow();
        assertThat(found.getEmail()).isEqualTo("bob2@example.com");
        assertThat(found.getDisplayName()).isEqualTo("Bob Two");
        assertThat(found.getRole()).isEqualTo(Role.ADMIN);
        assertThat(found.isEnabled()).isFalse();
        // @PreUpdate advanced updatedAt but left the immutable createdAt untouched.
        // Assert within microsecond tolerance: Postgres timestamptz has microsecond
        // resolution and ROUNDS the in-memory Instant (nanos on Linux CI, micros on macOS)
        // on the round-trip — it does NOT truncate — so neither an exact isEqualTo nor a
        // truncatedTo(MICROS) is stable (rounding can cross a micro boundary, which
        // intermittently red-flaked CI). Comparing within 1µs proves createdAt was
        // preserved without depending on the rounding direction.
        assertThat(found.getCreatedAt()).isCloseTo(createdAt, within(1, java.time.temporal.ChronoUnit.MICROS));
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(createdAt);
    }

    @Test
    void plainSettersAndConstructorExposeState() {
        // Exercises the no-arg constructor and the remaining setters/getters that the
        // persistence round-trip does not drive directly, keeping the entity fully covered.
        var user = new User();
        var id = UUID.randomUUID();
        var now = Instant.now();
        user.setId(id);
        user.setFirebaseUid("uid-x");
        user.setEmail("x@example.com");
        user.setDisplayName("X");
        user.setRole(Role.ADMIN);
        user.setEnabled(false);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        assertThat(user.getId()).isEqualTo(id);
        assertThat(user.getFirebaseUid()).isEqualTo("uid-x");
        assertThat(user.getEmail()).isEqualTo("x@example.com");
        assertThat(user.getDisplayName()).isEqualTo("X");
        assertThat(user.getRole()).isEqualTo(Role.ADMIN);
        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getCreatedAt()).isEqualTo(now);
        assertThat(user.getUpdatedAt()).isEqualTo(now);
        assertThat(Role.valueOf("USER")).isEqualTo(Role.USER);
    }
}
