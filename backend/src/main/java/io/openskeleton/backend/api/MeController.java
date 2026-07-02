package io.openskeleton.backend.api;

import io.openskeleton.backend.auth.FirebaseAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Returns the authenticated caller's identity: {@code GET /api/v1/me} →
 * {@code {"uid": ..., "email": ...}} (OSK-28).
 *
 * <p><b>Why it exists:</b> it is the concrete round-trip target that proves the
 * Firebase auth seam works end-to-end — a client presents its ID token and gets back
 * the {@code uid}/{@code email} the backend verified from it (the TM-130 target). It
 * lives in the {@code io.openskeleton.backend.api} package, so it inherits the
 * {@code /api/v1} prefix (see {@link ApiVersioningConfig}) and, being under
 * {@code /api/v1/**}, is authenticated by {@link FirebaseAuthenticationFilter} — this
 * handler therefore only ever runs for an already-verified caller.
 *
 * <p><b>Why read from request attributes (not Spring Security):</b> we intentionally
 * do NOT pull in Spring Security. The filter publishes the verified identity as
 * request attributes; this controller reads them back. Reaching the handler at all
 * means the token was valid, so {@code uid} is always present here.
 */
@RestController
public class MeController {

    /**
     * The identity of the current caller.
     *
     * @param uid   the verified Firebase user id (always present — the request only
     *     reaches here after successful verification)
     * @param email the caller's email, or {@code null} if the token carried none
     *     (e.g. anonymous/phone-only sign-in)
     */
    public record CurrentUser(String uid, String email) {}

    /**
     * Served at {@code /api/v1/me}. Reads the {@code uid}/{@code email} the
     * {@link FirebaseAuthenticationFilter} stashed on the request after verifying the
     * bearer token.
     */
    @GetMapping("/me")
    public CurrentUser me(HttpServletRequest request) {
        String uid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        String email = (String) request.getAttribute(FirebaseAuthenticationFilter.EMAIL_ATTRIBUTE);
        return new CurrentUser(uid, email);
    }
}
