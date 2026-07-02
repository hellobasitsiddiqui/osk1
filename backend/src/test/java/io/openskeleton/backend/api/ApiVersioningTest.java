package io.openskeleton.backend.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openskeleton.backend.auth.TokenVerifier;
import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the {@code /api/v1} versioning convention end-to-end (OSK-18).
 *
 * <p>Uses a full {@code @SpringBootTest} context (not a web slice) on purpose: the
 * {@code /api/v1} prefix is applied by {@link ApiVersioningConfig#configurePathMatch}
 * via the shared {@code WebMvcConfigurer} path-match hook, so it is only exercised
 * when the real MVC config is loaded. A sliced test could miss a broken prefix.
 *
 * <p>The two assertions capture the whole contract:
 * <ul>
 *   <li>the sample API endpoint IS versioned ({@code GET /api/v1/info} → 200), and</li>
 *   <li>the deploy liveness probe is NOT versioned ({@code GET /health} → 200),
 *       proving the prefix is scoped to the {@code api} package and does not leak
 *       onto operational endpoints Docker/Cloud Run curl.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiVersioningTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * The versioned API is now default-deny (OSK-28): {@code /api/v1/**} requires a
     * verified Firebase token. We mock the {@link TokenVerifier} seam so this test
     * stays focused on the routing/prefix convention rather than real token
     * verification (which needs live Firebase credentials).
     */
    @MockBean
    private TokenVerifier tokenVerifier;

    @BeforeEach
    void acceptTestToken() throws Exception {
        // Treat any presented bearer token as a valid caller for this routing test.
        when(tokenVerifier.verify(anyString())).thenReturn(new VerifiedToken("test-uid", "test@example.com"));
    }

    /** The API surface is served under the version prefix and returns its JSON contract. */
    @Test
    void apiInfoIsServedUnderVersionPrefix() throws Exception {
        // /api/v1/info is under the protected surface, so it now needs a token — send a
        // (mock-accepted) one so this test verifies the prefix, not the auth outcome.
        mockMvc.perform(get("/api/v1/info").header(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("OpenSkeleton Backend"))
                .andExpect(jsonPath("$.apiVersion").value("v1"));
    }

    /** The liveness probe stays at a stable, unversioned path for deploy health checks. */
    @Test
    void healthProbeIsNotVersioned() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
