package io.openskeleton.backend.login;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Env-overridable configuration for the passwordless email-code login flow (OSK-129), bound
 * from the {@code app.email-login.*} namespace and discovered by
 * {@code @ConfigurationPropertiesScan} on {@code BackendApplication} (the same mechanism that
 * binds {@code RateLimitProperties} / {@code EmailVerificationProperties}).
 *
 * <p>Every knob has a safe default here so the flow works with zero config; each is also
 * exposed as an env-var placeholder in {@code application.yml} so operators can tune it
 * without a rebuild. Together {@link #ttl}, {@link #maxAttempts} and the per-email
 * {@link #perEmailCapacity rate-limit} knobs are the brute-force defences around a short
 * numeric code.
 */
@ConfigurationProperties(prefix = "app.email-login")
public class EmailLoginProperties {

    /** Number of decimal digits in a generated code. 6 is the usual OTP length. */
    private int codeLength = 6;

    /**
     * How long an issued code stays valid. After this window a verify is rejected as expired
     * regardless of attempts. Short by design (default 10 minutes) — the first brute-force
     * defence.
     */
    private Duration ttl = Duration.ofMinutes(10);

    /**
     * Maximum number of failed verify attempts against a single code before it is treated as
     * dead (even if still within its TTL). The second brute-force defence; default 5.
     */
    private int maxAttempts = 5;

    /**
     * Per-email token-bucket capacity (burst) applied to BOTH the start and verify endpoints
     * (each keyed independently). Complements the coarse per-IP request limiter (OSK-63) so a
     * single address cannot be flooded with codes and a single address cannot be brute-forced
     * across many codes. Default 5.
     */
    private long perEmailCapacity = 5;

    /** Tokens refilled into each per-email bucket every {@link #perEmailRefillPeriod}. */
    private long perEmailRefillTokens = 5;

    /** The interval over which {@link #perEmailRefillTokens} tokens are added back. */
    private Duration perEmailRefillPeriod = Duration.ofMinutes(15);

    public int getCodeLength() {
        return codeLength;
    }

    public void setCodeLength(int codeLength) {
        this.codeLength = codeLength;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getPerEmailCapacity() {
        return perEmailCapacity;
    }

    public void setPerEmailCapacity(long perEmailCapacity) {
        this.perEmailCapacity = perEmailCapacity;
    }

    public long getPerEmailRefillTokens() {
        return perEmailRefillTokens;
    }

    public void setPerEmailRefillTokens(long perEmailRefillTokens) {
        this.perEmailRefillTokens = perEmailRefillTokens;
    }

    public Duration getPerEmailRefillPeriod() {
        return perEmailRefillPeriod;
    }

    public void setPerEmailRefillPeriod(Duration perEmailRefillPeriod) {
        this.perEmailRefillPeriod = perEmailRefillPeriod;
    }
}
