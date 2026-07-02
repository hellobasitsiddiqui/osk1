package io.openskeleton.backend.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openskeleton.backend.auth.FirebaseAuthenticationFilter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Unit tests for {@link RateLimitFilter} driven through the real {@code doFilter}
 * entry point (so {@code shouldNotFilter} is exercised too), using Spring's mock
 * servlet objects (OSK-63). No web server or Spring context is started — the filter
 * is instantiated directly with a small {@link RateLimitProperties}, which keeps the
 * key/keying/limit/exemption logic fast and deterministic. The Jackson mapper is
 * built the same way Spring Boot builds the application mapper (carries the {@code
 * ProblemDetail} mixin) so the rendered 429 body matches production.
 */
class RateLimitFilterTest {

    /** Mirror of Boot's application ObjectMapper so ProblemDetail renders as real problem+json. */
    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

    /** Small limits so the over-limit case is reached in a couple of requests. */
    private static RateLimitProperties props(boolean enabled, long capacity) {
        RateLimitProperties p = new RateLimitProperties();
        p.setEnabled(enabled);
        p.setCapacity(capacity);
        p.setRefillTokens(capacity);
        // Long period so no refill happens during a test's few-millisecond run.
        p.setRefillPeriod(Duration.ofHours(1));
        return p;
    }

    private static MockHttpServletRequest apiRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setRequestURI("/api/v1/me");
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    @Test
    void allowsRequestsUpToCapacityThenReturns429WithRetryAfterAndProblemJson() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(props(true, 2), objectMapper);

        // First two requests (same client IP) are under the limit and pass through.
        for (int i = 0; i < 2; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(apiRequest(), response, chain);
            assertThat(chain.getRequest())
                    .as("under-limit request should proceed")
                    .isNotNull();
            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        }

        // Third request from the same client is over the limit → 429, chain NOT invoked.
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(apiRequest(), response, chain);

        assertThat(chain.getRequest())
                .as("over-limit request must be short-circuited")
                .isNull();
        assertThat(response.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(Long.parseLong(response.getHeader(HttpHeaders.RETRY_AFTER))).isGreaterThanOrEqualTo(1L);
        assertThat(response.getContentAsString()).contains("\"status\":429").contains("Too Many Requests");
    }

    @Test
    void isKeyedByAuthenticatedUidWhenPresent() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(props(true, 1), objectMapper);

        // Same uid but DIFFERENT source IPs → still one shared bucket (capacity 1),
        // so the second request is throttled. Proves keying is by uid, not IP.
        MockHttpServletRequest first = apiRequest();
        first.setRemoteAddr("10.0.0.1");
        first.setAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE, "uid-xyz");
        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilter(first, new MockHttpServletResponse(), firstChain);
        assertThat(firstChain.getRequest()).isNotNull();

        MockHttpServletRequest second = apiRequest();
        second.setRemoteAddr("10.0.0.2");
        second.setAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE, "uid-xyz");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilter(second, secondResponse, secondChain);

        assertThat(secondChain.getRequest()).isNull();
        assertThat(secondResponse.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void unauthenticatedClientsAreKeyedByForwardedForFirstHop() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(props(true, 1), objectMapper);

        // No uid attribute; both requests share the same first X-Forwarded-For hop but
        // arrive on different socket peers → they must share a bucket (keyed on the
        // real client IP, i.e. the first hop), so the second is throttled.
        MockHttpServletRequest first = apiRequest();
        first.setRemoteAddr("172.16.0.1");
        first.addHeader("X-Forwarded-For", "198.51.100.23, 172.16.0.1");
        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilter(first, new MockHttpServletResponse(), firstChain);
        assertThat(firstChain.getRequest()).isNotNull();

        MockHttpServletRequest second = apiRequest();
        second.setRemoteAddr("172.16.0.99");
        second.addHeader("X-Forwarded-For", "198.51.100.23, 10.9.9.9");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilter(second, secondResponse, secondChain);

        assertThat(secondChain.getRequest()).isNull();
        assertThat(secondResponse.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
    }

    @Test
    void differentClientIpsGetIndependentBuckets() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(props(true, 1), objectMapper);

        // Two distinct client IPs (no uid) → independent buckets → both allowed even
        // though capacity is 1 each.
        MockHttpServletRequest first = apiRequest();
        first.setRemoteAddr("192.0.2.1");
        MockFilterChain firstChain = new MockFilterChain();
        filter.doFilter(first, new MockHttpServletResponse(), firstChain);
        assertThat(firstChain.getRequest()).isNotNull();

        MockHttpServletRequest second = apiRequest();
        second.setRemoteAddr("192.0.2.2");
        MockFilterChain secondChain = new MockFilterChain();
        filter.doFilter(second, new MockHttpServletResponse(), secondChain);
        assertThat(secondChain.getRequest()).isNotNull();
    }

    @Test
    void nonApiPathsAreExemptFromRateLimiting() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(props(true, 1), objectMapper);

        // /health, /actuator/**, readiness etc. are not under /api/ → never limited.
        // Fire many requests at an exempt path; all must pass through (capacity is 1).
        for (String uri : new String[] {"/health", "/actuator/health/readiness", "/actuator/metrics"}) {
            for (int i = 0; i < 3; i++) {
                MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
                request.setRequestURI(uri);
                request.setRemoteAddr("203.0.113.7");
                MockFilterChain chain = new MockFilterChain();
                filter.doFilter(request, new MockHttpServletResponse(), chain);
                assertThat(chain.getRequest())
                        .as("exempt path %s must not be limited", uri)
                        .isNotNull();
            }
        }
    }

    @Test
    void disabledFilterLetsEveryRequestThrough() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(props(false, 1), objectMapper);

        // enabled=false → the kill switch: even /api/** requests over the notional
        // limit all pass through.
        for (int i = 0; i < 5; i++) {
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(apiRequest(), new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).isNotNull();
        }
    }
}
