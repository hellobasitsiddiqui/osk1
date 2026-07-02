package io.openskeleton.backend.web.ratelimit;

/**
 * A single client's token bucket (OSK-63) — a small, self-contained, thread-safe
 * rate limiter with no external dependency.
 *
 * <p><b>Why hand-rolled instead of Bucket4j:</b> the whole algorithm is a few lines,
 * it is trivially unit-testable with an injected clock, and it avoids adding a
 * library + version-resolution risk for functionality this simple. One instance is
 * held per client key in the {@link RateLimitFilter}'s map.
 *
 * <p><b>Algorithm:</b> the bucket starts full ({@code capacity} tokens). Each
 * {@link #tryConsume} spends one token if available. Tokens are refilled lazily —
 * rather than a background thread, {@link #refill} computes how many tokens should
 * have accrued since the last touch based on elapsed wall-clock nanos and the
 * configured rate, capped at {@code capacity}. Tokens are tracked as a {@code double}
 * so fractional accrual between calls is not lost.
 *
 * <p><b>Concurrency:</b> {@link #tryConsume} is {@code synchronized} so concurrent
 * requests for the same key mutate the token count atomically. Buckets for different
 * keys are independent instances (held in a {@code ConcurrentHashMap}), so they never
 * contend with each other.
 *
 * <p>The clock is passed in as a nanosecond timestamp ({@code System.nanoTime()} in
 * production) so tests can drive refill deterministically without sleeping.
 */
final class TokenBucket {

    private final long capacity;

    /** Tokens accrued per nanosecond — precomputed from {@code refillTokens / refillPeriodNanos}. */
    private final double refillTokensPerNano;

    /** Current token balance (fractional to preserve sub-token accrual between calls). */
    private double tokens;

    /** {@code nanoTime()} value at which {@link #tokens} was last refilled. */
    private long lastRefillNanos;

    /**
     * @param capacity max tokens / burst size (must be &gt; 0)
     * @param refillTokens tokens added every {@code refillPeriodNanos}
     * @param refillPeriodNanos the refill interval in nanoseconds (must be &gt; 0)
     * @param nowNanos the current {@code nanoTime()} reading used to seed the clock
     */
    TokenBucket(long capacity, long refillTokens, long refillPeriodNanos, long nowNanos) {
        this.capacity = capacity;
        this.refillTokensPerNano = (double) refillTokens / (double) refillPeriodNanos;
        this.tokens = capacity; // start full so a fresh client gets its whole burst immediately
        this.lastRefillNanos = nowNanos;
    }

    /**
     * Attempts to spend one token.
     *
     * @param nowNanos the current {@code nanoTime()} reading (refill is computed
     *     relative to the previous call)
     * @return a {@link Decision} — {@code allowed} when a token was available (and
     *     spent), otherwise {@code denied} carrying the {@code retryAfterSeconds} the
     *     caller should wait before the next token becomes available
     */
    synchronized Decision tryConsume(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return new Decision(true, 0L);
        }
        // Not enough for a whole token yet: compute how long until one more accrues so
        // we can advertise an honest Retry-After (rounded up, floored at 1 second).
        double deficit = 1.0 - tokens;
        long nanosUntilNextToken = (long) Math.ceil(deficit / refillTokensPerNano);
        long retryAfterSeconds = Math.max(1L, (long) Math.ceil(nanosUntilNextToken / 1_000_000_000.0));
        return new Decision(false, retryAfterSeconds);
    }

    /**
     * Lazily tops the bucket up by however many tokens have accrued since {@link
     * #lastRefillNanos}, capped at {@link #capacity}. A non-positive elapsed interval
     * (clock not advanced, or two calls in the same nanosecond) is a no-op.
     */
    private void refill(long nowNanos) {
        long elapsedNanos = nowNanos - lastRefillNanos;
        if (elapsedNanos <= 0) {
            return;
        }
        tokens = Math.min(capacity, tokens + elapsedNanos * refillTokensPerNano);
        lastRefillNanos = nowNanos;
    }

    /**
     * Outcome of a {@link #tryConsume} call.
     *
     * @param allowed whether the request may proceed (a token was spent)
     * @param retryAfterSeconds seconds to wait before retrying — meaningful only when
     *     {@code allowed} is {@code false}; {@code 0} when allowed
     */
    record Decision(boolean allowed, long retryAfterSeconds) {}
}
