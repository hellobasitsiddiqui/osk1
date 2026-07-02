package io.openskeleton.backend.user;

import io.openskeleton.backend.web.NotFoundException;
import java.util.Optional;
import java.util.UUID;
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
}
