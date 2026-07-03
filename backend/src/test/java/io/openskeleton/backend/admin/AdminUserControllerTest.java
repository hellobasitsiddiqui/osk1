package io.openskeleton.backend.admin;

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
import io.openskeleton.backend.audit.AuditService;
import io.openskeleton.backend.auth.TokenVerifier;
import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import io.openskeleton.backend.user.AccountType;
import io.openskeleton.backend.user.NotificationPreference;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.User.Role;
import io.openskeleton.backend.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance tests for the admin user-management endpoints (OSK-71) — the {@code @PreAuthorize}
 * gate and the CRUD behaviour, driven through the real MVC stack with only the
 * {@link TokenVerifier} seam mocked (exactly as the other auth tests do, since a genuine
 * Firebase token needs live credentials). A real Testcontainers Postgres backs the repository,
 * so mutations are asserted to actually persist.
 *
 * <p>The two callers used throughout: an <b>ADMIN</b> token (verifier returns
 * {@link Role#ADMIN} → {@code ROLE_ADMIN} authority) and a <b>USER</b> token (→
 * {@code ROLE_USER}). Neither caller is seeded into the DB: their authority comes from the
 * token claim (OSK-79), and the auth filter lets a not-yet-provisioned caller through, so only
 * the <i>target</i> users need seeding.
 *
 * <p>Coverage of the ACs:
 * <ul>
 *   <li>USER authority → 403 on every endpoint; unauthenticated → 401.</li>
 *   <li>ADMIN authority → 200 for reads and mutations, with the change persisted.</li>
 *   <li>unknown id → 404; invalid/missing role or enabled body → 400 — all RFC 7807.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserControllerTest {

    private static final String ADMIN_TOKEN = "admin-token";
    private static final String USER_TOKEN = "user-token";
    private static final String USERS_PATH = "/api/v1/admin/users";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditService auditService;

    @MockBean
    private TokenVerifier tokenVerifier;

    private UUID aliceId;

    @BeforeEach
    void setUp() throws Exception {
        // Isolate from any rows other test classes left in the shared container.
        userRepository.deleteAll();

        // The admin/user callers are identified purely by their token claim; no DB row needed.
        when(tokenVerifier.verify(eq(ADMIN_TOKEN)))
                .thenReturn(new VerifiedToken("admin-uid", "admin@example.com", Role.ADMIN));
        when(tokenVerifier.verify(eq(USER_TOKEN)))
                .thenReturn(new VerifiedToken("user-uid", "user@example.com", Role.USER));

        // Seed one target user to administer.
        User alice = new User("alice-uid", "alice@example.com", "Alice");
        aliceId = userRepository.save(alice).getId();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // --- Authorization gate ---------------------------------------------------------------

    @Test
    void unauthenticatedRequestIsRejectedWith401() throws Exception {
        // No token at all → the auth filter short-circuits with a 401 before @PreAuthorize runs.
        mvc.perform(get(USERS_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void userAuthorityIsForbiddenOnListEndpoint() throws Exception {
        mvc.perform(get(USERS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(USER_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void userAuthorityIsForbiddenOnGetOneEndpoint() throws Exception {
        mvc.perform(get(USERS_PATH + "/" + aliceId).header(HttpHeaders.AUTHORIZATION, bearer(USER_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void userAuthorityIsForbiddenOnChangeRoleEndpoint() throws Exception {
        // A VALID body is used deliberately: with an invalid body the 400 would fire during
        // argument binding, before @PreAuthorize — this proves the authorization denial itself.
        mvc.perform(patch(USERS_PATH + "/" + aliceId + "/role")
                        .header(HttpHeaders.AUTHORIZATION, bearer(USER_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void userAuthorityIsForbiddenOnSetEnabledEndpoint() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + aliceId + "/enabled")
                        .header(HttpHeaders.AUTHORIZATION, bearer(USER_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        // And the target must be untouched by a forbidden call.
        assertThat(userRepository.findById(aliceId).orElseThrow().isEnabled()).isTrue();
    }

    // --- Admin reads ----------------------------------------------------------------------

    @Test
    void adminListsUsersInThePagedEnvelopeWithTheExpectedFields() throws Exception {
        // Add a second user so the page clearly carries more than the seeded one.
        User bob = new User("bob-uid", "bob@example.com", "Bob");
        bob.setRole(Role.ADMIN);
        userRepository.save(bob);

        mvc.perform(get(USERS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.items.length()").value(2))
                // Default sort is id ASC; assert the full field set is present on an item.
                .andExpect(jsonPath("$.items[0].id").exists())
                .andExpect(jsonPath("$.items[0].email").exists())
                .andExpect(jsonPath("$.items[0].displayName").exists())
                .andExpect(jsonPath("$.items[0].role").exists())
                .andExpect(jsonPath("$.items[0].enabled").value(true))
                .andExpect(jsonPath("$.items[0].accountType").value("REAL"))
                .andExpect(jsonPath("$.items[0].createdAt").exists());
    }

    @Test
    void adminHonoursThePageSizeParam() throws Exception {
        User bob = new User("bob-uid", "bob@example.com", "Bob");
        userRepository.save(bob);

        mvc.perform(get(USERS_PATH).param("size", "1").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void adminGetsASingleUserById() throws Exception {
        mvc.perform(get(USERS_PATH + "/" + aliceId).header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceId.toString()))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.displayName").value("Alice"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.accountType").value("REAL"));
    }

    @Test
    void adminGettingAnUnknownUserGets404ProblemDetail() throws Exception {
        mvc.perform(get(USERS_PATH + "/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"));
    }

    // --- Admin mutations: role ------------------------------------------------------------

    @Test
    void adminChangesRoleAndItPersists() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + aliceId + "/role")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceId.toString()))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        // Persisted, not just echoed.
        assertThat(userRepository.findById(aliceId).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void adminChangingToAnInvalidRoleGets400() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + aliceId + "/role")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPERADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"));

        // Unchanged on a rejected request.
        assertThat(userRepository.findById(aliceId).orElseThrow().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void adminChangingRoleWithMissingFieldGets400() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + aliceId + "/role")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void adminChangingRoleOfUnknownUserGets404() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + UUID.randomUUID() + "/role")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // --- Admin mutations: enabled ---------------------------------------------------------

    @Test
    void adminDisablesAUserAndItPersists() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + aliceId + "/enabled")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceId.toString()))
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(userRepository.findById(aliceId).orElseThrow().isEnabled()).isFalse();
    }

    @Test
    void adminReenablesAUserAndItPersists() throws Exception {
        // Start disabled, then flip back on.
        User disabled = userRepository.findById(aliceId).orElseThrow();
        disabled.setEnabled(false);
        userRepository.save(disabled);

        mvc.perform(patch(USERS_PATH + "/" + aliceId + "/enabled")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        assertThat(userRepository.findById(aliceId).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void adminSettingEnabledWithMissingFieldGets400() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + aliceId + "/enabled")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void adminSettingEnabledOnUnknownUserGets404() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + UUID.randomUUID() + "/enabled")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void seededTargetKeepsItsDefaultAccountType() {
        // Guards the DTO mapping's accountType default against silent drift.
        assertThat(userRepository.findById(aliceId).orElseThrow().getAccountType())
                .isEqualTo(AccountType.REAL);
    }

    // --- OSK-85: view + edit another user's profile fields --------------------------------

    private static final String PROFILE_SUFFIX = "/profile";

    @Test
    void adminGetOneReturnsTheFullProfileFields() throws Exception {
        // Seed Alice with a full profile so the detail endpoint has something to expose.
        User alice = userRepository.findById(aliceId).orElseThrow();
        alice.setFirstName("Alice");
        alice.setLastName("Anderson");
        alice.setCity("London");
        alice.setAge(29);
        alice.setPhone("+15551230000");
        alice.setNotificationPreference(NotificationPreference.PUSH);
        alice.setTimezone("Europe/London");
        alice.setLocale("en-GB");
        userRepository.save(alice);

        mvc.perform(get(USERS_PATH + "/" + aliceId).header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceId.toString()))
                // The OSK-71 summary fields are still present…
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.accountType").value("REAL"))
                // …plus the OSK-67 profile fields (OSK-85).
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.lastName").value("Anderson"))
                .andExpect(jsonPath("$.city").value("London"))
                .andExpect(jsonPath("$.age").value(29))
                .andExpect(jsonPath("$.phone").value("+15551230000"))
                .andExpect(jsonPath("$.notificationPreference").value("PUSH"))
                .andExpect(jsonPath("$.timezone").value("Europe/London"))
                .andExpect(jsonPath("$.locale").value("en-GB"));
    }

    @Test
    void adminEditsAnotherUsersProfilePersistsReflectsInResponseAndRecordsAudit() throws Exception {
        String body =
                """
                {
                  "displayName": "Alice A.",
                  "firstName": "Alice",
                  "lastName": "Anderson",
                  "city": "Paris",
                  "age": 34,
                  "phone": "+15559998888",
                  "notificationPreference": "PUSH",
                  "timezone": "Europe/Paris",
                  "locale": "fr-FR"
                }
                """;

        // The response echoes the persisted detail (so the web console can reflect the update).
        mvc.perform(patch(USERS_PATH + "/" + aliceId + PROFILE_SUFFIX)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(aliceId.toString()))
                .andExpect(jsonPath("$.displayName").value("Alice A."))
                .andExpect(jsonPath("$.firstName").value("Alice"))
                .andExpect(jsonPath("$.city").value("Paris"))
                .andExpect(jsonPath("$.age").value(34))
                .andExpect(jsonPath("$.phone").value("+15559998888"))
                .andExpect(jsonPath("$.notificationPreference").value("PUSH"))
                .andExpect(jsonPath("$.timezone").value("Europe/Paris"))
                .andExpect(jsonPath("$.locale").value("fr-FR"));

        // Persisted to the real database, not just echoed.
        User persisted = userRepository.findById(aliceId).orElseThrow();
        assertThat(persisted.getDisplayName()).isEqualTo("Alice A.");
        assertThat(persisted.getCity()).isEqualTo("Paris");
        assertThat(persisted.getAge()).isEqualTo(34);
        assertThat(persisted.getNotificationPreference()).isEqualTo(NotificationPreference.PUSH);
        assertThat(persisted.getTimezone()).isEqualTo("Europe/Paris");
        assertThat(persisted.getLocale()).isEqualTo("fr-FR");

        // A single PROFILE_EDITED audit event, actor = the admin, target = the edited user, with
        // the changed fields captured in metadata as {field: {old, new}}.
        List<AuditEvent> events = auditService
                .findEvents(aliceId.toString(), AuditAction.PROFILE_EDITED, PageRequest.of(0, 10))
                .getContent();
        assertThat(events).hasSize(1);
        AuditEvent event = events.get(0);
        assertThat(event.getActorFirebaseUid()).isEqualTo("admin-uid");
        assertThat(event.getTargetId()).isEqualTo(aliceId.toString());
        Map<String, Object> metadata = event.getMetadata();
        assertThat(metadata).containsKeys("city", "age", "notificationPreference");
        // Alice started with displayName "Alice" and null city, so the {old, new} is captured.
        @SuppressWarnings("unchecked")
        Map<String, Object> cityDelta = (Map<String, Object>) metadata.get("city");
        assertThat(cityDelta.get("old")).isNull();
        assertThat(cityDelta.get("new")).isEqualTo("Paris");
    }

    @Test
    void adminEditProfileIsSparseAndSkipsUnchangedValues() throws Exception {
        // First set city only.
        mvc.perform(patch(USERS_PATH + "/" + aliceId + PROFILE_SUFFIX)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Berlin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Berlin"));

        // A second PATCH that re-submits the SAME city plus a new firstName: city is unchanged
        // (skipped, no audit delta), firstName is a genuine change.
        mvc.perform(patch(USERS_PATH + "/" + aliceId + PROFILE_SUFFIX)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Berlin\",\"firstName\":\"Ada\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Berlin"))
                .andExpect(jsonPath("$.firstName").value("Ada"));

        assertThat(userRepository.findById(aliceId).orElseThrow().getFirstName())
                .isEqualTo("Ada");

        // Two PROFILE_EDITED events (one per PATCH that changed something); the second event's
        // metadata carries firstName but NOT city (unchanged → no delta).
        List<AuditEvent> events = auditService
                .findEvents(aliceId.toString(), AuditAction.PROFILE_EDITED, PageRequest.of(0, 10))
                .getContent();
        assertThat(events).hasSize(2);
    }

    @Test
    void adminEditProfileNoGenuineChangeIsANoOpWithoutAudit() throws Exception {
        // Alice's seeded displayName is already "Alice"; re-submitting it changes nothing.
        mvc.perform(patch(USERS_PATH + "/" + aliceId + PROFILE_SUFFIX)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice"));

        // No genuine change → no audit event recorded.
        assertThat(auditService
                        .findEvents(aliceId.toString(), AuditAction.PROFILE_EDITED, PageRequest.of(0, 10))
                        .getTotalElements())
                .isZero();
    }

    @Test
    void nonAdminEditingProfileIsForbidden403AndLeavesTheUserUntouched() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + aliceId + PROFILE_SUFFIX)
                        .header(HttpHeaders.AUTHORIZATION, bearer(USER_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Rome\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));

        assertThat(userRepository.findById(aliceId).orElseThrow().getCity()).isNull();
    }

    @Test
    void adminEditingProfileOfUnknownUserGets404() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + UUID.randomUUID() + PROFILE_SUFFIX)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\":\"Rome\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void adminEditingProfileWithOutOfRangeAgeGets400AndPersistsNothing() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + aliceId + PROFILE_SUFFIX)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"age\": 200}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("age")));

        // Rejected request touched nothing.
        assertThat(userRepository.findById(aliceId).orElseThrow().getAge()).isNull();
    }

    @Test
    void adminEditingProfileWithInvalidTimezoneGets400() throws Exception {
        mvc.perform(patch(USERS_PATH + "/" + aliceId + PROFILE_SUFFIX)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_TOKEN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timezone\": \"Mars/Phobos\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("timezone")));
    }
}
