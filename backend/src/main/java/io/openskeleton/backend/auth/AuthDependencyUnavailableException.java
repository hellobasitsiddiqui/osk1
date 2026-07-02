package io.openskeleton.backend.auth;

/**
 * Signals that the token-verification dependency (Firebase, behind the
 * {@link TokenVerifier} seam) is currently UNAVAILABLE — a verify attempt timed out,
 * the circuit-breaker is open, or bounded retries against a failing dependency were
 * exhausted (OSK-65).
 *
 * <p><b>Why a type distinct from {@link TokenVerificationException}:</b> the two
 * outcomes must map to <i>different</i> HTTP statuses. A
 * {@code TokenVerificationException} means "we reached the dependency and it told us
 * this token is invalid" → {@code 401}. This exception means "we could not get a
 * trustworthy answer in time" → a degraded {@code 503}. Keeping them separate is
 * exactly what lets {@link FirebaseAuthenticationFilter} fail fast with a {@code 503}
 * during an outage while never turning a legitimately-rejected token into a
 * {@code 503}.
 *
 * <p>Unchecked because it is an infrastructure fault surfaced from deep inside the
 * resilience pipeline; the filter catches it explicitly and renders the degraded
 * response.
 */
public class AuthDependencyUnavailableException extends RuntimeException {

    /**
     * @param message a short, non-sensitive reason (safe to log; not echoed verbatim to clients)
     * @param cause   the underlying resilience failure (timeout / breaker-open / exhausted retry)
     */
    public AuthDependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
