package io.openskeleton.backend.web.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openskeleton.backend.auth.FirebaseAuthenticationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client token-bucket rate limiter for the API surface (OSK-63) — abuse/DoS
 * protection that caps how fast any one caller may hit {@code /api/**}.
 *
 * <p><b>Scope / exemptions:</b> {@link #shouldNotFilter} restricts the filter to
 * paths under {@code /api/} and skips everything else, which is exactly how the
 * required exemptions are realised — {@code /health} (deploy liveness probe),
 * {@code /actuator/**} (ops surface, including {@code /actuator/health/readiness})
 * and the OpenAPI docs are all outside {@code /api/} and therefore never rate
 * limited. The master {@code app.rate-limit.enabled} flag can also switch the whole
 * filter off.
 *
 * <p><b>Keying (per-client bucket):</b> requests are bucketed by the authenticated
 * Firebase {@code uid} when present (published as a request attribute by {@link
 * FirebaseAuthenticationFilter}), falling back to the client IP for unauthenticated
 * callers. Because this backend runs behind a proxy (Cloud Run), the client IP is
 * taken from the first hop of {@code X-Forwarded-For} when present, else
 * {@code request.getRemoteAddr()}. Keying on uid means a single user cannot multiply
 * their quota by rotating source IPs, while anonymous traffic is still capped per IP.
 *
 * <p><b>Filter order (why it runs AFTER auth):</b> registered as a plain {@code
 * Filter} {@code @Bean} in {@link RateLimitConfig} — mirroring {@link
 * io.openskeleton.backend.auth.FirebaseAuthConfig} — and deliberately positioned to
 * run <i>after</i> the auth filter so the verified {@code uid} attribute is already
 * on the request and can be used as the bucket key. Unauthenticated {@code
 * /api/v1/**} requests are rejected (401) by the auth filter before reaching here;
 * any {@code /api/**} path that is not authenticated simply keys by IP.
 *
 * <p><b>Over-limit response:</b> a denied request gets HTTP {@code 429} with a {@code
 * Retry-After} header and an RFC 7807 {@code application/problem+json} body, matching
 * the shape produced by {@code GlobalExceptionHandler} / the auth filter (OSK-39) so
 * clients see one uniform error model.
 *
 * <p><b>Why not {@code @Component}:</b> a component-scanned {@code Filter} is pulled
 * into {@code @WebMvcTest} slices; declaring it as a {@code @Bean} in user config
 * keeps it out of those slices (which don't load user config) while every {@code
 * @SpringBootTest} and production run wires it normally — the same reasoning as the
 * auth filter.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** Only requests under this prefix are rate limited; everything else is exempt. */
    static final String RATE_LIMITED_PATH_PREFIX = "/api/";

    /** Proxy header carrying the original client IP (Cloud Run sits in front of the app). */
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * One {@link TokenBucket} per client key. A {@code ConcurrentHashMap} lets
     * different clients be limited independently without global locking; each bucket
     * serialises only the concurrent requests for its own key.
     */
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param properties the env-bound limits (capacity / refill rate / enabled flag)
     * @param objectMapper the Spring-managed mapper (carries the {@code ProblemDetail}
     *     Jackson mixin) used to render the 429 body as {@code problem+json}
     */
    public RateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Filter only the {@code /api/**} surface, and only when limiting is enabled.
     * Returning {@code true} bypasses the filter entirely — which is how {@code
     * /health}, {@code /actuator/**} and readiness stay exempt (they are not under
     * {@code /api/}), and how the {@code enabled=false} kill switch works.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return path == null || !path.startsWith(RATE_LIMITED_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String key = clientKey(request);
        TokenBucket bucket = buckets.computeIfAbsent(
                key,
                k -> new TokenBucket(
                        properties.getCapacity(),
                        properties.getRefillTokens(),
                        properties.getRefillPeriod().toNanos(),
                        System.nanoTime()));

        TokenBucket.Decision decision = bucket.tryConsume(System.nanoTime());
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }
        writeTooManyRequests(response, decision.retryAfterSeconds());
    }

    /**
     * Builds the bucket key for this request: the authenticated {@code uid} when the
     * auth filter has published one, otherwise the client IP. The {@code uid:} /
     * {@code ip:} prefixes keep the two namespaces from ever colliding (an IP literal
     * can never be mistaken for a uid bucket and vice-versa).
     */
    private static String clientKey(HttpServletRequest request) {
        Object uid = request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        if (uid instanceof String s && !s.isBlank()) {
            return "uid:" + s;
        }
        return "ip:" + clientIp(request);
    }

    /**
     * Resolves the caller's IP. Behind Cloud Run the socket peer is the proxy, so the
     * real client is the first hop of {@code X-Forwarded-For} (the left-most entry);
     * when the header is absent (e.g. a direct/local request) we fall back to the
     * socket address.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader(X_FORWARDED_FOR);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int comma = forwardedFor.indexOf(',');
            String firstHop = (comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor).trim();
            if (!firstHop.isBlank()) {
                return firstHop;
            }
        }
        return request.getRemoteAddr();
    }

    /**
     * Writes the RFC 7807 {@code 429} response: a {@code Retry-After} header plus a
     * {@link ProblemDetail} body in {@code application/problem+json}, mirroring the
     * uniform error shape used elsewhere in the API (OSK-39).
     */
    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded. Retry after " + retryAfterSeconds + " second(s).");
        problem.setTitle("Too Many Requests");

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
