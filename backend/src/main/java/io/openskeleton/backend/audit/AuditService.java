package io.openskeleton.backend.audit;

import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The write seam for the append-only audit log (OSK-80).
 *
 * <p>{@code record(...)} is the single, intentionally tiny entry point through which
 * the platform appends an audit event. It exists so that account/admin touchpoints can
 * record "who did what to whom, when" with one call, without knowing anything about the
 * persistence layer. The service only ever <b>appends</b> (a {@code save} of a fresh
 * {@link AuditEvent}); it never updates or deletes, upholding the append-only guarantee.
 *
 * <p><b>Wiring status.</b> The account/admin actions that will call this — JIT
 * provisioning (OSK-76) and the admin endpoints (OSK-71) — are not built yet, and the
 * only existing account touchpoint is the auth filter's hot path, which deliberately
 * must not take a synchronous DB write. So this ticket delivers the seam ready for
 * adoption (table + entity + service + tests); the concrete call sites land with
 * OSK-76/OSK-71, which will invoke {@code record(...)} at the point the action occurs.
 */
@Service
public class AuditService {

    private final AuditRepository auditRepository;

    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * Append an audit event with no extra metadata. Convenience overload for the common
     * case where the action/target alone tell the whole story.
     *
     * @param actorFirebaseUid the acting user's Firebase UID, or {@code null} for a
     *     system-originated event
     * @param action the action that occurred (required)
     * @param targetType coarse type of the affected entity (e.g. {@code "USER"}), or {@code null}
     * @param targetId the affected entity's id, or {@code null}
     * @return the persisted, id-assigned event
     */
    public AuditEvent record(String actorFirebaseUid, AuditAction action, String targetType, String targetId) {
        return record(actorFirebaseUid, action, targetType, targetId, null);
    }

    /**
     * Append a single immutable audit event and return it (with its generated id and
     * {@code createdAt} populated). This is the primary write seam.
     *
     * <p>The call runs in its own transaction so the write is atomic and independent.
     * Because {@link AuditEvent} is created transient here and never re-read-then-mutated,
     * the {@code save} is always an INSERT — never an UPDATE — which is what keeps the log
     * append-only.
     *
     * @param actorFirebaseUid the acting user's Firebase UID, or {@code null} for a
     *     system-originated event
     * @param action the action that occurred (required)
     * @param targetType coarse type of the affected entity (e.g. {@code "USER"}), or {@code null}
     * @param targetId the affected entity's id, or {@code null}
     * @param metadata optional benign structured context — <b>never secrets or tokens</b> —
     *     or {@code null}
     * @return the persisted, id-assigned event
     */
    @Transactional
    public AuditEvent record(
            String actorFirebaseUid,
            AuditAction action,
            String targetType,
            String targetId,
            Map<String, Object> metadata) {
        var event = new AuditEvent(actorFirebaseUid, action, targetType, targetId, metadata);
        return auditRepository.save(event);
    }

    /**
     * Read seam for the audit log: page over the events a single actor produced for one
     * action. Added for OSK-99's {@code GET /api/v1/me/history}, which surfaces a caller's
     * own {@code PROFILE_UPDATED} events, but kept action-agnostic so it is reusable by any
     * future "this actor's X events" view without widening the write seam's concerns.
     *
     * <p>Read-only and side-effect free: it never appends, so it upholds the append-only
     * guarantee. The {@code @Transactional(readOnly = true)} mirrors the read methods on
     * {@code UserService} — the query runs in a lightweight read-only transaction.
     *
     * @param actorFirebaseUid the acting user's Firebase UID to filter by
     * @param action the single action to filter by (e.g. {@link AuditAction#PROFILE_UPDATED})
     * @param pageable page/size/sort from the shared pagination convention (OSK-87)
     * @return the requested page of matching events
     */
    @Transactional(readOnly = true)
    public Page<AuditEvent> findByActorAndAction(String actorFirebaseUid, AuditAction action, Pageable pageable) {
        return auditRepository.findByActorFirebaseUidAndAction(actorFirebaseUid, action, pageable);
    }
}
