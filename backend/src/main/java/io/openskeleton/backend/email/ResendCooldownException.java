package io.openskeleton.backend.email;

/**
 * Raised when a caller asks to resend their verification email again before the
 * per-user cooldown window (OSK-77) has elapsed. The controller maps it to an RFC 7807
 * {@code 429 Too Many Requests} with a {@code Retry-After} header, matching the
 * over-limit shape produced by the OSK-63 rate limiter.
 */
class ResendCooldownException extends RuntimeException {

    private final long retryAfterSeconds;

    /**
     * @param retryAfterSeconds whole seconds the caller should wait before retrying,
     *     surfaced verbatim in the {@code Retry-After} header and the problem detail
     */
    ResendCooldownException(long retryAfterSeconds) {
        super("Verification email already sent recently; retry after " + retryAfterSeconds + " second(s).");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
