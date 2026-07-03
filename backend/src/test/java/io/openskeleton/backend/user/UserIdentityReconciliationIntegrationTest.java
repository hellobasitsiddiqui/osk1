package io.openskeleton.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Verifies the account-identity invariant of OSK-163 against a real Testcontainers Postgres:
 * one human maps to exactly one {@code users} row, keyed by a canonical identity (email and/or
 * phone), so signing in via either method — even under a brand-new Firebase uid — resolves to the
 * SAME account and never mints a duplicate.
 *
 * <p>The context booting at all already proves the {@code V10} migration applied and Hibernate
 * (in {@code validate} mode) accepted the mapping; on top of that the tests exercise the two
 * cooperating halves of the feature: the {@code V10} partial unique indexes (the hard DB backstop)
 * and the reconciling JIT provisioning in {@link UserService} (the cooperative resolution).
 *
 * <p><b>Isolation note.</b> The whole suite shares one Testcontainers database (default single
 * Surefire fork), and this class does not wipe it — so, like its sibling {@code @SpringBootTest}s,
 * every row it creates uses identifiers unique to this class ({@code uid-i163-*} uids,
 * {@code @osk163.example.com} emails, {@code +1500163*} phones) so nothing it writes can collide
 * with — or be collided by — another test's data through the new uniqueness constraints.
 */
@SpringBootTest
class UserIdentityReconciliationIntegrationTest {

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    // The container-backed DataSource, used for raw counts that deliberately bypass the entity's
    // soft-delete @SQLRestriction so we can assert on the physical number of rows for an identity.
    @Autowired
    private DataSource dataSource;

    @Test
    void sameEmailViaSecondUidResolvesToOneAccountAndAdoptsTheNewUid() {
        // A user first exists under one Firebase credential...
        User first = userRepository.saveAndFlush(new User("uid-i163-a1", "link@osk163.example.com", "First"));
        UUID firstId = first.getId();

        // ...then signs in again via a DIFFERENT provider — Firebase issues a brand-new uid, same email.
        User resolved = userService.provisionFromToken("uid-i163-a2", "link@osk163.example.com");

        // It resolved to the SAME account (same primary key) — not a second row...
        assertThat(resolved.getId()).isEqualTo(firstId);
        // ...and the new uid was adopted onto that identity (the linking policy).
        assertThat(resolved.getFirebaseUid()).isEqualTo("uid-i163-a2");
        // Exactly one physical row carries this email (all states) — no duplicate account.
        assertThat(countByEmail("link@osk163.example.com")).isEqualTo(1);
        // The old uid no longer resolves; the current credential does.
        assertThat(userRepository.findByFirebaseUid("uid-i163-a1")).isEmpty();
        assertThat(userRepository.findByFirebaseUid("uid-i163-a2")).isPresent();
    }

    @Test
    void emailIdentityIsMatchedCaseInsensitively() {
        // Alice signs up as Mixed.Case, later signs in as lower-case: same human, same account.
        userRepository.saveAndFlush(new User("uid-i163-ci1", "Mixed.Case@osk163.example.com", null));

        User resolved = userService.provisionFromToken("uid-i163-ci2", "mixed.case@osk163.example.com");

        assertThat(resolved.getFirebaseUid()).isEqualTo("uid-i163-ci2");
        assertThat(countByEmail("mixed.case@osk163.example.com")).isEqualTo(1);
    }

    @Test
    void samePhoneViaSecondUidResolvesToOneAccount() {
        // Phone identity reconciliation: a row already owns this phone; a new uid presenting the
        // same phone (with no email) resolves to it rather than creating a second account.
        User seed = new User("uid-i163-p1", null, null);
        seed.setPhone("+15001630001");
        UUID seedId = userRepository.saveAndFlush(seed).getId();

        User resolved = userService.provisionFromToken("uid-i163-p2", null, "+15001630001");

        assertThat(resolved.getId()).isEqualTo(seedId);
        assertThat(resolved.getFirebaseUid()).isEqualTo("uid-i163-p2");
        assertThat(countByPhone("+15001630001")).isEqualTo(1);
    }

