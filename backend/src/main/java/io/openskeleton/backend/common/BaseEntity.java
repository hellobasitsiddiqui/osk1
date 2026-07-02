package io.openskeleton.backend.common;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Reusable persistence base class for every domain entity (OSK-84).
 *
 * <p>It centralises three cross-cutting concerns so each entity does not re-implement
 * them (the {@code users} table is the first adopter — see {@code User} — and future
 * tables just {@code extends BaseEntity}):
 *
 * <ul>
 *   <li><b>Audit timestamps</b> — {@code createdAt} is stamped once on INSERT and never
 *       updated ({@code updatable = false}); {@code updatedAt} is advanced on every
 *       INSERT and UPDATE. Both are set by the {@link #onCreate()} / {@link #onUpdate()}
 *       JPA lifecycle callbacks so the app supplies the values rather than relying on
 *       the column {@code DEFAULT now()} (which would require omitting the column from
 *       the INSERT). This behaviour was moved here from OSK-60's {@code User} so it is
 *       inherited rather than duplicated per entity.</li>
 *   <li><b>Soft delete</b> — {@code deletedAt} is the delete marker: {@code null} means
 *       the row is active (the common case), a timestamp means it is logically deleted.
 *       Rows are never physically removed. Normal reads exclude deleted rows by default
 *       (adopters annotate the concrete entity with {@code @SQLRestriction("deleted_at
 *       is null")}); {@link #markDeleted()} / {@link #restore()} flip the marker and a
 *       restore path brings a row back.</li>
 *   <li><b>Optimistic concurrency</b> — the {@link Version @Version} {@code version}
 *       counter lets Hibernate detect a concurrent (stale) update. Each UPDATE carries
 *       {@code WHERE ... AND version = ?}; if another transaction already advanced the
 *       version, zero rows match and Hibernate raises a
 *       {@code StaleObjectStateException} (surfaced by Spring as
 *       {@code ObjectOptimisticLockingFailureException}) instead of silently
 *       overwriting the newer state. The global error handler maps that to HTTP 409.</li>
 * </ul>
 *
 * <p>The id is intentionally <i>not</i> declared here: id type and generation are
 * entity-specific (e.g. {@code User} uses a UUID surrogate key), so each concrete
 * entity owns its {@code @Id}. This class is a {@link MappedSuperclass} — it maps no
 * table of its own; its columns are folded into each subclass's table.
 *
 * <p>Plain Java (explicit getters/setters) is used rather than Lombok, which is not a
 * project dependency.
 */
@MappedSuperclass
public abstract class BaseEntity {

    /** When the row was first persisted. Set once on INSERT and never changed. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the row was last written. Advanced on every INSERT and UPDATE. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Soft-delete marker: {@code null} = active, a timestamp = logically deleted.
     * Nullable so the default (active) state is the absence of a value.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /**
     * Optimistic-locking version counter, managed by Hibernate via {@link Version}.
     * A primitive {@code long} (not {@code Long}) because the column is
     * {@code NOT NULL DEFAULT 0}: a fresh entity starts at 0 and Hibernate increments
     * it on each successful UPDATE.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /**
     * Stamp both audit timestamps immediately before the first INSERT, so the app
     * supplies non-null values rather than depending on the column defaults.
     */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Advance {@code updatedAt} on every UPDATE (including a soft-delete or restore). */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /**
     * Mark this entity as soft-deleted by stamping {@code deletedAt} with the current
     * instant. A no-op-safe operation for callers, but the caller should still persist
     * the change; the {@code deleted_at IS NULL} read restriction then hides the row.
     */
    public void markDeleted() {
        this.deletedAt = Instant.now();
    }

    /** Reverse a soft delete by clearing {@code deletedAt}, making the row active again. */
    public void restore() {
        this.deletedAt = null;
    }

    /** {@code true} when this entity is soft-deleted ({@code deletedAt} is set). */
    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }
}
