package io.openskeleton.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
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
import io.openskeleton.backend.user.NotificationPreference;
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
 * Acceptance test for OSK-67 — the richer self-service profile fields on {@code /api/v1/me}
 * (first/last name, city, age, phone, notification preference, timezone, locale) — driven
 * through the real MVC stack (auth filter + {@code MeController}) against a real Testcontainers
 * Postgres (the {@code jdbc:tc:...} datasource), with ONLY the {@link TokenVerifier} seam
 * mocked, exactly as {@code MeProvisioningIntegrationTest} / {@code ProfileHistoryIntegrationTest}.
 *
 * <p>The mere fact the context boots already proves the {@code V7} migration applied on the
 * container and that Hibernate ({@code validate} mode) asserted the extended {@link User}
 * mapping matches the Flyway-built schema. On top of that boot-time proof the tests exercise:
 * V7's columns physically exist; {@code GET /me} exposes the new fields; {@code PATCH /me}
 * updates and persists each; bad age/timezone/locale are rejected 400 {@code problem+json};
 * each edit is recorded in the reused audit history; and a direct repository round-trip reads
 * and writes the new columns.
 *
 * <p>Each test uses a distinct uid so the shared (context-cached) container's accumulating
 * audit log never leaks across tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeProfileFieldsIntegrationTest {

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
    void migrationV7AppliedAndProfileColumnsExist() {
        var jdbc = new JdbcTemplate(dataSource);

        // Flyway recorded V7 as a successful, applied migration.
        Boolean v7Success =
                jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '7'", Boolean.class);
        assertThat(v7Success).isTrue();

        // All eight new columns physically exist on the migrated users table.
        Integer newColumns = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'users' AND column_name IN "
                        + "('first_name','last_name','city','age','phone','notification_preference','timezone','locale')",
                Integer.class);
        assertThat(newColumns).isEqualTo(8);
    }

    @Test
    void freshlyProvisionedUserExposesNullProfileFieldsAndDefaultNotificationPreference() throws Exception {
        String token = "prof-token-fresh";
        String uid = "uid-prof-fresh";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "fresh@example.com"));

        // First GET provisions the row (OSK-76) — the new fields start unset, and the
        // notification preference defaults to EMAIL (its NOT NULL column default).
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.firstName").doesNotExist())
                .andExpect(jsonPath("$.lastName").doesNotExist())
                .andExpect(jsonPath("$.city").doesNotExist())
                .andExpect(jsonPath("$.age").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.timezone").doesNotExist())
                .andExpect(jsonPath("$.locale").doesNotExist())
                .andExpect(jsonPath("$.notificationPreference").value("EMAIL"));
    }

    @Test
    void patchSetsEveryNewFieldPersistsAndIsReflectedByGet() throws Exception {
        String token = "prof-token-set";
        String uid = "uid-prof-set";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "set@example.com"));

        String body =
                """
                {
                  "displayName": "Ada Lovelace",
                  "firstName": "Ada",
                  "lastName": "Lovelace",
                  "city": "London",
                  "age": 30,
                  "phone": "+15551234567",
                  "notificationPreference": "PUSH",
                  "timezone": "Europe/London",
                  "locale": "en-GB"
                }
                """;

        // PATCH provisions-then-updates in one shot and echoes the persisted profile.
        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.firstName").value("Ada"))
                .andExpect(jsonPath("$.lastName").value("Lovelace"))
                .andExpect(jsonPath("$.city").value("London"))
                .andExpect(jsonPath("$.age").value(30))
                .andExpect(jsonPath("$.phone").value("+15551234567"))
                .andExpect(jsonPath("$.notificationPreference").value("PUSH"))
                .andExpect(jsonPath("$.timezone").value("Europe/London"))
                .andExpect(jsonPath("$.locale").value("en-GB"));

        // Persisted to the real database: a fresh repository read sees every field.
        User persisted = userRepository.findByFirebaseUid(uid).orElseThrow();
        assertThat(persisted.getFirstName()).isEqualTo("Ada");
        assertThat(persisted.getLastName()).isEqualTo("Lovelace");
        assertThat(persisted.getCity()).isEqualTo("London");
        assertThat(persisted.getAge()).isEqualTo(30);
        assertThat(persisted.getPhone()).isEqualTo("+15551234567");
        assertThat(persisted.getNotificationPreference()).isEqualTo(NotificationPreference.PUSH);
        assertThat(persisted.getTimezone()).isEqualTo("Europe/London");
        assertThat(persisted.getLocale()).isEqualTo("en-GB");

        // ...and the next GET reflects the persisted profile.
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("London"))
                .andExpect(jsonPath("$.notificationPreference").value("PUSH"));
    }

    @Test
    void patchIsSparseAndDoesNotClobberUnmentionedFields() throws Exception {
        String token = "prof-token-sparse";
        String uid = "uid-prof-sparse";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "sparse@example.com"));

        // Set two fields first.
        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"firstName\":\"Grace\",\"city\":\"NYC\"}"))
                .andExpect(status().isOk());

        // A second PATCH touching only city must leave firstName intact (not null it out).
        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Boston\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("Grace"))
                .andExpect(jsonPath("$.city").value("Boston"));

        User persisted = userRepository.findByFirebaseUid(uid).orElseThrow();
        assertThat(persisted.getFirstName()).isEqualTo("Grace");
        assertThat(persisted.getCity()).isEqualTo("Boston");
    }

    @Test
    void patchRejectsOutOfRangeAgeWith400() throws Exception {
        String token = "prof-token-age";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken("uid-prof-age", "age@example.com"));

        // Too old (> 120) → bean-validation 400 problem+json.
        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"age\": 200}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("age")));

        // Too young (< 13) → also 400.
        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"age\": 10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // Nothing was persisted for this user beyond the JIT row (no age set).
        assertThat(userRepository.findByFirebaseUid("uid-prof-age")).isEmpty();
    }

    @Test
    void patchRejectsInvalidTimezoneWith400() throws Exception {
        String token = "prof-token-tz";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken("uid-prof-tz", "tz@example.com"));

        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timezone\": \"Mars/Phobos\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("timezone")));
    }

    @Test
    void patchRejectsMalformedLocaleWith400() throws Exception {
        String token = "prof-token-loc";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken("uid-prof-loc", "loc@example.com"));

        // Underscore is not a valid BCP-47 separator (should be en-GB).
        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\": \"en_GB\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("locale")));
    }

    @Test
    void editsToNewFieldsAreRecordedInProfileHistory() throws Exception {
        String token = "prof-token-hist";
        String uid = "uid-prof-hist";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "hist@example.com"));

        // A single PATCH changing three fields → three PROFILE_UPDATED history events.
        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Paris\",\"age\":41,\"notificationPreference\":\"NONE\"}"))
                .andExpect(status().isOk());

        // Surfaced by GET /me/history in the shared PagedResponse envelope, newest-first.
        mvc.perform(get("/api/v1/me/history").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items[?(@.field == 'city')].newValue").value("Paris"))
                .andExpect(jsonPath("$.items[?(@.field == 'age')].newValue").value("41"))
                .andExpect(jsonPath("$.items[?(@.field == 'notificationPreference')].newValue")
                        .value("NONE"));

        // The new-field edits are the same audit_events the history reads (target = the user).
        var events = new JdbcTemplate(dataSource)
                .queryForObject(
                        "SELECT count(*) FROM audit_events WHERE actor_firebase_uid = ? AND action = ?",
                        Integer.class,
                        uid,
                        AuditAction.PROFILE_UPDATED.name());
        assertThat(events).isEqualTo(3);
    }

    @Test
    void repositoryRoundTripPersistsAndReadsBackTheNewColumns() {
        // Direct entity/repository round-trip for the new columns (independent of the HTTP layer).
        User user = new User("uid-prof-rt", "rt@example.com", "RT");
        user.setFirstName("Grace");
        user.setLastName("Hopper");
        user.setCity("New York");
        user.setAge(45);
        user.setPhone("+1-202-555-0100");
        user.setNotificationPreference(NotificationPreference.NONE);
        user.setTimezone("America/New_York");
        user.setLocale("en-US");

        User saved = userRepository.saveAndFlush(user);
        Instant createdAt = saved.getCreatedAt();

        User found = userRepository.findByFirebaseUid("uid-prof-rt").orElseThrow();
        assertThat(found.getFirstName()).isEqualTo("Grace");
        assertThat(found.getLastName()).isEqualTo("Hopper");
        assertThat(found.getCity()).isEqualTo("New York");
        assertThat(found.getAge()).isEqualTo(45);
        assertThat(found.getPhone()).isEqualTo("+1-202-555-0100");
        assertThat(found.getNotificationPreference()).isEqualTo(NotificationPreference.NONE);
        assertThat(found.getTimezone()).isEqualTo("America/New_York");
        assertThat(found.getLocale()).isEqualTo("en-US");
        // Timestamp gotcha: Postgres timestamptz stores microseconds, so the in-memory Instant
        // (nanos on Linux CI) round-trips truncated — compare the expected at MICROS precision.
        assertThat(found.getCreatedAt()).isEqualTo(createdAt.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void provisionedUserWithoutAnyPreferenceDefaultsToEmail() {
        // A user inserted with no notification preference set persists the EMAIL default
        // (the entity field default + the NOT NULL DEFAULT column) — first-login upsert safe.
        userRepository.saveAndFlush(new User("uid-prof-def", "def@example.com", null));

        User found = userRepository.findByFirebaseUid("uid-prof-def").orElseThrow();
        assertThat(found.getNotificationPreference()).isEqualTo(NotificationPreference.EMAIL);
        assertThat(found.getAge()).isNull();
        assertThat(found.getFirstName()).isNull();
    }
}
