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

/**
 * Integration test for the OSK-168 account-type (REAL vs TEST) column + setter seam, run
 * against a real Testcontainers Postgres (the {@code jdbc:tc:...} datasource).
 *
 * <p>As with the sibling {@code @SpringBootTest}s here, the context booting at all is a
 * proof point: Hibernate runs in {@code validate} mode, so the {@link User#getAccountType()}
 * mapping (a new {@code @Enumerated(STRING)} field) MUST match the {@code account_type}
 * column that {@code V6__add_account_type_to_users.sql} adds — otherwise startup fails
 * before any test runs (AC-2).
 *
 * <p>On top of that it exercises the acceptance criteria against the real DB: the V6
 * migration is recorded and the column shape is right (AC-1), a freshly persisted user
 * defaults to {@link AccountType#REAL} (AC-4), the {@link UserService#markAccountType} setter
 * flips it to {@link AccountType#TEST} and the value survives a reload (AC-3/AC-4), and the
 * setter 404s for an unknown uid rather than creating a row.
 */
@SpringBootTest
class UserAccountTypeIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private DataSource dataSource;

    @Test
    void migrationV6AddedAccountTypeColumnWithRealDefault() {
        var jdbc = new JdbcTemplate(dataSource);

        // Flyway recorded V6 as applied.
        Boolean v6Success =
                jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '6'", Boolean.class);
        assertThat(v6Success).isTrue();

        // account_type is a NOT NULL text column defaulting to 'REAL'.
        Integer accountTypeCol = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'users' AND column_name = 'account_type' AND is_nullable = 'NO'",
                Integer.class);
        assertThat(accountTypeCol).isEqualTo(1);
        String columnDefault = jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_name = 'users' AND column_name = 'account_type'",
                String.class);
        assertThat(columnDefault).contains("REAL");
    }

    @Test
    void newlyPersistedUserDefaultsToRealAndSurvivesReload() {
        var saved = userRepository.saveAndFlush(new User("uid-at-fresh", "fresh@at.example.com", "Fresh"));
        assertThat(saved.getAccountType()).isEqualTo(AccountType.REAL);

        // Reload from the DB (not the identity-map instance) to prove REAL was actually
        // written, not just held in memory by the entity's field default.
        userRepository.flush();
        var reloaded = userRepository.findByFirebaseUid("uid-at-fresh").orElseThrow();
        assertThat(reloaded.getAccountType()).isEqualTo(AccountType.REAL);
    }

    @Test
    void markAccountTypeFlipsToTestAndPersists() {
        var seeded = userRepository.saveAndFlush(new User("uid-at-mark", "mark@at.example.com", "Mark"));
        assertThat(seeded.getAccountType()).isEqualTo(AccountType.REAL);

        var marked = userService.markAccountType("uid-at-mark", AccountType.TEST);
        assertThat(marked.getAccountType()).isEqualTo(AccountType.TEST);

        // The change is durable: a fresh read from Postgres sees TEST.
        var reloaded = userRepository.findByFirebaseUid("uid-at-mark").orElseThrow();
        assertThat(reloaded.getAccountType()).isEqualTo(AccountType.TEST);
    }

    @Test
    void markAccountTypeOfUnknownUserThrowsNotFound() {
        assertThatThrownBy(() -> userService.markAccountType("uid-at-missing-" + UUID.randomUUID(), AccountType.TEST))
                .isInstanceOf(NotFoundException.class);
    }
}
