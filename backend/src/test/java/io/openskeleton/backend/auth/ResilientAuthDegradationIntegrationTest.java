package io.openskeleton.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance test for OSK-65: proves a slow/failing verifier yields a TIMELY degraded
 * {@code 503} through the real MVC stack — not a hung request — while an ordinary
 * invalid token still returns {@code 401}.
 *
 * <p>The full stack (the real {@link ResilientTokenVerifier} + auth filter +
 * error rendering) runs; ONLY the live Firebase adapter — the concrete
 * {@link FirebaseTokenVerifier} that the resilient wrapper delegates to — is mocked,
 * so we can make it sleep beyond the timeout or throw repeatedly at will. Because the
 * mock replaces the <i>delegate</i> (not the primary resilient bean), the resilience
 * pipeline is genuinely exercised end-to-end.
 *
 * <p>Timeouts/backoff are shortened via {@link TestPropertySource} so the suite stays
 * fast; the assertions still prove the same behaviour that the production 2s timeout
 * gives.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            // Snappy, test-sized resilience knobs (production uses 2s / 3 attempts).
            "resilience4j.timelimiter.instances.tokenVerifier.timeout-duration=250ms",
            "resilience4j.retry.instances.tokenVerifier.max-attempts=2",
            "resilience4j.retry.instances.tokenVerifier.wait-duration=20ms",
            // Keep the breaker from tripping mid-test so each case is independent.
            "resilience4j.circuitbreaker.instances.tokenVerifier.minimum-number-of-calls=100"
        })
class ResilientAuthDegradationIntegrationTest {

    @Autowired
    private MockMvc mvc;

    /**
     * Mock the DELEGATE adapter, not the resilient wrapper — the real
     * {@link ResilientTokenVerifier} (the primary bean) still wraps it, so the
     * TimeLimiter/Retry/CircuitBreaker all run.
     */
    @MockBean
    private FirebaseTokenVerifier delegate;

    @Test
    void hungVerifierYieldsTimely503_notAHang() throws Exception {
        // The dependency hangs far beyond the 250ms timeout.
        when(delegate.verify(any())).thenAnswer(inv -> {
            Thread.sleep(30_000);
            return new VerifiedToken("uid", "user@example.com");
        });

        long start = System.nanoTime();
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer hangs"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.title").value("Service Unavailable"))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        // The whole request came back in well under the 30s the delegate would sleep:
        // the timeout fired and we failed fast rather than hanging.
        assertThat(elapsedMs).isLessThan(5_000);
    }

    @Test
    void repeatedlyFailingVerifierYields503() throws Exception {
        when(delegate.verify(any())).thenThrow(new RuntimeException("firebase unreachable"));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer transient"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.title").value("Service Unavailable"));
    }

    @Test
    void invalidTokenStill401_notDegradedTo503() throws Exception {
        // A genuine rejection must NOT be turned into a 503 by the resilience layer.
        when(delegate.verify(any())).thenThrow(new TokenVerificationException("expired"));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer bad"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void healthyVerifierStill200() throws Exception {
        // Sanity: the resilience wrapper is transparent on the happy path.
        when(delegate.verify(any())).thenReturn(new VerifiedToken("uid-9", "ok@example.com"));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("uid-9"))
                .andExpect(jsonPath("$.email").value("ok@example.com"));
    }
}
