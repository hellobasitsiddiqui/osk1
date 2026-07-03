package io.openskeleton.backend.login;

import io.openskeleton.backend.web.ratelimit.TokenBucket;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-key token-bucket throttle for the email-code login endpoints (OSK-129), reusing the
 * OSK-63 {@link TokenBucket} rather than re-implementing the algorithm.
 *
 * <p><b>Why a second limiter on top of the global one:</b> the request-scoped
 * {@code RateLimitFilter} (OSK-63) already caps ALL {@code /api/**} traffic per client IP,
 * which covers the "per IP" half of the requirement. This adds the "per email" half: a bucket
 * keyed by the target address, so a single email address cannot be flooded with codes
 * (start) or brute-forced across codes (verify) even from many source IPs. The two limiters
 * are complementary — an attacker must beat both.
 *
 * <p><b>Keying:</b> callers pass a composite key ({@code "start:" + email} /
 * {@code "verify:" + email}) so the two endpoints have independent budgets — a burst of
 * verify attempts never eats into the start allowance for the same address, and vice-versa.
 * Buckets are held per key in a {@link ConcurrentHashMap}; different keys never contend.
 *
 * <p>Mirrors {@code RateLimitFilter}'s bucket management (one {@link TokenBucket} per key,
 * seeded from the configured capacity/refill), just keyed by email instead of by client id.
 */
@Component
public class EmailLoginRateLimiter {

    private final long capacity;
    private final long refillTokens;
    private final long refillPeriodNanos;

    /** One bucket per composite key; created lazily on first touch (starts full). */
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * @param properties supplies the per-email capacity / refill-rate / refill-period (bound
     *     from {@code app.email-login.*})
     */
    public EmailLoginRateLimiter(EmailLoginProperties properties) {
        this.capacity = properties.getPerEmailCapacity();
        this.refillTokens = properties.getPerEmailRefillTokens();
        this.refillPeriodNanos = properties.getPerEmailRefillPeriod().toNanos();
    }

    /**
     * Try to spend one token for {@code key}.
     *
     * @param key the composite bucket key (e.g. {@code "start:alice@example.com"})
     * @return the bucket {@link TokenBucket.Decision} — {@code allowed} when a token was
     *     available, otherwise a denial carrying the whole seconds to wait before retrying
     */
    public TokenBucket.Decision tryAcquire(String key) {
        TokenBucket bucket = buckets.computeIfAbsent(
                key, k -> new TokenBucket(capacity, refillTokens, refillPeriodNanos, System.nanoTime()));
        return bucket.tryConsume(System.nanoTime());
    }
}
