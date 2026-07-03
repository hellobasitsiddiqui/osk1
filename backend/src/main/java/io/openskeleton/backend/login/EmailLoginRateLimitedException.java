package io.openskeleton.backend.login;

/**
 * Raised when an email-code start/verify request exceeds the per-email token-bucket limit
 * (OSK-129). Carries the honest {@code Retry-After} the caller should wait, and is mapped to
 * an RFC 7807 {@code 429 Too Many Requests} by {@link EmailCodeController} — mirroring the
 * shape the OSK-63 rate limiter and the OSK-77 resend cooldown produce.
 */
public class EmailLoginRateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public EmailLoginRateLimitedException(long retryAfterSeconds) {
        super("Too many email-code requests; retry after " + retryAfterSeconds + " second(s).");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
