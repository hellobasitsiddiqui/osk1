package io.openskeleton.backend.audit;

import java.util.Map;
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
}
