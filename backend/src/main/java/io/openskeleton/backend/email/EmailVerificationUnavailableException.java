package io.openskeleton.backend.email;

/**
 * Raised when the verification resend cannot be performed because the Firebase Admin
 * dependency is unavailable (OSK-77): either no {@code FirebaseAuth} client is
 * configured (no Application Default Credentials at startup) or an Admin SDK call
 * (reading the user, minting the link) failed. The controller maps it to a degraded
 * RFC 7807 {@code 503 Service Unavailable} with a {@code Retry-After} hint — the same
 * "dependency degraded, back off and retry" contract the auth filter uses (OSK-65),
 * distinct from a genuine client error.
 */
class EmailVerificationUnavailableException extends RuntimeException {

    EmailVerificationUnavailableException(String message) {
        super(message);
    }

    EmailVerificationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
