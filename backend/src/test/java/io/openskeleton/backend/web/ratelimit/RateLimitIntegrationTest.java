package io.openskeleton.backend.web.ratelimit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
 * End-to-end acceptance test for the API rate limiter (OSK-63), exercising the real
 * MVC stack — the {@link RateLimitFilter} runs in the actual servlet chain <i>after</i>
 * the Firebase auth filter, with only the {@link TokenVerifier} seam mocked (so no
 * live Firebase credentials are needed, exactly as {@code FirebaseAuthIntegrationTest}
 * does).
 *
 * <p>Limits are tightened to a burst of 2 with an effectively-infinite refill period
 * via {@code @SpringBootTest(properties=...)} so the over-limit boundary is reached in
 * three requests without any timing flakiness. This unique property set also gives the
 * test its own cached context, so its buckets never interfere with other tests.
 *
 * <p>Covers the acceptance criteria:
 * <ul>
 *   <li><b>Under limit passes, over limit 429</b> — with the required {@code
 *       Retry-After} header and RFC 7807 {@code application/problem+json} body.</li>
 *   <li><b>Keyed by uid, not IP</b> — the same authenticated user hitting from three
 *       different {@code X-Forwarded-For} IPs still shares one bucket and is throttled,
 *       which simultaneously proves the limiter runs after auth (the verified {@code
 *       uid} is available as the bucket key).</li>
 *   <li><b>Exempt paths</b> — {@code /actuator/health/readiness} is never limited.</li>
 * </ul>
 */
@SpringBootTest(
        properties = {
            "app.rate-limit.enabled=true",
            "app.rate-limit.capacity=2",
            "app.rate-limit.refill-tokens=2",
            "app.rate-limit.refill-period=1h"
        })
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mvc;

    /** Mock the verification seam so any bearer token is accepted as a known user. */
    @MockBean
    private TokenVerifier tokenVerifier;

    @Test
    void underLimitRequestsPassThenOverLimitReturns429WithRetryAfterAndProblemJson() throws Exception {
        when(tokenVerifier.verify(anyString())).thenReturn(new VerifiedToken("uid-limit", "limit@example.com"));

        // Capacity is 2 → the first two requests from this client succeed.
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer token-limit"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer token-limit"))
                .andExpect(status().isOk());

        // The third is over the limit → 429 with Retry-After + RFC 7807 problem+json.
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer token-limit"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void sameUserFromDifferentIpsSharesOneBucket() throws Exception {
        when(tokenVerifier.verify(anyString())).thenReturn(new VerifiedToken("uid-shared", "shared@example.com"));

        // Three DIFFERENT client IPs but the SAME authenticated uid. If the limiter
        // keyed on IP (or ran before auth so uid were unavailable) each IP would get
        // its own bucket and none would be throttled. Because it keys on uid, all
        // three share one capacity-2 bucket and the third is rejected — this asserts
        // both the uid keying AND that the filter runs after the auth filter.
        mvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token-shared")
                        .header("X-Forwarded-For", "198.51.100.1"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token-shared")
                        .header("X-Forwarded-For", "198.51.100.2"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token-shared")
                        .header("X-Forwarded-For", "198.51.100.3"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void exemptActuatorHealthIsNeverRateLimited() throws Exception {
        // Well beyond the capacity-2 limit, but /actuator/** (health, readiness, ...)
        // is not under /api/ → exempt, so every request keeps returning 200.
        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }
    }
}
