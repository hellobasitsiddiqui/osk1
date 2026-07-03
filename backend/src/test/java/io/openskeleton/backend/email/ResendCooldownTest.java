package io.openskeleton.backend.email;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResendCooldown} — the per-user "one send per window" gate
 * (OSK-77). Driven by a controllable {@link Clock} so the whole state machine (first
 * send, denial inside the window, reopening after it, honest {@code Retry-After}
 * rounding, and per-user independence) is exercised deterministically without sleeping.
 */
class ResendCooldownTest {

    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    @Test
    void firstSendIsAlwaysAllowed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ResendCooldown cooldown = new ResendCooldown(COOLDOWN, clock);

        ResendCooldown.Decision decision = cooldown.check("uid-1");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.retryAfterSeconds()).isZero();
    }

    @Test
    void secondSendInsideTheWindowIsDeniedWithFullRetryAfter() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ResendCooldown cooldown = new ResendCooldown(COOLDOWN, clock);

        cooldown.check("uid-1"); // consume the window
        ResendCooldown.Decision denied = cooldown.check("uid-1"); // immediate repeat

        assertThat(denied.allowed()).isFalse();
        // now == lastSent, so the full 60s window remains.
        assertThat(denied.retryAfterSeconds()).isEqualTo(60L);
    }

    @Test
    void sendIsAllowedAgainOnceTheWindowHasElapsed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ResendCooldown cooldown = new ResendCooldown(COOLDOWN, clock);

        cooldown.check("uid-1");
        clock.advance(COOLDOWN); // exactly at the boundary: now == readyAt -> allowed

        ResendCooldown.Decision decision = cooldown.check("uid-1");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.retryAfterSeconds()).isZero();
    }

    @Test
    void retryAfterIsRoundedUpToWholeSeconds() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ResendCooldown cooldown = new ResendCooldown(COOLDOWN, clock);

        cooldown.check("uid-1");
        clock.advance(Duration.ofMillis(58_200)); // 1.8s remain -> ceil -> 2

        assertThat(cooldown.check("uid-1").retryAfterSeconds()).isEqualTo(2L);
    }

    @Test
    void retryAfterIsFlooredAtOneSecond() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ResendCooldown cooldown = new ResendCooldown(COOLDOWN, clock);

        cooldown.check("uid-1");
        clock.advance(Duration.ofMillis(59_500)); // 0.5s remain -> ceil 1 (floor keeps it >= 1)

        assertThat(cooldown.check("uid-1").retryAfterSeconds()).isEqualTo(1L);
    }

    @Test
    void differentUsersHaveIndependentWindows() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ResendCooldown cooldown = new ResendCooldown(COOLDOWN, clock);

        cooldown.check("uid-1"); // consume uid-1's window

        // uid-2 has never sent -> allowed even though uid-1 is inside its window.
        assertThat(cooldown.check("uid-2").allowed()).isTrue();
        // uid-1 is still throttled.
        assertThat(cooldown.check("uid-1").allowed()).isFalse();
    }

    /** A {@link Clock} whose instant can be advanced by the test. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration delta) {
            this.now = this.now.plus(delta);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public long millis() {
            return now.toEpochMilli();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this; // zone is irrelevant to the cooldown maths
        }
    }
}
