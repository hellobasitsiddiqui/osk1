package io.openskeleton.backend.user;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stamps a user's {@code last_active_at} "heartbeat" (OSK-73), throttled so a busy caller does
 * not cause a database write on every request.
 *
 * <p><b>What it does.</b> {@link #touch(String)} records that a user was just active. It is
 * invoked by {@link LastActiveInterceptor} for every authenticated {@code /api/v1/**} request,
 * so {@code last_active_at} reflects the last time the platform actually saw the caller — a
 * backend-owned signal distinct from Firebase's {@code lastLoginAt} (which only advances on a
 * fresh sign-in).
 *
 * <p><b>Two layers of throttling, so we "avoid a write per request".</b>
 * <ol>
 *   <li><b>In-process skip (avoids the round-trip):</b> a small {@link ConcurrentMap} remembers,
 *       per uid, the last instant we <i>successfully</i> stamped. A subsequent {@link #touch}
 *       within {@link #THROTTLE} returns immediately without touching the database at all — the
 *       common hot path costs nothing.</li>
 *   <li><b>SQL predicate (authoritative, race- and restart-safe):</b> when we do go to the
 *       database, the UPDATE only writes rows whose {@code last_active_at} is NULL or older than
 *       the threshold ({@link UserRepository#touchLastActive}). So even across instances or after
 *       a restart (empty cache), at most one real row write happens per user per window, and two
 *       concurrent requests can't double-write.</li>
 * </ol>
 * The in-memory map is only ever populated after a confirmed row update ({@code rows > 0}); a
 * miss (e.g. the user isn't provisioned yet — the very first request provisions the row <i>after</i>
 * this runs) is deliberately not cached, so the next request retries rather than being suppressed.
 *
 * <p><b>Why a targeted UPDATE and not {@code repository.save(user)}.</b> The bulk JPQL update in
 * {@link UserRepository#touchLastActive} writes only the single column and, being a bulk update,
 * bypasses the {@code @Version}/{@code @PreUpdate} lifecycle — so a heartbeat neither bumps
 * {@code updated_at} (which should track profile edits, not activity) nor collides with a
 * concurrent profile save via optimistic locking. A lost heartbeat under a rare interleave is
 * harmless: the next request re-stamps it.
 */
@Service
public class LastActiveService {

    /**
     * How stale {@code last_active_at} must be before we write again. Chosen as a small,
     * activity-tracking granularity: fine enough to be useful, coarse enough that a chatty client
     * causes at most one write every few minutes.
     */
    static final Duration THROTTLE = Duration.ofMinutes(5);

    private final UserRepository userRepository;

    /** Per-uid instant of our last confirmed stamp; the in-process throttle (see class docs). */
    private final ConcurrentMap<String, Instant> lastStampedByUid = new ConcurrentHashMap<>();

    public LastActiveService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Record that the given user is active now, subject to the {@link #THROTTLE} window.
     *
     * <p>No-ops when {@code firebaseUid} is {@code null} (defensive — an authenticated request
     * always has a uid, but callers need not pre-check) or when this uid was stamped within the
     * window. Otherwise it issues the throttled UPDATE and, if a row was actually written, remembers
     * the instant so the next in-window request can skip the database entirely.
     *
     * @param firebaseUid the caller's verified Firebase uid, or {@code null} (no-op)
     */
    @Transactional
    public void touch(String firebaseUid) {
        if (firebaseUid == null) {
            return;
        }
        Instant now = Instant.now();
        Instant threshold = now.minus(THROTTLE);

        Instant lastStamped = lastStampedByUid.get(firebaseUid);
        if (lastStamped != null && lastStamped.isAfter(threshold)) {
            return; // stamped within the window in this process — skip the DB round-trip entirely.
        }

        int rowsUpdated = userRepository.touchLastActive(firebaseUid, now, threshold);
        if (rowsUpdated > 0) {
            // Only cache a confirmed write: a miss (row absent / already fresh in another instance)
            // must not suppress the next attempt.
            lastStampedByUid.put(firebaseUid, now);
        }
    }
}
