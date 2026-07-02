package io.openskeleton.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates every {@code /api/v1/**} request by verifying the caller's Firebase
 * ID token (OSK-28).
 *
 * <p><b>What it does:</b> for a protected request it reads the
 * {@code Authorization: Bearer <token>} header, hands the raw token to the injected
 * {@link TokenVerifier}, and — on success — stashes the caller's {@code uid}/{@code
 * email} and their Spring authorities ({@code ROLE_USER}/{@code ROLE_ADMIN}, mapped
 * from the Firebase {@code role} custom claim — OSK-79) as request attributes so
 * downstream handlers (e.g. {@code /api/v1/me}) can read them. On a missing/malformed
 * header or a token that fails verification it
 * short-circuits the chain with an RFC 7807 {@code 401} in the same
 * {@code application/problem+json} shape the rest of the API uses (OSK-39 /
 * {@code GlobalExceptionHandler}).
 *
 * <p><b>Why a plain {@link OncePerRequestFilter} and not Spring Security:</b> the
 * whole security requirement here is "verify one bearer token on one path prefix".
 * Pulling in {@code spring-boot-starter-security} would switch on a full filter chain
 * and default behaviours across the app for no benefit; a single, explicit filter is
 * smaller, easier to reason about, and matches the existing {@code
 * SecurityHeadersFilter} approach in this codebase.
 *
 * <p><b>Why it is scoped to {@code /api/v1/**} (default-deny for the API, not the
 * whole app):</b> the permit-list is expressed by <i>not</i> filtering anything else.
 * {@link #shouldNotFilter} skips every non-{@code /api/v1/} path, which is exactly the
 * set that must stay public: the deploy liveness probe ({@code /health}), Actuator
 * ({@code /actuator/health}) and the OpenAPI docs ({@code /swagger-ui},
 * {@code /v3/api-docs}). Everything under the versioned API surface, by contrast,
 * requires a valid token — so a newly added API controller is authenticated by
 * default and cannot become an "accidentally public" endpoint.
 *
 * <p><b>Why it is registered via {@code @Bean} (see {@code FirebaseAuthConfig})
 * rather than {@code @Component}:</b> it depends on the {@link TokenVerifier} bean,
 * which is a plain {@code @Component} service. A {@code @Component} {@code Filter}
 * gets pulled into {@code @WebMvcTest} web slices (which do NOT load service
 * components), breaking those slices with a missing-dependency error. Declaring it as
 * a {@code @Bean} in a user {@code @Configuration} keeps it out of the slices (they
 * do not load user config) while every {@code @SpringBootTest} and production run
 * wires it normally.
 */
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthenticationFilter.class);

    /** The versioned API surface this filter guards; every other path is left public. */
    static final String PROTECTED_PATH_PREFIX = "/api/v1/";

    /** Standard bearer-token scheme prefix (note the trailing space) in the {@code Authorization} header. */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Request attribute under which the verified Firebase {@code uid} is published
     * for downstream handlers. Public so controllers (e.g. {@code MeController}) can
     * read the same key without duplicating the string.
     */
    public static final String UID_ATTRIBUTE = "io.openskeleton.auth.uid";

    /** Request attribute for the verified caller's email (may be absent on the token → {@code null}). */
    public static final String EMAIL_ATTRIBUTE = "io.openskeleton.auth.email";

    /**
     * Request attribute under which the caller's Spring authorities are published
     * (OSK-79). The value is an immutable {@code List<GrantedAuthority>} carrying the
     * caller's role as {@code ROLE_USER} / {@code ROLE_ADMIN}, mapped from the Firebase
     * {@code role} custom claim (defaulting to {@code ROLE_USER} when the claim is
     * absent). Downstream handlers and later authorization logic (OSK-71) read this key
     * to decide what the caller may do, without re-deriving it from the token.
     */
    public static final String AUTHORITIES_ATTRIBUTE = "io.openskeleton.auth.authorities";

    private final TokenVerifier tokenVerifier;
    private final ObjectMapper objectMapper;

    /**
     * @param tokenVerifier the seam that actually validates the token (real Firebase
     *     adapter in prod; a mock in tests)
     * @param objectMapper the Spring-managed mapper — it carries the {@code
     *     ProblemDetail} Jackson mixin, so serialising the 401 yields correct
     *     {@code problem+json}
     */
    public FirebaseAuthenticationFilter(TokenVerifier tokenVerifier, ObjectMapper objectMapper) {
        this.tokenVerifier = tokenVerifier;
        this.objectMapper = objectMapper;
    }

    /**
     * Run the filter ONLY for the protected API surface. Returning {@code true} here
     * makes the request bypass this filter entirely, which is how the public
     * permit-list (health, actuator, swagger/openapi) is realised — those paths never
     * see a token requirement.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(PROTECTED_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        // 1) No credentials presented (missing header or not a Bearer token) → 401.
        //    We reject before touching the verifier so an unauthenticated probe never
        //    incurs a Firebase round-trip.
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response, "Missing or malformed Authorization header; expected 'Bearer <token>'.");
            return;
        }

        String idToken = header.substring(BEARER_PREFIX.length()).trim();
        if (idToken.isEmpty()) {
            writeUnauthorized(response, "Empty bearer token.");
            return;
        }

        // 2) Verify the token via the seam. Two failure modes are handled distinctly:
        //    a bad token is a normal 401; a dependency that is slow/tripped/unreachable
        //    is a degraded 503 (OSK-65) — never a hang, and never mislabelled as a 401.
        VerifiedToken verified;
        try {
            verified = tokenVerifier.verify(idToken);
        } catch (TokenVerificationException e) {
            // Log at debug only: invalid tokens are attacker-controlled and noisy, and
            // the reason must not be echoed verbatim to the caller.
            log.debug("Firebase ID token verification failed: {}", e.getMessage());
            writeUnauthorized(response, "Invalid or expired authentication token.");
            return;
        } catch (AuthDependencyUnavailableException e) {
            // Infrastructure fault surfaced by the resilience layer (verify timed out,
            // the circuit-breaker is open, or bounded retries were exhausted). Fail fast
            // with a degraded 503 rather than hanging the request or masking it as 401.
            log.warn("Token verification dependency degraded; returning 503: {}", e.getMessage());
            writeServiceUnavailable(response, "Authentication is temporarily unavailable. Please retry shortly.");
            return;
        }

        // 3) Authenticated. Publish the identity + authorities for downstream handlers
        //    and continue. The role custom claim (resolved on the verified principal by
        //    RoleClaimMapper) is mapped to a Spring ROLE_* authority here (OSK-79), so
        //    the request carries who the caller is AND what role they hold — with the
        //    least-privilege ROLE_USER default already applied when the claim is absent.
        request.setAttribute(UID_ATTRIBUTE, verified.uid());
        request.setAttribute(EMAIL_ATTRIBUTE, verified.email());
        request.setAttribute(AUTHORITIES_ATTRIBUTE, RoleClaimMapper.authoritiesFrom(verified.role()));
        filterChain.doFilter(request, response);
    }

    /**
     * Writes an RFC 7807 {@code 401} response (media type
     * {@code application/problem+json}) and commits it — the request does NOT continue
     * down the chain. The body mirrors {@code GlobalExceptionHandler}'s shape (a
     * {@link ProblemDetail} with a {@code title} + {@code detail}) so clients get a
     * uniform error model whether the 401 comes from here or a 4xx/5xx from a handler.
     */
    private void writeUnauthorized(HttpServletResponse response, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setTitle("Unauthorized");

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }

    /**
     * Writes an RFC 7807 {@code 503} response (media type
     * {@code application/problem+json}) for the <i>degraded</i> case (OSK-65): the
     * verification dependency is unavailable, so we fail fast instead of hanging. Same
     * {@link ProblemDetail} shape as the 401 path, plus a {@code Retry-After} hint so
     * clients (and probes) back off before retrying the sick dependency.
     */
    private void writeServiceUnavailable(HttpServletResponse response, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail);
        problem.setTitle("Service Unavailable");

        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, "5");
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
