package io.openskeleton.backend.api;

import io.openskeleton.backend.auth.FirebaseAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the authenticated caller's identity: {@code GET /api/v1/me} →
 * {@code {"uid": ..., "email": ..., "displayName": ..., "role": ...}} (OSK-28, OSK-66).
 *
 * <p><b>Why it exists:</b> it is the concrete round-trip target that proves the
 * Firebase auth seam works end-to-end — a client presents its ID token and gets back
 * the identity the backend verified from it (the TM-130 target). It lives in the
 * {@code io.openskeleton.backend.api} package, so it inherits the {@code /api/v1}
 * prefix (see {@link ApiVersioningConfig}) and, being under {@code /api/v1/**}, is
 * authenticated by {@link FirebaseAuthenticationFilter} — this handler therefore only
 * ever runs for an already-verified caller.
 *
 * <p><b>Why read from request attributes (not Spring Security):</b> we intentionally
 * do NOT pull in Spring Security. The filter publishes the verified identity as
 * request attributes; this controller reads them back, read-only. Reaching the handler
 * at all means the token was valid, so {@code uid} is always present here.
 */
@RestController
public class MeController {

    /**
     * Default role returned until RBAC role claims (OSK-79 / spec 2.3) land. The
     * verified principal does not yet carry a role claim, so every authenticated caller
     * is reported as a plain {@code USER}. This controller deliberately invents no role
     * mechanics of its own — once the token verifier exposes a role claim, {@link #me}
     * will read it here instead of defaulting.
     */
    static final String DEFAULT_ROLE = "USER";

    /**
     * The identity of the current caller.
     *
     * @param uid         the verified Firebase user id (always present — the request only
     *     reaches here after successful verification)
     * @param email       the caller's email, or {@code null} if the token carried none
     *     (e.g. anonymous/phone-only sign-in)
     * @param displayName the caller's display name, or {@code null} — the verified
     *     principal does not yet expose it (the {@link FirebaseAuthenticationFilter}
     *     publishes only {@code uid}/{@code email}); OSK-79 will surface the remaining
     *     token claims and this field will populate then, without changing this shape
     * @param role        the caller's role; currently always {@link #DEFAULT_ROLE}
     *     ({@code USER}) until RBAC role claims land (OSK-79 / spec 2.3)
     */
    public record CurrentUser(String uid, String email, String displayName, String role) {}

    /**
     * Served at {@code /api/v1/me}. Reads the identity the
     * {@link FirebaseAuthenticationFilter} stashed on the request after verifying the
     * bearer token.
     *
     * <p>{@code uid} and {@code email} are read back from the filter's request
     * attributes (its verified-principal contract). {@code displayName} is not exposed
     * by that contract yet, so it is reported as {@code null} rather than re-decoding
     * the token here (which would duplicate — and risk diverging from — the filter's
     * verification). {@code role} defaults to {@link #DEFAULT_ROLE} until RBAC lands.
     */
    @GetMapping("/me")
    public CurrentUser me(HttpServletRequest request) {
        String uid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        String email = (String) request.getAttribute(FirebaseAuthenticationFilter.EMAIL_ATTRIBUTE);
        // displayName is not (yet) part of the filter's published verified-principal
        // contract; surface it as null until OSK-79 exposes the additional claims.
        String displayName = null;
        return new CurrentUser(uid, email, displayName, DEFAULT_ROLE);
    }
}
