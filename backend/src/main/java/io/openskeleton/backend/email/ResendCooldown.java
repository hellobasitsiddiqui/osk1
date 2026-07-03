package io.openskeleton.backend.email;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A per-user "one resend every N seconds" cooldown for verification emails (OSK-77).
 *
 * <p><b>Why a cooldown and not the token bucket:</b> the OSK-63 {@code TokenBucket}
 * models "N requests per period with a burst"; here the requirement is the simpler,
 * stricter "at most one send per cooldown window per user". This tracks the last
 * successful send time per uid and rejects anything inside the window — a few lines,
 * trivially unit-testable with an injected {@link Clock}, and independent of the
 * request-scoped rate-limit filter (which caps overall API traffic, not per-user
 * sends). It mirrors the token bucket's honest {@code Retry-After} contract.
 *
 * <p><b>Concurrency:</b> the check-and-record is a single atomic {@link
 * ConcurrentHashMap#compute} per key, so two concurrent resends for the same user can
 * never both pass — exactly one wins the window and the other is told to retry. Keys
 * for different users are independent map entries and never contend.
 *
 * <p>The {@link Clock} is injected so tests can advance time deterministically instead
 * of sleeping across a real cooldown window.
 */
final class ResendCooldown {

    private final Duration cooldown;
    private final Clock clock;

    /** Last successful-send instant per user key; entries are created/updated lazily on first send. */
    private final ConcurrentHashMap<String, Instant> lastSent = new ConcurrentHashMap<>();

    /**
     * @param cooldown the minimum interval between two successful sends for one user
     *     (a zero/negative value effectively disables the cooldown)
     * @param clock the time source (production: {@code Clock.systemUTC()}; tests: a
     *     controllable clock)
     */
    ResendCooldown(Duration cooldown, Clock clock) {
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /**
     * Atomically decides whether {@code key} may send now, and — when allowed —
     * records this instant as the new last-send time (consuming the window).
     *
     * @param key the per-user bucket key (the caller's Firebase uid)
     * @return {@link Decision#allowed()} with {@code retryAfterSeconds == 0} when the
     *     send may proceed; otherwise a denial carrying the whole seconds the caller
     *     should wait before the window reopens (rounded up, floored at 1)
     */
    Decision check(String key) {
        // Smuggle the decision out of the atomic remapping function; compute() runs the
        // lambda under the entry lock so the read (is the window open?) and the write
        // (record this send) happen as one indivisible step.
        Decision[] out = new Decision[1];
        lastSent.compute(key, (k, previous) -> {
            Instant now = clock.instant();
            if (previous == null) {
                // First ever send for this user: always allowed; record now.
                out[0] = new Decision(true, 0L);
                return now;
            }
            Instant readyAt = previous.plus(cooldown);
            if (!now.isBefore(readyAt)) {
                // Window has elapsed (now >= readyAt): allow and reset the window.
                out[0] = new Decision(true, 0L);
                return now;
            }
            // Still inside the window: deny, keep the original send time unchanged, and
            // advertise an honest Retry-After.
            out[0] = new Decision(false, retryAfterSeconds(now, readyAt));
            return previous;
        });
        return out[0];
    }

    /** Whole seconds from {@code now} until {@code readyAt}, rounded up and floored at 1. */
    private static long retryAfterSeconds(Instant now, Instant readyAt) {
        long millisRemaining = Duration.between(now, readyAt).toMillis();
        return Math.max(1L, (long) Math.ceil(millisRemaining / 1000.0));
    }

    /**
     * Outcome of a {@link #check} call.
     *
     * @param allowed whether the send may proceed (and the window was consumed)
     * @param retryAfterSeconds seconds to wait before retrying — meaningful only when
     *     {@code allowed} is {@code false}; {@code 0} when allowed
     */
    record Decision(boolean allowed, long retryAfterSeconds) {}
}
