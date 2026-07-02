package io.openskeleton.backend.auth;

/**
 * Thrown by a {@link TokenVerifier} when a Firebase ID token cannot be trusted
 * (OSK-28): it is missing, expired, malformed, revoked, or fails signature
 * verification.
 *
 * <p><b>Why a dedicated checked exception:</b> it forces every caller (i.e. the
 * {@link FirebaseAuthenticationFilter}) to explicitly handle the "not authenticated"
 * outcome and turn it into a {@code 401}, rather than letting an unexpected runtime
 * failure fall through to the generic {@code 500} handler. The message is
 * intentionally generic/non-sensitive so it can be surfaced to the caller without
 * leaking why verification failed (which could aid token forgery).
 */
public class TokenVerificationException extends Exception {

    /** @param message a short, non-sensitive reason (safe to log; not echoed verbatim to clients) */
    public TokenVerificationException(String message) {
        super(message);
    }

    /**
     * @param message a short, non-sensitive reason
     * @param cause   the underlying SDK failure (kept for server-side logs only)
     */
    public TokenVerificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
