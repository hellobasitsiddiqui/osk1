package io.openskeleton.backend.audit;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link AuditEvent} (OSK-80).
 *
 * <p>The id type is {@link UUID} to match the entity's UUID primary key. Although
 * {@link JpaRepository} technically exposes {@code delete*}/{@code save}-as-update
 * operations, the audit log is <b>append-only</b>: this repository is only ever used
 * to INSERT a new event ({@code save} on a transient entity) and to read events back.
 * No caller updates or deletes audit rows, and the {@link AuditEvent} entity has no
 * setters, so there is no mutation path in practice. Keeping the standard
 * {@code JpaRepository} (rather than a hand-rolled narrower interface) matches the
 * {@code UserRepository} convention; the append-only discipline is a usage contract
 * enforced by {@code AuditService} and the entity's immutability, not by hiding methods.
 */
public interface AuditRepository extends JpaRepository<AuditEvent, UUID> {

    /**
     * Page over the events a given actor produced for a single action, e.g. one user's
     * {@code PROFILE_UPDATED} events for the {@code GET /api/v1/me/history} feature (OSK-99).
     *
     * <p>Both filter columns are indexed ({@code idx_audit_events_actor}, and
     * {@code idx_audit_events_created_at} backs the typical newest-first ordering), so this
     * stays cheap as the append-only table grows. Ordering is intentionally left to the
     * {@link Pageable} rather than baked into the method name, so the caller controls it via
     * the shared pagination convention (OSK-87) — the history endpoint defaults it to
     * {@code createdAt DESC}.
     *
     * @param actorFirebaseUid the acting user's Firebase UID to filter by
     * @param action the single action to filter by
     * @param pageable page/size/sort (size is capped by {@code PageableConfig})
     * @return the requested page of matching events
     */
    Page<AuditEvent> findByActorFirebaseUidAndAction(String actorFirebaseUid, AuditAction action, Pageable pageable);

    /**
     * Page over every event recorded against one target entity — e.g. all audit rows for a
     * single user id — backing the admin audit read endpoint's {@code ?targetUserId=} filter
     * (OSK-93). Ordering is left to the {@link Pageable} (the endpoint defaults it to
     * {@code createdAt DESC}, newest-first).
     *
     * @param targetId the affected entity's id to filter by (a user's UUID rendered as text)
     * @param pageable page/size/sort (size is capped by {@code PageableConfig})
     * @return the requested page of events targeting that id
     */
    Page<AuditEvent> findByTargetId(String targetId, Pageable pageable);

    /**
     * Page over every event of a single action across all actors/targets — e.g. all
     * {@code ROLE_CHANGED} events — backing the admin audit read endpoint's {@code ?action=}
     * filter (OSK-93). Ordering is left to the {@link Pageable}.
     *
     * @param action the single action to filter by
     * @param pageable page/size/sort (size is capped by {@code PageableConfig})
     * @return the requested page of events for that action
     */
    Page<AuditEvent> findByAction(AuditAction action, Pageable pageable);

    /**
     * Page over the events of a single action recorded against one target — the combined
     * {@code ?targetUserId=&action=} filter on the admin audit read endpoint (OSK-93).
     * Ordering is left to the {@link Pageable}.
     *
     * @param targetId the affected entity's id to filter by
     * @param action the single action to filter by
     * @param pageable page/size/sort (size is capped by {@code PageableConfig})
     * @return the requested page of matching events
     */
    Page<AuditEvent> findByTargetIdAndAction(String targetId, AuditAction action, Pageable pageable);
}
