package io.openskeleton.backend.login;

/**
 * Raised when the email-code verify path cannot complete because the Firebase Admin dependency
 * is unavailable (OSK-129): the Admin client is not configured (no Application Default
 * Credentials), or a Firebase call to resolve the identity / mint the custom token failed.
 * Mapped to a degraded RFC 7807 {@code 503 Service Unavailable} with a {@code Retry-After} hint
 * by {@link EmailCodeController} — matching the auth filter's degraded shape (OSK-65) and the
 * email-verification 503 (OSK-77).
 *
 * <p>Note this only affects <b>verify</b> (which must mint a token). <b>Start</b> never needs
 * Firebase, so it keeps working — a code is still issued and emailed even with no ADC.
 */
public class EmailLoginUnavailableException extends RuntimeException {

    public EmailLoginUnavailableException(String message) {
        super(message);
    }

    public EmailLoginUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
