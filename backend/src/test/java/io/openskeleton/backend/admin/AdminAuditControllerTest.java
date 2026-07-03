package io.openskeleton.backend.admin;

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
import io.openskeleton.backend.audit.AuditEvent;
import io.openskeleton.backend.audit.AuditRepository;
import io.openskeleton.backend.audit.AuditService;
import io.openskeleton.backend.auth.TokenVerifier;
import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.User.Role;
import io.openskeleton.backend.user.UserRepository;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance tests for OSK-93 — audit wiring on the admin mutation endpoints plus the admin
 * audit read endpoint ({@code GET /api/v1/admin/audit}). Driven through the real MVC stack with
 * only the {@link TokenVerifier} seam mocked (a genuine Firebase token needs live credentials),
 * and a real Testcontainers Postgres behind the repositories, so audit rows are asserted to
 * actually persist and read back.
 *
 * <p>Two callers, exactly as {@code AdminUserControllerTest}: an <b>ADMIN</b> token (→
 * {@code ROLE_ADMIN}) and a <b>USER</b> token (→ {@code ROLE_USER}). Neither is seeded into the
 * DB — their authority comes from the token claim (OSK-79) — so only the <i>target</i> users are
 * seeded, and the admin's uid ({@code "admin-uid"}) is the actor recorded on every event.
 *
 * <p>Coverage of the ACs:
 * <ul>
 *   <li>an admin role-change / disable / enable each writes the expected {@link AuditEvent}
 *       (actor / action / target / metadata);</li>
 *   <li>{@code GET /api/v1/admin/audit} returns events newest-first, paginated;</li>
 *   <li>the optional {@code ?targetUserId=} / {@code ?action=} filters work (each, and both);</li>
 *   <li>a USER-authority caller → 403 and an unauthenticated caller → 401 on the read endpoint;</li>
 *   <li>an unknown {@code ?action=} value → 400 RFC 7807;</li>
 *   <li>the persisted timestamp survives the Postgres round-trip to the microsecond.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminAuditControllerTest {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String USER_TOKEN = "user-token";
    private static final String ADMIN_UID = "admin-uid";
    private static final String USERS_PATH = "/api/v1/admin/users";
    private static final String AUDIT_PATH = "/api/v1/admin/audit";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private AuditService auditService;

    @MockBean
    private TokenVerifier tokenVerifier;

    private UUID aliceId;

    @BeforeEach
    void setUp() throws Exception {
        // Isolate from rows other test classes left in the shared container. The audit log is
        // append-only in production; deleteAll here is test-only isolation, mirroring how
        // AdminUserControllerTest resets the users table.
        auditRepository.deleteAll();
        userRepository.deleteAll();

        when(tokenVerifier.verify(eq(ADMIN_TOKEN)))
                .thenReturn(new VerifiedToken(ADMIN_UID, "admin@example.com", Role.ADMIN));
        when(tokenVerifier.verify(eq(USER_TOKEN)))
                .thenReturn(new VerifiedToken("user-uid", "user@example.com", Role.USER));

        // Seed one target user to administer.
        aliceId = userRepository
                .save(new User("alice-uid", "alice@example.com", "Alice"))
                .getId();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** Record an audit event straight through the seam so read-endpoint tests have data to page. */
    private AuditEvent seedEvent(AuditAction action, String targetId) throws InterruptedException {
        AuditEvent event = auditService.record(ADMIN_UID, action, "USER", targetId);
        // Guarantee a strictly increasing createdAt between successive seeds so the newest-first
        // ordering assertions are deterministic: timestamptz has microsecond resolution, and a
        // few ms of sleep is >1000 micros, well clear of any tie.
        Thread.sleep(5);
        return event;
    }

    // --- Authorization gate ---------------------------------------------------------------

    @Test
    void unauthenticatedAuditReadIsRejectedWith401() throws Exception {
        // No token → the auth filter short-circuits with a 401 before @PreAuthorize runs.
        mvc.perform(get(AUDIT_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void userAuthorityIsForbiddenOnAuditRead() throws Exception {
        mvc.perform(get(AUDIT_PATH).header(HttpHeaders.AUTHORIZATION, bearer(USER_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    // --- Wiring: admin mutations write the expected audit events --------------------------

    @Test
    void adminRoleChangeWritesRoleChangedAuditEvent() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + aliceId + "/role")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        // Exactly one event, capturing who/what/whom plus the old→new role metadata.
        List<AuditEvent> events = auditRepository.findAll();
        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.getActorFirebaseUid()).isEqualTo(ADMIN_UID);
        assertThat(event.getAction()).isEqualTo(AuditAction.ROLE_CHANGED);
        assertThat(event.getTargetType()).isEqualTo("USER");
        assertThat(event.getTargetId()).isEqualTo(aliceId.toString());
        assertThat(event.getMetadata()).containsEntry("old", "USER").containsEntry("new", "ADMIN");
    }

    @Test
    void adminDisableWritesAccountDisabledAuditEvent() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + aliceId + "/enabled")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        List<AuditEvent> events = auditRepository.findAll();
        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.getActorFirebaseUid()).isEqualTo(ADMIN_UID);
        assertThat(event.getAction()).isEqualTo(AuditAction.ACCOUNT_DISABLED);
        assertThat(event.getTargetType()).isEqualTo("USER");
        assertThat(event.getTargetId()).isEqualTo(aliceId.toString());
        // enable/disable carry no extra metadata — the action name tells the whole story.
        assertThat(event.getMetadata()).isNull();
    }

    @Test
    void adminEnableWritesAccountEnabledAuditEvent() throws Exception {
        // Start disabled, then flip on so the recorded action is ACCOUNT_ENABLED.
        User disabled = userRepository.findById(aliceId).orElseThrow();
        disabled.setEnabled(false);
        userRepository.save(disabled);

        mvc.perform(patch(USERS_PATH + "/" + aliceId + "/enabled")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());

        List<AuditEvent> events = auditRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAction()).isEqualTo(AuditAction.ACCOUNT_ENABLED);
        assertThat(events.get(0).getActorFirebaseUid()).isEqualTo(ADMIN_UID);
        assertThat(events.get(0).getTargetId()).isEqualTo(aliceId.toString());
    }

    @Test
    void aRejectedMutationWritesNoAuditEvent() throws Exception {
        // A 404 (unknown id) short-circuits before any save/record, so the log stays empty —
        // audit rows only ever accompany a real mutation.
        mvc.perform(patch(USERS_PATH + "/" + UUID.randomUUID() + "/role")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isNotFound());

        assertThat(auditRepository.count()).isZero();
    }

    // --- Read endpoint: paging + newest-first ---------------------------------------------

    @Test
    void auditReadReturnsEventsNewestFirstInThePagedEnvelope() throws Exception {
        // Seed two events with a guaranteed time gap; the later one must come first.
        seedEvent(AuditAction.ROLE_CHANGED, aliceId.toString());
        seedEvent(AuditAction.ACCOUNT_DISABLED, aliceId.toString());

        mvc.perform(get(AUDIT_PATH).header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items.length()").value(2))
                // Newest-first: the ACCOUNT_DISABLED event was recorded last, so it leads.
                .andExpect(jsonPath("$.items[0].action").value("ACCOUNT_DISABLED"))
                .andExpect(jsonPath("$.items[1].action").value("ROLE_CHANGED"))
                // Full field set is present on an item.
                .andExpect(jsonPath("$.items[0].id").exists())
                .andExpect(jsonPath("$.items[0].actor").value(ADMIN_UID))
                .andExpect(jsonPath("$.items[0].targetType").value("USER"))
                .andExpect(jsonPath("$.items[0].targetId").value(aliceId.toString()))
                .andExpect(jsonPath("$.items[0].createdAt").exists());
    }

    @Test
    void auditReadHonoursThePageSizeParam() throws Exception {
        seedEvent(AuditAction.ROLE_CHANGED, aliceId.toString());
        seedEvent(AuditAction.ACCOUNT_DISABLED, aliceId.toString());

        mvc.perform(get(AUDIT_PATH).param("size", "1").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void auditReadReturnsRoleChangeMetadata() throws Exception {
        // Drive a real role change so the {old, new} metadata is surfaced on the wire.
        mvc.perform(patch(USERS_PATH + "/" + aliceId + "/role")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        mvc.perform(get(AUDIT_PATH).header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("ROLE_CHANGED"))
                .andExpect(jsonPath("$.items[0].metadata.old").value("USER"))
                .andExpect(jsonPath("$.items[0].metadata.new").value("ADMIN"));
    }

    // --- Read endpoint: filters -----------------------------------------------------------

    @Test
    void auditReadFiltersByTargetUserId() throws Exception {
        UUID bobId = userRepository
                .save(new User("bob-uid", "bob@example.com", "Bob"))
                .getId();
        seedEvent(AuditAction.ROLE_CHANGED, aliceId.toString());
        seedEvent(AuditAction.ACCOUNT_DISABLED, bobId.toString());

        mvc.perform(get(AUDIT_PATH)
                        .param("targetUserId", aliceId.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].targetId").value(aliceId.toString()))
                .andExpect(jsonPath("$.items[0].action").value("ROLE_CHANGED"));
    }

    @Test
    void auditReadFiltersByAction() throws Exception {
        seedEvent(AuditAction.ROLE_CHANGED, aliceId.toString());
        seedEvent(AuditAction.ACCOUNT_DISABLED, aliceId.toString());

        mvc.perform(get(AUDIT_PATH)
                        .param("action", "ACCOUNT_DISABLED")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].action").value("ACCOUNT_DISABLED"));
    }

    @Test
    void auditReadFiltersByTargetUserIdAndAction() throws Exception {
        UUID bobId = userRepository
                .save(new User("bob-uid", "bob@example.com", "Bob"))
                .getId();
        seedEvent(AuditAction.ROLE_CHANGED, aliceId.toString());
        seedEvent(AuditAction.ACCOUNT_DISABLED, aliceId.toString());
        seedEvent(AuditAction.ROLE_CHANGED, bobId.toString());

        // Both filters compose: only alice's ROLE_CHANGED, not bob's and not alice's disable.
        mvc.perform(get(AUDIT_PATH)
                        .param("targetUserId", aliceId.toString())
                        .param("action", "ROLE_CHANGED")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].targetId").value(aliceId.toString()))
                .andExpect(jsonPath("$.items[0].action").value("ROLE_CHANGED"));
    }

    @Test
    void auditReadWithUnknownActionGets400ProblemDetail() throws Exception {
        // An unbindable ?action= value is a client error → 400 RFC 7807 (not a 500).
        mvc.perform(get(AUDIT_PATH)
                        .param("action", "NOT_A_REAL_ACTION")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    // --- Timestamp precision --------------------------------------------------------------

    @Test
    void auditEventTimestampSurvivesRoundTripToTheMicrosecond() {
        // Hold the in-memory (nanosecond) createdAt the entity stamped, then re-read the
        // persisted row: Postgres timestamptz rounds nanos to micros, so an exact isEqualTo
        // would flake on CI. Assert with a 1-micro tolerance instead (repo-wide pattern).
        AuditEvent saved = auditService.record(ADMIN_UID, AuditAction.ROLE_CHANGED, "USER", aliceId.toString(), null);
        AuditEvent found = auditRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getCreatedAt()).isCloseTo(saved.getCreatedAt(), within(1, ChronoUnit.MICROS));
    }
}
