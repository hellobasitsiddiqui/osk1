package io.openskeleton.backend.auth;

/**
 * Seam that verifies a Firebase ID token and returns the authenticated caller
 * (OSK-28).
 *
 * <p><b>Why an interface (and not just a call into the Firebase SDK):</b> the real
 * verification — {@code FirebaseAuth.verifyIdToken(...)} — needs a live Firebase
 * project, network access and a genuine signed token, none of which exist in a unit
 * test. By hiding verification behind this one method, the whole authentication path
 * (the {@link FirebaseAuthenticationFilter}, the 401 shape, the permit-list, the
 * {@code /api/v1/me} round-trip) can be exercised end-to-end with a mocked verifier,
 * while the production wiring ({@link FirebaseTokenVerifier}) stays a thin adapter.
 *
 * <p>Implementations MUST be side-effect-free with respect to the request: they only
 * decide "is this token valid, and if so who is it?". Establishing the security
 * context and writing the HTTP response is the filter's job.
 */
public interface TokenVerifier {

    /**
     * Verifies a raw Firebase ID token (the value after {@code Bearer } in the
     * {@code Authorization} header).
     *
     * @param idToken the raw JWT string; never {@code null} or blank when called by
     *     the filter (the filter rejects a missing/blank token before reaching here)
     * @return the verified caller's identity (uid, and email if present on the token)
     * @throws TokenVerificationException if the token is absent, expired, malformed,
     *     signed by the wrong key, or cannot be verified for any other reason — the
     *     filter turns this into an RFC 7807 {@code 401}
     */
    VerifiedToken verify(String idToken) throws TokenVerificationException;

    /**
     * The identity established from a valid ID token.
     *
     * <p>A record keeps it immutable and trivially constructible in tests. {@code
     * email} may be {@code null}: some Firebase providers (e.g. anonymous or
     * phone-only sign-in) issue tokens with a uid but no email, so callers must treat
     * it as optional.
     *
     * @param uid   the Firebase user id — always present on a valid token
     * @param email the caller's email, or {@code null} if the token carries none
     */
    record VerifiedToken(String uid, String email) {}
}
