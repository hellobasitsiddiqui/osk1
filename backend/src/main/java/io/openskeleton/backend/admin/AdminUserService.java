package io.openskeleton.backend.admin;

import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.User.Role;
import io.openskeleton.backend.user.UserRepository;
import io.openskeleton.backend.web.NotFoundException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service backing the admin user-management endpoints (OSK-71).
 *
 * <p>It is the single place the admin surface reads and mutates <i>other</i> users by their
 * primary-key {@code id} (as opposed to {@link io.openskeleton.backend.user.UserService},
 * which operates on the <i>caller's own</i> row by {@code firebaseUid}). It reuses the
 * shared {@link UserRepository} rather than duplicating persistence logic, so the same
 * soft-delete read restriction and optimistic-locking guarantees apply here as everywhere
 * else.
 *
 * <p><b>Authorization is enforced upstream.</b> Every method here is only ever reached
 * through {@code AdminUserController}, whose handlers are guarded by
 * {@code @PreAuthorize("hasRole('ADMIN')")}. This service therefore assumes the caller is
 * already an authenticated admin and does not re-check the role; it is intentionally not
 * exposed to non-admin callers by any other route.
 *
 * <p><b>Soft-delete semantics (OSK-84).</b> {@link #list(Pageable)} and {@link #get(UUID)}
 * use the default repository finders, which carry {@code User}'s
 * {@code @SQLRestriction("deleted_at is null")}, so soft-deleted users are invisible to the
 * admin surface (a deleted user reads as "not found"). Reactivating a deleted account is the
 * restore flow's job (OSK-84), not this ticket's.
 *
 * <p><b>Concurrency.</b> {@link #changeRole(UUID, Role)} and
 * {@link #setEnabled(UUID, boolean)} load-mutate-save through Hibernate, so each carries the
 * {@code @Version} optimistic-lock check inherited from {@code BaseEntity}: a concurrent
 * stale write is rejected with a 409 by the global handler rather than silently overwritten.
 *
 * <p><b>Audit logging is deliberately NOT done here — see OSK-93.</b> The mutating methods
 * are the natural call sites for "who changed whose role / enabled flag", but recording those
 * events into the append-only audit log ({@code AuditService.record(...)} with
 * {@code ROLE_CHANGED} / {@code ACCOUNT_ENABLED} / {@code ACCOUNT_DISABLED}) is a separate
 * ticket. Each mutation below marks the exact seam with a {@code TODO(OSK-93)} so the wiring
 * lands in one obvious place without pre-empting that ticket's scope.
 */
@Service
public class AdminUserService {

    private final UserRepository userRepository;

    public AdminUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * One page of users for the admin list, honouring the shared pagination convention
     * (OSK-87 — {@code page}/{@code size}/{@code sort}, size hard-capped by
     * {@code PageableConfig}). Read-only; soft-deleted users are excluded by the entity's
     * read restriction.
     *
     * @param pageable the resolved page request (never {@code null} — the resolver supplies a
     *     deterministic fallback when the caller omits the params)
     * @return the requested page of active users
     */
    @Transactional(readOnly = true)
    public Page<User> list(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    /**
     * Load a single active user by primary-key id for the admin detail view.
     *
     * @param id the user's UUID id
     * @return the matching active user
     * @throws NotFoundException if no active (non-soft-deleted) user has that id — mapped to a
     *     404 {@code problem+json} by the global handler
     */
    @Transactional(readOnly = true)
    public User get(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException("No user with id " + id));
    }

    /**
     * Set a user's authorization {@link Role} and persist it.
     *
     * <p><b>What this changes (and what it does not).</b> This updates the <i>persisted</i>
     * role on the {@code users} row — the value surfaced by {@code GET /api/v1/me} and the
     * admin views, and the platform's source of truth for a user's role. It does <b>not</b>
     * retroactively change the authorities on tokens the user has <i>already</i> been issued:
     * request-time authorities are derived by {@link io.openskeleton.backend.auth.RoleClaimMapper}
     * from the Firebase
     * {@code role} custom claim on the presented ID token (OSK-79), not from this column. To
     * propagate the new role onto the user's next ID token, an admin flow must also write the
     * Firebase claim via {@link io.openskeleton.backend.auth.RoleService#setRole} — the seam
     * OSK-79 landed for exactly this. That propagation needs a live {@code FirebaseAuth}
     * (Application Default Credentials), which is out of scope for this DB-backed ticket, so
     * it is intentionally left as a follow-up rather than wired into a credential-free,
     * test-exercised endpoint.
     *
     * @param id   the target user's id
     * @param role the new role (never {@code null} — the controller validates the body)
     * @return the updated, persisted user
     * @throws NotFoundException if no active user has that id (404)
     */
    @Transactional
    public User changeRole(UUID id, Role role) {
        Objects.requireNonNull(role, "role");
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("No user with id " + id));
        user.setRole(role);
        User saved = userRepository.save(user);
        // TODO(OSK-93): record an audit event here —
        // auditService.record(actorUid, AuditAction.ROLE_CHANGED, "USER", id.toString(), {old, new}).
        // Left unwired on purpose: audit logging for admin mutations is that ticket's scope.
        return saved;
    }

    /**
     * Enable or disable a user's account and persist it.
     *
     * <p><b>Why disabling actually takes effect.</b> The persisted {@code enabled} flag is not
     * cosmetic: {@link io.openskeleton.backend.auth.FirebaseAuthenticationFilter} consults it on every authenticated
     * {@code /api/v1/**} request and rejects a disabled user's request with a 403 before any
     * handler runs (a soft off-switch that requires no token revocation). So flipping this to
     * {@code false} here is the mechanism that locks an account out; flipping it back to
     * {@code true} restores access on the user's next request.
     *
     * @param id      the target user's id
     * @param enabled the desired enabled state
     * @return the updated, persisted user
     * @throws NotFoundException if no active user has that id (404)
     */
    @Transactional
    public User setEnabled(UUID id, boolean enabled) {
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("No user with id " + id));
        user.setEnabled(enabled);
        User saved = userRepository.save(user);
        // TODO(OSK-93): record an audit event here —
        // auditService.record(actorUid, enabled ? AuditAction.ACCOUNT_ENABLED : AuditAction.ACCOUNT_DISABLED,
        //         "USER", id.toString(), null). Left unwired on purpose (audit logging is OSK-93).
        return saved;
    }
}
