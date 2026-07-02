package io.openskeleton.backend.auth;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * End-to-end check of the Firebase auth seam (OSK-28) through the real MVC stack.
 *
 * <p>This is the acceptance-criteria test: it drives the actual
 * {@link FirebaseAuthenticationFilter} + {@code MeController} + error rendering, with
 * ONLY the {@link TokenVerifier} seam mocked (verifying a real Firebase ID token needs
 * live credentials and a genuine signed token — exactly what the seam abstracts away).
 * A full {@code @SpringBootTest} is used deliberately so the filter runs in the real
 * chain rather than a sliced context that might skip it.
 *
 * <p>Covers all three AC cases:
 * <ul>
 *   <li><b>200 valid</b> — a token the (mocked) verifier accepts reaches
 *       {@code /api/v1/me}, which echoes the verified uid/email.</li>
 *   <li><b>401 invalid</b> — a token the verifier rejects (and a missing token) yields
 *       an RFC 7807 {@code 401} in {@code application/problem+json}.</li>
 *   <li><b>200 public</b> — a permit-listed path ({@code /actuator/health}) succeeds
 *       with no token at all.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class FirebaseAuthIntegrationTest {

    private static final String VALID_TOKEN = "valid-token";
    private static final String INVALID_TOKEN = "invalid-token";

    @Autowired
    private MockMvc mvc;

    /** Mock the verification seam so no live Firebase project/credentials are needed. */
    @MockBean
    private TokenVerifier tokenVerifier;

    @Test
    void validTokenReaches200AndExposesUidAndEmail() throws Exception {
        when(tokenVerifier.verify(eq(VALID_TOKEN))).thenReturn(new VerifiedToken("uid-123", "alice@example.com"));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("uid-123"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                // OSK-66: /me now returns the full identity; role defaults to USER until RBAC (OSK-79).
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void invalidTokenIsRejectedWith401ProblemDetail() throws Exception {
        when(tokenVerifier.verify(eq(INVALID_TOKEN))).thenThrow(new TokenVerificationException("expired"));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + INVALID_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void missingTokenIsRejectedWith401ProblemDetail() throws Exception {
        // No Authorization header at all → 401 before the verifier is even consulted.
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void nonBearerSchemeIsRejectedWith401() throws Exception {
        // A present-but-wrong scheme (e.g. Basic) must be treated as unauthenticated,
        // never passed to the verifier as if it were a token.
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void emptyBearerTokenIsRejectedWith401() throws Exception {
        // "Bearer " with no token value is malformed → 401 without consulting the verifier.
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer   "))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void publicActuatorHealthSucceedsWithoutToken() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
