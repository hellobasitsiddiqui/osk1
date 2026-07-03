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
import io.openskeleton.backend.audit.AuditEvent;
import io.openskeleton.backend.audit.AuditRepository;
import io.openskeleton.backend.auth.TokenVerifier;
import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance test for OSK-99 — per-field profile-change history recorded on
 * {@code PATCH /api/v1/me} and read back at {@code GET /api/v1/me/history} — driven through
 * the real MVC stack (auth filter + {@code MeController}) against a real Testcontainers
 * Postgres (the {@code jdbc:tc:...} datasource), with ONLY the {@link TokenVerifier} seam
 * mocked, exactly as {@code MeProvisioningIntegrationTest} / {@code DeviceControllerTest} do.
 *
 * <p>Because the feature reuses the append-only {@code audit_events} table (OSK-80) rather
 * than a bespoke history table, the assertions read the recorded events both through the
 * public endpoint and directly via {@link AuditRepository}, proving the two stay consistent.
 *
 * <p>Covers the acceptance criteria:
 * <ul>
 *   <li><b>AC-1 / AC-3 record:</b> an edit records <b>exactly one</b> history event with the
 *       correct {@code old}/{@code new}; an idempotent re-PATCH of the same value records
 *       nothing (the changed-vs-unchanged branch).</li>
 *   <li><b>AC-2 / AC-3 read:</b> {@code GET /me/history} returns the caller's events,
 *       newest-first, in the shared {@code PagedResponse} envelope.</li>
 *   <li><b>Isolation:</b> the history is scoped to the caller — one user never sees another's
 *       changes.</li>
 *   <li><b>AC-3 auth:</b> an unauthenticated request is a 401 problem+json before any handler
 *       runs.</li>
 * </ul>
 *
 * <p>Each test uses a distinct uid so that the shared (context-cached) container's
 * accumulating audit log never leaks across tests: every assertion is scoped by actor uid.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProfileHistoryIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuditRepository auditRepository;

    /** Mock the verification seam so no live Firebase project/credentials are needed. */
    @MockBean
    private TokenVerifier tokenVerifier;

    @Test
    void editRecordsExactlyOneHistoryEventWithCorrectOldAndNew() throws Exception {
        String token = "hist-token-1";
        String uid = "uid-hist-1";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "hist1@example.com"));

        // A single edit: null → "Alice" (also exercises the null old-value path).
        patchDisplayName(token, "Alice").andExpect(status().isOk());

        // AC-1: exactly ONE PROFILE_UPDATED event for this actor, with the true before/after.
        var events =
                auditRepository.findByActorFirebaseUidAndAction(uid, AuditAction.PROFILE_UPDATED, Pageable.unpaged());
        assertThat(events.getTotalElements()).isEqualTo(1);
        AuditEvent recorded = events.getContent().get(0);
        assertThat(recorded.getActorFirebaseUid()).isEqualTo(uid);
        assertThat(recorded.getTargetType()).isEqualTo("user");
        assertThat(recorded.getTargetId()).isNotNull();
        assertThat(recorded.getMetadata())
                .containsEntry("field", "displayName")
                .containsEntry("old", null)
                .containsEntry("new", "Alice");

        // AC-2: the endpoint surfaces that one entry in the canonical PagedResponse envelope.
        mvc.perform(get("/api/v1/me/history").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].field").value("displayName"))
                // old was null → omitted (mirrors how /me renders a null displayName).
                .andExpect(jsonPath("$.items[0].oldValue").doesNotExist())
                .andExpect(jsonPath("$.items[0].newValue").value("Alice"))
                .andExpect(jsonPath("$.items[0].actor").value(uid))
                .andExpect(jsonPath("$.items[0].changedAt").exists());
    }

    @Test
    void multipleEditsAreListedNewestFirstAndRepeatedValueRecordsNothing() throws Exception {
        String token = "hist-token-2";
        String uid = "uid-hist-2";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "hist2@example.com"));

        // Two genuine edits...
        patchDisplayName(token, "Alice").andExpect(status().isOk());
        patchDisplayName(token, "Bob").andExpect(status().isOk());
        // ...then a redundant re-PATCH of the current value → a no-op, records nothing.
        patchDisplayName(token, "Bob").andExpect(status().isOk());

        // Only the two real changes were recorded (the no-op added no row).
        var events =
                auditRepository.findByActorFirebaseUidAndAction(uid, AuditAction.PROFILE_UPDATED, Pageable.unpaged());
        assertThat(events.getTotalElements()).isEqualTo(2);

        // The endpoint returns both, newest-first: the Alice→Bob change precedes null→Alice.
        mvc.perform(get("/api/v1/me/history").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].oldValue").value("Alice"))
                .andExpect(jsonPath("$.items[0].newValue").value("Bob"))
                .andExpect(jsonPath("$.items[1].oldValue").doesNotExist())
                .andExpect(jsonPath("$.items[1].newValue").value("Alice"));
    }

    @Test
    void historyIsScopedToTheCallerOnly() throws Exception {
        // One caller makes an edit; a different caller must not see it in their history.
        String tokenA = "hist-token-a";
        String uidA = "uid-hist-a";
        when(tokenVerifier.verify(eq(tokenA))).thenReturn(new VerifiedToken(uidA, "a@example.com"));
        patchDisplayName(tokenA, "Owned By A").andExpect(status().isOk());

        String tokenB = "hist-token-b";
        String uidB = "uid-hist-b";
        when(tokenVerifier.verify(eq(tokenB))).thenReturn(new VerifiedToken(uidB, "b@example.com"));

        // B has made no edits → an empty page, and certainly none of A's events.
        mvc.perform(get("/api/v1/me/history").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void unauthenticatedHistoryRequestIs401ProblemDetail() throws Exception {
        // No Authorization header → the auth filter short-circuits with a 401 problem+json
        // before the handler runs (identity is only ever the verified token).
        mvc.perform(get("/api/v1/me/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    /** Issue {@code PATCH /api/v1/me} setting displayName as the given caller. */
    private org.springframework.test.web.servlet.ResultActions patchDisplayName(String bearer, String displayName)
            throws Exception {
        return mvc.perform(patch("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"" + displayName + "\"}"));
    }
}
