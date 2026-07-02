package io.openskeleton.backend.user;

import io.openskeleton.backend.web.NotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for {@link User} lifecycle operations (OSK-84).
 *
 * <p>It owns the soft-delete and restore transitions on top of the repository, so the
 * "never physically delete" policy and the optimistic-locking guarantees live behind a
 * single seam that later features (JIT provisioning OSK-76, RBAC OSK-71) build on
 * rather than manipulating {@code deleted_at} ad hoc.
 *
 * <p><b>Concurrency:</b> {@link #softDelete(UUID)} and {@link #restore(UUID)} both save
 * the entity through Hibernate, so each carries the {@code @Version} optimistic-lock
 * check inherited from {@code BaseEntity}. A concurrent stale-version write raises
 * {@code ObjectOptimisticLockingFailureException}, which the global error handler maps
 * to HTTP 409 — a stale change is rejected, never silently overwritten.
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Look up an <b>active</b> (non-soft-deleted) user by Firebase UID. Returns empty
     * for both "never existed" and "soft-deleted", since the default read surface
     * excludes deleted rows.
     */
    @Transactional(readOnly = true)
    public Optional<User> findActiveByFirebaseUid(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid);
    }

    /**
     * Soft-delete the active user with the given id: stamp {@code deletedAt} and
     * persist. After this the user is excluded from all default reads. Idempotency is
     * intentional — an already-deleted (or unknown) id yields a
     * {@link NotFoundException} (404), because the default finder cannot see a
     * soft-deleted row, so a second delete is a no-op-by-404 rather than a silent
     * success.
     *
     * @throws NotFoundException if no active user exists for the id
     */
    @Transactional
    public User softDelete(UUID id) {
        User user =
                userRepository.findById(id).orElseThrow(() -> new NotFoundException("No active user with id " + id));
        user.markDeleted();
        return userRepository.save(user);
    }

    /**
     * Restore a previously soft-deleted user: locate it via the deleted-inclusive
     * lookup (bypassing the default {@code deleted_at is null} restriction), clear
     * {@code deletedAt}, and persist so it becomes visible to normal reads again.
     *
     * @throws NotFoundException if no user (active or deleted) exists for the id
     */
    @Transactional
    public User restore(UUID id) {
        User user = userRepository
                .findByIdIncludingDeleted(id)
                .orElseThrow(() -> new NotFoundException("No user with id " + id));
        user.restore();
        return userRepository.save(user);
    }

    /**
     * Just-in-time provision the caller identified by a verified Firebase token (OSK-76).
     *
     * <p><b>Contract:</b> return the persisted {@link User} for {@code firebaseUid},
     * creating one on first sight. This is an <i>upsert-by-{@code firebase_uid}</i>: if an
     * active row already exists it is returned unchanged (the common, steady-state path);
     * otherwise a fresh {@code User} is inserted carrying the least-privilege defaults the
     * entity itself declares ({@code role = USER}, {@code enabled = true}). The
     * {@code displayName} starts {@code null} — the auth filter does not publish it, so it
     * is only set later via {@link #updateProfile(String, String)} (PATCH /me).
     *
     * <p><b>Idempotent under a first-request race (AC-1):</b> two concurrent first
     * requests for the same brand-new user can both observe "not found" and both attempt
     * the insert. The {@code firebase_uid} UNIQUE constraint lets exactly one win; the
     * loser's insert fails with {@link DataIntegrityViolationException}, which we catch and
     * turn into a re-read of the now-committed row — so the race resolves to "found", never
     * a duplicate row and never a surfaced 500.
     *
     * <p><b>Why this method is deliberately NOT {@code @Transactional}:</b> each repository
     * call therefore runs in its own transaction, and that isolation is exactly what makes
     * the recovery possible. The losing insert's transaction rolls back on its own, so the
     * subsequent re-read runs in a fresh, un-poisoned transaction and can see the row the
     * winner committed. Wrapping the whole method in a single transaction would instead
     * mark that transaction rollback-only on the constraint violation, and the re-read
     * would fail too.
     *
     * @param firebaseUid the verified Firebase uid (never {@code null} — a handler is only
     *     reached for an authenticated caller)
     * @param email the caller's email from the verified token, or {@code null}
     * @return the persisted user for this uid (existing or freshly created)
     */
    public User provisionFromToken(String firebaseUid, String email) {
        // Steady-state fast path: the user was provisioned on an earlier request.
        Optional<User> existing = userRepository.findByFirebaseUid(firebaseUid);
        if (existing.isPresent()) {
            return existing.get();
        }
        // First sight: try to insert. saveAndFlush forces the INSERT — and thus any
        // unique-constraint violation — to surface HERE, synchronously, rather than being
        // deferred to a later commit where this method could no longer catch it.
        try {
            User provisioned = new User(firebaseUid, email, null);
            return userRepository.saveAndFlush(provisioned);
        } catch (DataIntegrityViolationException race) {
            // A concurrent first request won the insert and tripped the firebase_uid
            // UNIQUE constraint for us. The row now exists and is committed: re-read and
            // return it so the outcome is "found" (idempotent). If it is somehow STILL
            // absent, the violation was not the uid race (a different constraint), so we
            // have nothing to return — rethrow rather than invent a result.
            return userRepository.findByFirebaseUid(firebaseUid).orElseThrow(() -> race);
        }
    }

    /**
     * Update the mutable profile of the caller's persisted user (OSK-76, PATCH /me).
     *
     * <p>Today the only client-settable field is {@code displayName}; the identity fields
     * ({@code firebaseUid}, {@code email}, {@code role}, {@code enabled}) are intentionally
     * NOT touched here — they are derived from the verified token or governed by other
     * flows, never from a request body.
     *
     * <p>Runs in a single transaction so the load-mutate-save is atomic and carries the
     * {@code @Version} optimistic-lock check inherited from {@code BaseEntity}: a concurrent
     * stale write is rejected (409) rather than silently overwritten. The caller is expected
     * to already exist — the controller provisions first — so a missing row is a genuine
     * {@link NotFoundException} (404).
     *
     * @param firebaseUid the verified uid identifying which user to update
     * @param displayName the new display name (may be {@code null} to clear it)
     * @return the updated, persisted user
     * @throws NotFoundException if no active user exists for the uid
     */
    @Transactional
    public User updateProfile(String firebaseUid, String displayName) {
        User user = userRepository
                .findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new NotFoundException("No active user with firebaseUid " + firebaseUid));
        user.setDisplayName(displayName);
        return userRepository.save(user);
    }
}
