package io.openskeleton.backend.web.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed, env-overridable configuration for the API rate limiter (OSK-63),
 * bound from the {@code app.rate-limit.*} namespace and discovered by
 * {@code @ConfigurationPropertiesScan} on {@link io.openskeleton.backend.BackendApplication}
 * (the same mechanism that binds {@code AppProperties}).
 *
 * <p>The model is a classic <b>token bucket</b> per client: a bucket holds up to
 * {@link #capacity} tokens (the allowed burst), each accepted request spends one
 * token, and tokens are replenished at a steady rate of {@link #refillTokens} every
 * {@link #refillPeriod}. So the sustained request rate is
 * {@code refillTokens / refillPeriod} and short bursts up to {@code capacity} are
 * tolerated. Defaults below (100 tokens, refill 100 every minute) mean "100
 * requests/minute per client with a burst of 100"; override per environment via the
 * {@code APP_RATE_LIMIT_*} env vars wired in {@code application.yml}.
 *
 * <p>Field defaults are set here so the limiter is safe even with zero config; the
 * base {@code application.yml} additionally exposes each value as an env-var
 * placeholder so operators can tune limits without a rebuild.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public class RateLimitProperties {

    /**
     * Master on/off switch. When {@code false} the filter skips every request
     * (no buckets, no 429s) — useful to disable limiting entirely in an environment
     * without removing the wiring.
     */
    private boolean enabled = true;

    /**
     * Maximum tokens a single client's bucket can hold — i.e. the largest burst of
     * requests served back-to-back before the steady refill rate takes over.
     */
    private long capacity = 100;

    /** Number of tokens added to each bucket every {@link #refillPeriod}. */
    private long refillTokens = 100;

    /** The interval over which {@link #refillTokens} tokens are added back to a bucket. */
    private Duration refillPeriod = Duration.ofMinutes(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCapacity() {
        return capacity;
    }

    public void setCapacity(long capacity) {
        this.capacity = capacity;
    }

    public long getRefillTokens() {
        return refillTokens;
    }

    public void setRefillTokens(long refillTokens) {
        this.refillTokens = refillTokens;
    }

    public Duration getRefillPeriod() {
        return refillPeriod;
    }

    public void setRefillPeriod(Duration refillPeriod) {
        this.refillPeriod = refillPeriod;
    }
}
