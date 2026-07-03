package io.openskeleton.backend.avatar;

/**
 * The avatar could not be applied because the Firebase Admin dependency is unavailable (OSK-83) —
 * either no Admin client is configured (no ADC, e.g. Storage/Firebase not yet enabled — OSK-95) or a
 * live Firebase call failed. Mapped to a degraded {@code 503 problem+json} with a {@code Retry-After}
 * hint by {@code MeController}, mirroring the email-verification resend degradation (OSK-77).
 */
public class AvatarUnavailableException extends RuntimeException {

    public AvatarUnavailableException(String message) {
        super(message);
    }

    public AvatarUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
