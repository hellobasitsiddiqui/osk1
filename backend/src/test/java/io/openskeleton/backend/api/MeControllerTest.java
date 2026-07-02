package io.openskeleton.backend.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openskeleton.backend.auth.TokenVerifier;
import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AC test for {@code GET /api/v1/me} returning the verified caller identity (OSK-66).
 *
 * <p>Drives the real MVC stack — the {@link io.openskeleton.backend.auth.FirebaseAuthenticationFilter}
 * plus {@link MeController} — with ONLY the {@link TokenVerifier} seam mocked, exactly
 * as the OSK-28 auth integration test does (a live Firebase project and a genuinely
 * signed token are what the seam abstracts away). A full {@code @SpringBootTest} is
 * used deliberately so the auth filter runs in the real chain and publishes the
 * verified principal the controller reads back.
 *
 * <p>Covers both AC cases for the endpoint's identity contract:
 * <ul>
 *   <li><b>authenticated → 200</b> with the full identity
 *       ({@code uid}, {@code email}, {@code displayName}, {@code role}) derived from the
 *       verified principal.</li>
 *   <li><b>anonymous → 401</b> in the standard RFC 7807 {@code problem+json} shape
 *       enforced by the Epic-1 auth filter.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeControllerTest {

    private static final String VALID_TOKEN = "valid-token";

    @Autowired
    private MockMvc mvc;

    /** Mock the verification seam so no live Firebase project/credentials are needed. */
    @MockBean
    private TokenVerifier tokenVerifier;

    @Test
    void authenticatedCallerGets200WithVerifiedIdentity() throws Exception {
        // The (mocked) verifier accepts the token and returns the verified principal
        // that the filter then publishes for the controller to read back.
        when(tokenVerifier.verify(eq(VALID_TOKEN))).thenReturn(new VerifiedToken("uid-123", "alice@example.com"));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.uid").value("uid-123"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                // displayName is not yet exposed by the verified principal → null until OSK-79.
                .andExpect(jsonPath("$.displayName").doesNotExist())
                // role defaults to USER until RBAC role claims land (OSK-79 / spec 2.3).
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void tokenWithoutEmailStillReturnsUidAndDefaultRole() throws Exception {
        // Anonymous/phone-only sign-in: a valid token carrying a uid but no email.
        when(tokenVerifier.verify(eq(VALID_TOKEN))).thenReturn(new VerifiedToken("uid-456", null));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("uid-456"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void anonymousCallerGets401ProblemDetail() throws Exception {
        // No Authorization header at all → the Epic-1 auth filter short-circuits with a
        // 401 in the standard RFC 7807 problem+json shape, before any handler runs.
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }
}
