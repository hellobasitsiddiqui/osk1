package io.openskeleton.backend.auth;

import io.openskeleton.backend.user.User.Role;

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
     * <p><b>Role (OSK-79):</b> {@code role} is the caller's authorization role, derived
     * from the Firebase {@code role} custom claim by {@link RoleClaimMapper}. It is
     * carried on the principal itself (rather than recomputed per handler) so the auth
     * filter can publish the matching {@code ROLE_*} authority and later handlers see a
     * consistent identity. The canonical constructor normalises a {@code null} role to
     * {@link Role#USER}, so a principal always has a concrete, least-privilege role — the
     * same default the filter applies when the claim is absent.
     *
     * @param uid   the Firebase user id — always present on a valid token
     * @param email the caller's email, or {@code null} if the token carries none
     * @param role  the caller's role (never {@code null} after construction)
     */
    record VerifiedToken(String uid, String email, Role role) {

        /** Normalise a missing role to the safe default so the principal is never role-less. */
        public VerifiedToken {
            if (role == null) {
                role = Role.USER;
            }
        }

        /**
         * Convenience for callers/tests that only care about identity: constructs a
         * principal with the default {@link Role#USER}. This keeps the pre-RBAC
         * two-argument construction (uid + email) working unchanged while OSK-79 adds
         * the role dimension.
         */
        public VerifiedToken(String uid, String email) {
            this(uid, email, Role.USER);
        }
    }
}
