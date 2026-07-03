package io.openskeleton.backend.email;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Env-overridable configuration for the email-verification resend endpoint (OSK-77),
 * bound from the {@code app.email-verification.*} namespace and discovered by
 * {@code @ConfigurationPropertiesScan} on {@code BackendApplication} (the same
 * mechanism that binds {@code RateLimitProperties}).
 *
 * <p>The single knob is the per-user {@link #resendCooldown}: the minimum time a caller
 * must wait between successful resends. It exists to stop a user (accidentally or
 * maliciously) triggering a flood of verification emails to their own inbox — a
 * per-user throttle that complements, rather than replaces, the coarse per-client
 * request rate limiter (OSK-63). The default (60s) is set here so the endpoint is safe
 * with zero config; override per environment via {@code APP_EMAIL_VERIFICATION_RESEND_COOLDOWN}.
 */
@ConfigurationProperties(prefix = "app.email-verification")
public class EmailVerificationProperties {

    /**
     * Minimum interval between two successful verification resends for the same user.
     * A second resend inside this window is rejected with {@code 429 Too Many Requests}
     * and a {@code Retry-After} hint. Must be non-negative (a zero/near-zero value
     * effectively disables the cooldown).
     */
    private Duration resendCooldown = Duration.ofSeconds(60);

    public Duration getResendCooldown() {
        return resendCooldown;
    }

    public void setResendCooldown(Duration resendCooldown) {
        this.resendCooldown = resendCooldown;
    }
}
