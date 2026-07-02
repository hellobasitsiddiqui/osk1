package io.openskeleton.backend.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

    /** The API surface is served under the version prefix and returns its JSON contract. */
    @Test
    void apiInfoIsServedUnderVersionPrefix() throws Exception {
        mockMvc.perform(get("/api/v1/info"))
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
