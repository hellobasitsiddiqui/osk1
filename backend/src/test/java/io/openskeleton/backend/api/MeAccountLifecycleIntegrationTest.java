package io.openskeleton.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openskeleton.backend.audit.AuditAction;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance test for OSK-69 — the account-lifecycle fields on {@code /api/v1/me}
 * (onboarding-completed, terms-accepted version+timestamp, age-verified) — driven through the
 * real MVC stack (auth filter + {@code MeController}) against a real Testcontainers Postgres
 * (the {@code jdbc:tc:...} datasource), with ONLY the {@link TokenVerifier} seam mocked, exactly
 * as {@code MeProfileFieldsIntegrationTest} / {@code ProfileHistoryIntegrationTest}.
 *
 * <p>The mere fact the context boots already proves the {@code V8} migration applied on the
 * container and that Hibernate ({@code validate} mode) asserted the extended {@link User} mapping
 * matches the Flyway-built schema. On top of that boot-time proof the tests exercise: V8's columns
 * physically exist; a fresh user exposes the lifecycle defaults; {@code PATCH /me/lifecycle} marks
 * onboarding complete, accepts terms (recording version + a server timestamp) and marks
 * age-verified; a blank terms version is rejected 400 {@code problem+json}; re-submitting current
 * state is an idempotent no-op; every genuine change is recorded in the reused audit history; and a
 * direct repository round-trip reads and writes the new columns (including the timestamptz).
 *
 * <p>Each test uses a distinct uid so the shared (context-cached) container's accumulating audit
 * log never leaks across tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeAccountLifecycleIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DataSource dataSource;

    /** Mock the verification seam so no live Firebase project/credentials are needed. */
    @MockBean
    private TokenVerifier tokenVerifier;

    @Test
    void migrationV8AppliedAndLifecycleColumnsExist() {
        var jdbc = new JdbcTemplate(dataSource);

        // Flyway recorded V8 as a successful, applied migration.
        Boolean v8Success =
                jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '8'", Boolean.class);
        assertThat(v8Success).isTrue();

        // All four new columns physically exist on the migrated users table.
        Integer newColumns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'users' AND column_name IN "
                        + "('onboarding_completed','terms_accepted_version','terms_accepted_at','age_verified')",
                Integer.class);
        assertThat(newColumns).isEqualTo(4);
    }

    @Test
    void freshlyProvisionedUserExposesLifecycleDefaults() throws Exception {
        String token = "lc-token-fresh";
        String uid = "uid-lc-fresh";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "fresh@lc.example.com"));

        // First GET provisions the row (OSK-76) — the two booleans default false and are always
        // present; the terms pair starts unset (rendered absent as with any null field).
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.onboardingCompleted").value(false))
                .andExpect(jsonPath("$.ageVerified").value(false))
                .andExpect(jsonPath("$.termsAcceptedVersion").doesNotExist())
                .andExpect(jsonPath("$.termsAcceptedAt").doesNotExist());
    }

    @Test
    void patchMarksOnboardingCompletePersistsAndIsReflectedByGet() throws Exception {
        String token = "lc-token-onb";
        String uid = "uid-lc-onb";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "onb@lc.example.com"));

        mvc.perform(patch("/api/v1/me/lifecycle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingCompleted\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted").value(true))
                // Untouched lifecycle state is left at its defaults (sparse PATCH).
                .andExpect(jsonPath("$.ageVerified").value(false));

        // Persisted to the real database.
        User persisted = userRepository.findByFirebaseUid(uid).orElseThrow();
        assertThat(persisted.isOnboardingCompleted()).isTrue();
        assertThat(persisted.isAgeVerified()).isFalse();

        // ...and the next GET reflects it.
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted").value(true));
    }

    @Test
    void acceptTermsSetsVersionAndServerTimestamp() throws Exception {
        String token = "lc-token-terms";
        String uid = "uid-lc-terms";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "terms@lc.example.com"));

        Instant before = Instant.now();
        mvc.perform(patch("/api/v1/me/lifecycle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"termsAcceptedVersion\": \"v1.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termsAcceptedVersion").value("v1.0"))
                // The server stamped an acceptance timestamp (present in the response).
                .andExpect(jsonPath("$.termsAcceptedAt").exists());

        // Persisted: version stored, and the timestamp is a fresh SERVER instant (near "now"),
        // never taken from the client body (the request carried no timestamp at all).
        User persisted = userRepository.findByFirebaseUid(uid).orElseThrow();
        assertThat(persisted.getTermsAcceptedVersion()).isEqualTo("v1.0");
        assertThat(persisted.getTermsAcceptedAt()).isNotNull().isCloseTo(before, within(10, ChronoUnit.SECONDS));
    }

    @Test
    void patchMarksAgeVerifiedPersistsAndIsReflectedByGet() throws Exception {
        String token = "lc-token-age";
        String uid = "uid-lc-age";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "age@lc.example.com"));

        mvc.perform(patch("/api/v1/me/lifecycle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ageVerified\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ageVerified").value(true));

        User persisted = userRepository.findByFirebaseUid(uid).orElseThrow();
        assertThat(persisted.isAgeVerified()).isTrue();

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ageVerified").value(true));
    }

    @Test
    void lifecycleChangesAreRecordedInProfileHistory() throws Exception {
        String token = "lc-token-hist";
        String uid = "uid-lc-hist";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "hist@lc.example.com"));

        // A single PATCH changing all three lifecycle facets → three PROFILE_UPDATED history
        // events (terms records the version only; the timestamp is derived state, not a field).
        mvc.perform(
                        patch("/api/v1/me/lifecycle")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"onboardingCompleted\":true,\"termsAcceptedVersion\":\"v2.0\",\"ageVerified\":true}"))
                .andExpect(status().isOk());

        // Surfaced by GET /me/history in the shared PagedResponse envelope.
        mvc.perform(get("/api/v1/me/history").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[?(@.field == 'onboardingCompleted')].newValue")
                        .value("true"))
                .andExpect(jsonPath("$.items[?(@.field == 'termsAcceptedVersion')].newValue")
                        .value("v2.0"))
                .andExpect(jsonPath("$.items[?(@.field == 'ageVerified')].newValue")
                        .value("true"));

        // The lifecycle edits are the same audit_events the history reads.
        var events = new JdbcTemplate(dataSource)
                .queryForObject(
                        "SELECT count(*) FROM audit_events WHERE actor_firebase_uid = ? AND action = ?",
                        Integer.class,
                        uid,
                        AuditAction.PROFILE_UPDATED.name());
        assertThat(events).isEqualTo(3);
    }

    @Test
    void patchRejectsBlankTermsVersionWith400() throws Exception {
        String token = "lc-token-blank";
        String uid = "uid-lc-blank";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "blank@lc.example.com"));

        // Whitespace-only version → bean-validation 400 problem+json (must not be blank).
        mvc.perform(patch("/api/v1/me/lifecycle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"termsAcceptedVersion\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("termsAcceptedVersion")));

        // The 400 fires at @Valid argument binding, before the handler body runs, so the user is
        // never even JIT-provisioned — nothing is persisted for this uid at all.
        assertThat(userRepository.findByFirebaseUid(uid)).isEmpty();
    }

    @Test
    void reAcceptingSameTermsVersionIsAnIdempotentNoOp() throws Exception {
        String token = "lc-token-reterms";
        String uid = "uid-lc-reterms";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "reterms@lc.example.com"));

        // First acceptance stamps a timestamp and records one history event.
        mvc.perform(patch("/api/v1/me/lifecycle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"termsAcceptedVersion\": \"v1.0\"}"))
                .andExpect(status().isOk());
        Instant firstAcceptedAt =
                userRepository.findByFirebaseUid(uid).orElseThrow().getTermsAcceptedAt();
        assertThat(firstAcceptedAt).isNotNull();

        // Re-accepting the SAME version is a no-op: no new timestamp, no new history event.
        mvc.perform(patch("/api/v1/me/lifecycle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"termsAcceptedVersion\": \"v1.0\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.termsAcceptedVersion").value("v1.0"));

        Instant secondAcceptedAt =
                userRepository.findByFirebaseUid(uid).orElseThrow().getTermsAcceptedAt();
        assertThat(secondAcceptedAt).isEqualTo(firstAcceptedAt); // unchanged — not re-stamped

        // Still exactly one PROFILE_UPDATED event (the second PATCH recorded nothing).
        var events = new JdbcTemplate(dataSource)
                .queryForObject(
                        "SELECT count(*) FROM audit_events WHERE actor_firebase_uid = ? AND action = ?",
                        Integer.class,
                        uid,
                        AuditAction.PROFILE_UPDATED.name());
        assertThat(events).isEqualTo(1);
    }

    @Test
    void resubmittingCurrentBooleanStateIsANoOp() throws Exception {
        String token = "lc-token-noop";
        String uid = "uid-lc-noop";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "noop@lc.example.com"));

        // Set onboarding complete once.
        mvc.perform(patch("/api/v1/me/lifecycle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingCompleted\": true}"))
                .andExpect(status().isOk());

        // Re-submit the same true value plus an all-null-effect body — no genuine change, so no
        // second history event is recorded (idempotent no-op path).
        mvc.perform(patch("/api/v1/me/lifecycle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"onboardingCompleted\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted").value(true));

        var events = new JdbcTemplate(dataSource)
                .queryForObject(
                        "SELECT count(*) FROM audit_events WHERE actor_firebase_uid = ? AND action = ?",
                        Integer.class,
                        uid,
                        AuditAction.PROFILE_UPDATED.name());
        assertThat(events).isEqualTo(1);
    }

    @Test
    void emptyLifecyclePatchChangesNothing() throws Exception {
        String token = "lc-token-empty";
        String uid = "uid-lc-empty";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "empty@lc.example.com"));

        // A body with every field absent leaves all lifecycle state at its defaults and records
        // no history (the edits list is empty → no write).
        mvc.perform(patch("/api/v1/me/lifecycle")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onboardingCompleted").value(false))
                .andExpect(jsonPath("$.ageVerified").value(false))
                .andExpect(jsonPath("$.termsAcceptedVersion").doesNotExist());

        var events = new JdbcTemplate(dataSource)
                .queryForObject(
                        "SELECT count(*) FROM audit_events WHERE actor_firebase_uid = ? AND action = ?",
                        Integer.class,
                        uid,
                        AuditAction.PROFILE_UPDATED.name());
        assertThat(events).isEqualTo(0);
    }

    @Test
    void repositoryRoundTripPersistsAndReadsBackTheLifecycleColumns() {
        // Direct entity/repository round-trip for the new columns (independent of the HTTP layer).
        Instant acceptedAt = Instant.now();
        User user = new User("uid-lc-rt", "rt@lc.example.com", "RT");
        user.setOnboardingCompleted(true);
        user.setTermsAcceptedVersion("2026-01-01");
        user.setTermsAcceptedAt(acceptedAt);
        user.setAgeVerified(true);

        userRepository.saveAndFlush(user);

        User found = userRepository.findByFirebaseUid("uid-lc-rt").orElseThrow();
        assertThat(found.isOnboardingCompleted()).isTrue();
        assertThat(found.getTermsAcceptedVersion()).isEqualTo("2026-01-01");
        assertThat(found.isAgeVerified()).isTrue();
        // Timestamp gotcha: Postgres timestamptz has microsecond resolution and ROUNDS the
        // in-memory Instant (nanos on Linux CI) on the round-trip — not truncates — so assert
        // within 1µs rather than an exact match (repo-wide convention).
        assertThat(found.getTermsAcceptedAt()).isCloseTo(acceptedAt, within(1, ChronoUnit.MICROS));
    }

    @Test
    void defaultsHoldForAProvisionedUserWithNoLifecycleSet() {
        // A user inserted with no lifecycle set persists the boolean defaults (false) and null
        // terms pair — first-login upsert safe.
        userRepository.saveAndFlush(new User("uid-lc-def", "def@lc.example.com", null));

        User found = userRepository.findByFirebaseUid("uid-lc-def").orElseThrow();
        assertThat(found.isOnboardingCompleted()).isFalse();
        assertThat(found.isAgeVerified()).isFalse();
        assertThat(found.getTermsAcceptedVersion()).isNull();
        assertThat(found.getTermsAcceptedAt()).isNull();
    }
}
