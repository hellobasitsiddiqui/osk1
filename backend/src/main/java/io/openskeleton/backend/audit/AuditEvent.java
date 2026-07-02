package io.openskeleton.backend.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity mapped to the {@code audit_events} table created by
 * {@code V4__create_audit_events.sql} (OSK-80) — one immutable row per
 * security-relevant action the platform takes.
 *
 * <p><b>Append-only by construction.</b> This entity is deliberately write-once: it
 * has <i>no setters</i> and <i>no {@code @PreUpdate}</i> callback, so once persisted a
 * row cannot be mutated through the mapping. Combined with the table having no
 * {@code updated_at} column and the {@code AuditService}/{@code AuditRepository} never
 * calling update/delete, the "audit rows are history and history does not change"
 * guarantee holds at every layer. Fields are populated once — via the constructor and
 * the {@link #onCreate()} lifecycle callback — and thereafter only read.
 *
 * <p><b>Mapping notes (each pairs with a column in the migration):</b>
 * <ul>
 *   <li>{@code id} — a UUID surrogate key. {@link GenerationType#UUID} makes Hibernate
 *       assign the value application-side before insert; the column's
 *       {@code DEFAULT gen_random_uuid()} remains only as an out-of-band safety net.</li>
 *   <li>{@code actorFirebaseUid} — WHO acted (nullable: some events are system-originated).</li>
 *   <li>{@code action} — WHAT happened, persisted as the {@link AuditAction} enum
 *       <i>name</i> ({@link EnumType#STRING}) so the stored value is stable/readable,
 *       matching the {@code TEXT NOT NULL} column.</li>
 *   <li>{@code targetType}/{@code targetId} — WHAT it was done to (both nullable).</li>
 *   <li>{@code metadata} — optional structured context, mapped to the Postgres
 *       {@code JSONB} column via {@link JdbcTypeCode}({@link SqlTypes#JSON}) over a
 *       {@code Map}; Hibernate (de)serialises it with the app's JSON mapper. By policy
 *       it must never hold secrets/tokens — only benign, auditable detail.</li>
 *   <li>{@code createdAt} — WHEN, stamped once by {@link #onCreate()} and never updated
 *       (the column is {@code updatable = false}); there is intentionally no
 *       {@code updatedAt}.</li>
 * </ul>
 *
 * <p>Plain Java (explicit constructor/getters) is used rather than Lombok, matching the
 * {@code User} entity: Lombok is not a project dependency. A class (not a record) is
 * required because JPA needs a no-arg constructor and field access.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "actor_firebase_uid")
    private String actorFirebaseUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false)
    private AuditAction action;

    @Column(name = "target_type", updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private String targetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", updatable = false)
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** No-arg constructor required by JPA/Hibernate. */
    protected AuditEvent() {}

    /**
     * Create a new audit event to be appended. The {@code id} is generated on persist
     * ({@link GenerationType#UUID}) and {@code createdAt} is stamped by
     * {@link #onCreate()}, so callers supply only the descriptive fields.
     *
     * @param actorFirebaseUid the acting user's Firebase UID, or {@code null} for a
     *     system-originated event
     * @param action the action that occurred (required)
     * @param targetType coarse type of the affected entity (e.g. {@code "USER"}), or {@code null}
     * @param targetId the affected entity's id, or {@code null}
     * @param metadata optional benign structured context (never secrets/tokens), or {@code null}
     */
    public AuditEvent(
            String actorFirebaseUid,
            AuditAction action,
            String targetType,
            String targetId,
            Map<String, Object> metadata) {
        this.actorFirebaseUid = actorFirebaseUid;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.metadata = metadata;
    }

    /**
     * Stamp the immutable creation timestamp immediately before the (only) INSERT, so
     * the app supplies a non-null value rather than depending on the column default.
     * There is no {@code @PreUpdate} counterpart — audit rows are never updated.
     */
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getActorFirebaseUid() {
        return actorFirebaseUid;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
