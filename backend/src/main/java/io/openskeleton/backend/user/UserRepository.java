package io.openskeleton.backend.user;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JPA repository for {@link User} (OSK-60, extended in OSK-84).
 *
 * <p>The id type is {@link UUID} to match the entity's UUID primary key. Beyond the
 * standard CRUD that {@link JpaRepository} supplies, the one domain lookup the
 * platform needs is by Firebase UID — the natural identity every authenticated
 * caller carries — implemented as a derived query below.
 *
 * <p><b>Soft-delete semantics (OSK-84):</b> every finder here — the inherited CRUD and
 * {@link #findByFirebaseUid(String)} — automatically excludes soft-deleted rows,
 * because {@link User} carries {@code @SQLRestriction("deleted_at is null")} which
 * Hibernate appends to the SELECT it generates. So the default read surface only ever
 * returns <i>active</i> users. The single deliberate exception is
 * {@link #findByIdIncludingDeleted(UUID)}, a native query used only by the restore
 * path to locate a row regardless of its soft-delete state.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Find an <b>active</b> user by their unique Firebase UID. Returns
     * {@link Optional#empty()} when no active user has been provisioned for that UID
     * (including when the user exists but has been soft-deleted — the
     * {@code @SQLRestriction} on {@link User} filters it out). Spring Data derives the
     * query from the method name against the {@code firebase_uid} unique column.
     */
    Optional<User> findByFirebaseUid(String firebaseUid);

    /**
     * Load a user by id <b>regardless of soft-delete state</b> — the restore-path
     * escape hatch. This is a native query precisely so it bypasses the entity-level
     * {@code @SQLRestriction("deleted_at is null")} (which only applies to Hibernate's
     * generated HQL), letting the service locate a soft-deleted row in order to
     * reactivate it. Normal code must not use this; it is the one place deleted rows
     * are intentionally visible.
     */
    @Query(value = "SELECT * FROM users WHERE id = :id", nativeQuery = true)
    Optional<User> findByIdIncludingDeleted(@Param("id") UUID id);

    /**
     * Stamp {@code last_active_at = :now} for the user with {@code firebaseUid}, but ONLY when their
     * current value is stale — {@code NULL} or older than {@code :threshold} (OSK-73). This is the
     * authoritative, race- and restart-safe half of the last-active throttle: the {@code WHERE}
     * predicate means at most one real row write happens per user per throttle window, even across
     * instances and even if two requests arrive concurrently (the second matches zero rows).
     *
     * <p>A <b>bulk JPQL update</b> is used deliberately: it writes only this one column and bypasses
     * the {@code @Version}/{@code @PreUpdate} lifecycle, so a heartbeat neither advances
     * {@code updated_at} (which should track profile edits) nor trips optimistic locking against a
     * concurrent profile save.
     *
     * @param firebaseUid the user to stamp (unique key)
     * @param now the instant to record as last-active
     * @param threshold only update when the stored value is NULL or strictly before this
     * @return the number of rows updated — {@code 1} if a stamp was written, {@code 0} if the row was
     *     absent or already fresh within the window
     */
    @Modifying
    @Query("update User u set u.lastActiveAt = :now "
            + "where u.firebaseUid = :firebaseUid "
            + "and (u.lastActiveAt is null or u.lastActiveAt < :threshold)")
    int touchLastActive(
            @Param("firebaseUid") String firebaseUid, @Param("now") Instant now, @Param("threshold") Instant threshold);
}
