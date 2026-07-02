package io.openskeleton.backend.auth;

/**
 * Internal, unchecked carrier that ferries a genuine {@link TokenVerificationException}
 * (a bad, expired, revoked or forged token) across the asynchronous resilience
 * boundary inside {@link ResilientTokenVerifier} (OSK-65).
 *
 * <p><b>Why it exists:</b> the resilience pipeline runs the real verification on a
 * bounded executor and propagates any failure through a {@link java.util.concurrent.Future},
 * which can only surface unchecked exceptions. The <i>checked</i>
 * {@code TokenVerificationException} is therefore wrapped in this unchecked carrier so
 * it can travel out of the worker thread.
 *
 * <p><b>Why it is a distinct type (and not just any RuntimeException):</b> it is the
 * signal that lets the circuit-breaker and retry <b>ignore</b> a token rejection. An
 * invalid token is a normal {@code 401}, not an infrastructure fault: it must never
 * count toward opening the breaker (otherwise an attacker spamming bad tokens could
 * trip the breaker and deny service to everyone) and retrying it is pointless (the
 * verdict is deterministic). Both the breaker and the retry list this class in their
 * {@code ignore-exceptions} (see {@code application.yml}).
 *
 * <p>{@link ResilientTokenVerifier#verify} unwraps it back into the original
 * {@code TokenVerificationException} via {@link #unwrap()} so the auth filter still
 * renders a {@code 401}.
 */
public class AuthFailureWrapper extends RuntimeException {

    /** @param cause the real token-rejection this carrier transports (never {@code null}) */
    public AuthFailureWrapper(TokenVerificationException cause) {
        super(cause);
    }

    /** @return the original checked {@link TokenVerificationException} carried here. */
    public TokenVerificationException unwrap() {
        return (TokenVerificationException) getCause();
    }
}
