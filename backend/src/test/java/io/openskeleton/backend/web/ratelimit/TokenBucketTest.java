package io.openskeleton.backend.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TokenBucket} using an injected nanosecond clock so refill
 * behaviour is exercised deterministically without sleeping (OSK-63).
 */
class TokenBucketTest {

    private static final long ONE_SECOND_NANOS = Duration.ofSeconds(1).toNanos();

    @Test
    void startsFullThenDeniesOnceDrained() {
        // Capacity 2, refilling 2 tokens/second. Bucket starts full at t0.
        TokenBucket bucket = new TokenBucket(2, 2, ONE_SECOND_NANOS, 0L);

        // Two back-to-back consumes at the same instant succeed (also covers the
        // "elapsed <= 0" refill no-op branch on the second call).
        assertThat(bucket.tryConsume(0L).allowed()).isTrue();
        assertThat(bucket.tryConsume(0L).allowed()).isTrue();

        // Third at the same instant is denied — bucket is empty and no time has passed.
        TokenBucket.Decision denied = bucket.tryConsume(0L);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void refillsOverTimeAndAllowsAgain() {
        TokenBucket bucket = new TokenBucket(2, 2, ONE_SECOND_NANOS, 0L);
        bucket.tryConsume(0L);
        bucket.tryConsume(0L);
        assertThat(bucket.tryConsume(0L).allowed()).isFalse();

        // After a full second, 2 tokens have accrued (capped at capacity) → allowed again.
        assertThat(bucket.tryConsume(ONE_SECOND_NANOS).allowed()).isTrue();
    }

    @Test
    void refillIsCappedAtCapacity() {
        TokenBucket bucket = new TokenBucket(2, 2, ONE_SECOND_NANOS, 0L);
        bucket.tryConsume(0L);
        bucket.tryConsume(0L);

        // Ten seconds pass — far more than needed — but the bucket only ever holds
        // `capacity` (2) tokens, so exactly two consumes succeed and the third fails.
        long tenSeconds = 10 * ONE_SECOND_NANOS;
        assertThat(bucket.tryConsume(tenSeconds).allowed()).isTrue();
        assertThat(bucket.tryConsume(tenSeconds).allowed()).isTrue();
        assertThat(bucket.tryConsume(tenSeconds).allowed()).isFalse();
    }

    @Test
    void retryAfterReflectsSlowRefillRate() {
        // 1 token every 10 seconds, capacity 1. Drain it, then the advertised
        // Retry-After should round up to ~10 seconds (never below 1).
        TokenBucket bucket = new TokenBucket(1, 1, 10 * ONE_SECOND_NANOS, 0L);
        assertThat(bucket.tryConsume(0L).allowed()).isTrue();

        TokenBucket.Decision denied = bucket.tryConsume(0L);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isBetween(1L, 10L);
    }
}
