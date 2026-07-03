package io.openskeleton.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserMetadata;
import com.google.firebase.auth.UserRecord;
import io.openskeleton.backend.auth.TokenVerifier;
import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance test for OSK-73 — surfacing Firebase-owned, read-only account state on
 * {@code GET /api/v1/me} (email-verified, MFA, phone-verified, phone, photoURL, last-login) plus the
 * backend's own {@code lastActiveAt} heartbeat — driven through the real MVC stack (auth filter +
 * last-active interceptor + {@code MeController}) against a real Testcontainers Postgres.
 *
 * <p>ONLY two seams are mocked: the {@link TokenVerifier} (so no live signed token is needed) and
 * {@link FirebaseAuth} (so the Admin-SDK {@code getUser} lookup is stubbed rather than hitting a live
 * Firebase project). This mirrors how the rest of the auth path is tested. The mere fact the context
 * boots proves the {@code V9} migration applied and Hibernate ({@code validate}) matched the
 * {@link User#getLastActiveAt()} mapping to the {@code last_active_at} column.
 *
 * <p>Each test uses a distinct uid so the shared (context-cached) container and the singleton
 * last-active throttle never leak state across tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeAccountStateIntegrationTest {

    /** A fixed last-sign-in instant (epoch millis) for a deterministic lastLoginAt assertion. */
    private static final long LAST_SIGN_IN_MILLIS = 1_700_000_000_000L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DataSource dataSource;

    /** Mock the verification seam so no live Firebase project/credentials are needed. */
    @MockBean
    private TokenVerifier tokenVerifier;

    /** Mock the Admin-SDK auth client so {@code getUser} is stubbed, not live. */
    @MockBean
    private FirebaseAuth firebaseAuth;

    @Test
    void migrationV9AppliedAndLastActiveColumnExists() {
        var jdbc = new JdbcTemplate(dataSource);

        Boolean v9Success =
                jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '9'", Boolean.class);
        assertThat(v9Success).isTrue();

        Integer lastActiveColumn = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'last_active_at'",
                Integer.class);
        assertThat(lastActiveColumn).isEqualTo(1);
    }

    @Test
    void meSurfacesFirebaseOwnedAccountStateFromTheStubbedUserRecord() throws Exception {
        String token = "acct-token-full";
        String uid = "uid-acct-full";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "full@example.com"));

        UserMetadata metadata = mock(UserMetadata.class);
        when(metadata.getLastSignInTimestamp()).thenReturn(LAST_SIGN_IN_MILLIS);
        UserRecord record = mock(UserRecord.class);
        when(record.isEmailVerified()).thenReturn(true);
        when(record.getPhoneNumber()).thenReturn("+15551234567");
        when(record.getPhotoUrl()).thenReturn("https://example.com/photo.png");
        when(record.getUserMetadata()).thenReturn(metadata);
        when(firebaseAuth.getUser(eq(uid))).thenReturn(record);

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.phoneVerified").value(true))
                .andExpect(jsonPath("$.phoneNumber").value("+15551234567"))
                .andExpect(jsonPath("$.photoUrl").value("https://example.com/photo.png"))
                .andExpect(jsonPath("$.lastLoginAt")
                        .value(Instant.ofEpochMilli(LAST_SIGN_IN_MILLIS).toString()))
                // MFA is not exposed by firebase-admin 9.9.0 → always null → omitted from the JSON.
                .andExpect(jsonPath("$.mfaEnabled").doesNotExist());
    }

    @Test
    void meDegradesToNullFirebaseFieldsWhenTheLookupFails() throws Exception {
        String token = "acct-token-degrade";
        String uid = "uid-acct-degrade";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "degrade@example.com"));
        // Firebase Admin unavailable / uid has no record → the SDK lookup throws; /me must still answer.
        when(firebaseAuth.getUser(eq(uid))).thenThrow(new RuntimeException("firebase down"));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                // Persisted identity still present...
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.email").value("degrade@example.com"))
                // ...but every Firebase-owned field gracefully degrades to null (omitted).
                .andExpect(jsonPath("$.emailVerified").doesNotExist())
                .andExpect(jsonPath("$.phoneVerified").doesNotExist())
                .andExpect(jsonPath("$.phoneNumber").doesNotExist())
                .andExpect(jsonPath("$.photoUrl").doesNotExist())
                .andExpect(jsonPath("$.lastLoginAt").doesNotExist())
                .andExpect(jsonPath("$.mfaEnabled").doesNotExist());
    }

    @Test
    void meStampsAndSurfacesLastActiveOnAuthenticatedRequests() throws Exception {
        String token = "acct-token-active";
        String uid = "uid-acct-active";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "active@example.com"));

        // Seed the user with a STALE last_active (well outside the throttle window) so the interceptor
        // is due to refresh it. A distinct uid keeps the singleton throttle cache clean for this test.
        User seeded = new User(uid, "active@example.com", null);
        Instant stale = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        seeded.setLastActiveAt(stale);
        userRepository.save(seeded);

        Instant beforeRequest = Instant.now();

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                // The heartbeat was refreshed and surfaced (present, no longer the stale value).
                .andExpect(jsonPath("$.lastActiveAt").exists());

        // The persisted column was actually advanced to "now" (after the stale seed, at/after the
        // request start), proving the interceptor stamped it.
        Instant persisted = userRepository.findByFirebaseUid(uid).orElseThrow().getLastActiveAt();
        assertThat(persisted).isNotNull();
        assertThat(persisted).isAfter(stale);
        assertThat(persisted).isAfter(beforeRequest.minusSeconds(1));
    }

    @Test
    void meSurfacesThePersistedLastActiveValueThatRoundTripsThroughPostgres() throws Exception {
        String token = "acct-token-roundtrip";
        String uid = "uid-acct-roundtrip";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "rt@example.com"));

        // Seed a RECENT last_active (inside the throttle window) so the interceptor leaves it untouched
        // and /me surfaces exactly this value — letting us assert the DB round-trip precisely.
        User seeded = new User(uid, "rt@example.com", null);
        Instant recent = Instant.now().truncatedTo(ChronoUnit.MICROS);
        seeded.setLastActiveAt(recent);
        userRepository.save(seeded);

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastActiveAt").exists());

        // Instant equality after a DB round-trip: Postgres stores microsecond precision, so compare
        // with a 1-microsecond tolerance (repo convention).
        Instant persisted = userRepository.findByFirebaseUid(uid).orElseThrow().getLastActiveAt();
        assertThat(persisted).isCloseTo(recent, within(1, ChronoUnit.MICROS));
    }

    @Test
    void freshlyProvisionedUserHasNoLastActiveYet() throws Exception {
        // The very first /me provisions the row AFTER the interceptor runs, so there is nothing to
        // stamp yet — lastActiveAt is null (omitted) on the first-ever request.
        String token = "acct-token-fresh";
        String uid = "uid-acct-fresh";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "fresh@example.com"));
        when(firebaseAuth.getUser(any())).thenThrow(new RuntimeException("no firebase record"));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.lastActiveAt").doesNotExist());
    }
}