    @Test
    void uniqueIndexRejectsASecondActiveRowWithTheSameEmail() {
        // The hard DB backstop: bypassing reconciliation and inserting a duplicate email directly
        // is rejected by ux_users_email_lower_active (case-insensitively).
        userRepository.saveAndFlush(new User("uid-i163-u1", "dup@osk163.example.com", null));

        User dup = new User("uid-i163-u2", "DUP@osk163.example.com", null);
        assertThatThrownBy(() -> userRepository.saveAndFlush(dup)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueIndexRejectsASecondActiveRowWithTheSamePhone() {
        User a = new User("uid-i163-up1", null, null);
        a.setPhone("+15001630009");
        userRepository.saveAndFlush(a);

        User b = new User("uid-i163-up2", null, null);
        b.setPhone("+15001630009");
        assertThatThrownBy(() -> userRepository.saveAndFlush(b)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void manyUsersMayHaveNoEmailAndNoPhone() {
        // The indexes are PARTIAL (WHERE ... IS NOT NULL), so identity-less users (anonymous /
        // pre-identity accounts) are unconstrained: any number may coexist with null email + phone.
        userRepository.saveAndFlush(new User("uid-i163-n1", null, null));
        userRepository.saveAndFlush(new User("uid-i163-n2", null, null));
        userRepository.saveAndFlush(new User("uid-i163-n3", null, null));

        assertThat(userRepository.findByFirebaseUid("uid-i163-n1")).isPresent();
        assertThat(userRepository.findByFirebaseUid("uid-i163-n2")).isPresent();
        assertThat(userRepository.findByFirebaseUid("uid-i163-n3")).isPresent();
    }

    @Test
    void aSoftDeletedIdentityDoesNotReserveTheEmailForANewPerson() {
        // The indexes are ACTIVE-scoped (AND deleted_at IS NULL): a retired (soft-deleted) account
        // must not block a genuinely new person from reusing that email.
        User original = userRepository.saveAndFlush(new User("uid-i163-sd1", "recycle@osk163.example.com", null));
        original.markDeleted();
        userRepository.saveAndFlush(original);

        // A brand-new person reuses the same email — the soft-deleted row is out of the index, so this inserts.
        User fresh = userRepository.saveAndFlush(new User("uid-i163-sd2", "recycle@osk163.example.com", null));
        assertThat(fresh.getId()).isNotNull();
        assertThat(fresh.getId()).isNotEqualTo(original.getId());
    }

    @Test
    void createdAtIsPreservedAcrossARelinkRoundTrip() {
        // Adopting a new uid onto an identity must not disturb the immutable createdAt. Assert
        // within 1µs because Postgres timestamptz rounds the in-memory Instant on the round-trip
        // (see UserRepositoryIntegrationTest for the rationale).
        User first = userRepository.saveAndFlush(new User("uid-i163-ts1", "ts@osk163.example.com", null));
        Instant createdAt = first.getCreatedAt();

        userService.provisionFromToken("uid-i163-ts2", "ts@osk163.example.com");

        User reloaded = userRepository.findByFirebaseUid("uid-i163-ts2").orElseThrow();
        assertThat(reloaded.getId()).isEqualTo(first.getId());
        assertThat(reloaded.getCreatedAt()).isCloseTo(createdAt, within(1, ChronoUnit.MICROS));
    }

    @Test
    void concurrentFirstProvisionOfOneNewIdentityCreatesExactlyOneRow() throws Exception {
        // Race-safe upsert: N concurrent first-sign-ins of the SAME brand-new identity (same uid +
        // email, no pre-existing row). The firebase_uid AND email unique indexes let exactly one
        // insert win; every loser recovers by re-reading and returns the winner's row.
        String uid = "uid-i163-same";
        String email = "same-race@osk163.example.com";
        List<UUID> ids = runConcurrently(
                8, () -> userService.provisionFromToken(uid, email).getId());

        // Every racer returned the SAME id, and exactly one row exists for the identity — no dup, no 500.
        assertThat(ids).allMatch(id -> id.equals(ids.get(0)));
        assertThat(countByEmail(email)).isEqualTo(1);
        assertThat(countByFirebaseUid(uid)).isEqualTo(1);
    }

    @Test
    void concurrentSecondUidSignInsNeverCreateADuplicateAccount() throws Exception {
        // The harder race: one account already exists, and N concurrent sign-ins each present a
        // DIFFERENT new uid for the SAME email. Each attempt either resolves to the single existing
        // account or loses the concurrent relink race and surfaces a conflict (which the API maps to
        // a 409) — but the invariant that matters holds unconditionally: NEVER a duplicate row.
        User seed = userRepository.saveAndFlush(new User("uid-i163-cx-seed", "cx@osk163.example.com", null));
        UUID seedId = seed.getId();

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<UUID>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String uid = "uid-i163-cx-" + i;
                futures.add(pool.submit((Callable<UUID>) () -> {
                    barrier.await();
                    return userService
                            .provisionFromToken(uid, "cx@osk163.example.com")
                            .getId();
                }));
            }
            for (Future<UUID> f : futures) {
                try {
                    // A winning relink resolves to the one existing account.
                    assertThat(f.get()).isEqualTo(seedId);
                } catch (ExecutionException e) {
                    // A losing relink is a genuine concurrent conflict, mapped to 409 at the API edge.
                    assertThat(e.getCause())
                            .isInstanceOfAny(
                                    ObjectOptimisticLockingFailureException.class,
                                    DataIntegrityViolationException.class);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        // The core guarantee: still exactly ONE row for the identity — no duplicate account was created.
        assertThat(countByEmail("cx@osk163.example.com")).isEqualTo(1);
    }

    /** Run {@code task} on {@code threads} threads released together, returning each result. */
    private static List<UUID> runConcurrently(int threads, Callable<UUID> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try {
            List<Future<UUID>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<UUID>) () -> {
                    barrier.await(); // release all threads together to maximise the race
                    return task.call();
                }));
            }
            List<UUID> results = new ArrayList<>();
            for (Future<UUID> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Raw count of physical rows (all soft-delete states) whose email matches case-insensitively. */
    private int countByEmail(String email) {
        return count("SELECT count(*) FROM users WHERE lower(email) = lower(?)", email);
    }

    /** Raw count of physical rows (all soft-delete states) with the given phone. */
    private int countByPhone(String phone) {
        return count("SELECT count(*) FROM users WHERE phone = ?", phone);
    }

    /** Raw count of physical rows (all soft-delete states) with the given firebase uid. */
    private int countByFirebaseUid(String firebaseUid) {
        return count("SELECT count(*) FROM users WHERE firebase_uid = ?", firebaseUid);
    }

    private int count(String sql, Object arg) {
        Integer n = new JdbcTemplate(dataSource).queryForObject(sql, Integer.class, arg);
        return n == null ? 0 : n;
    }
}
