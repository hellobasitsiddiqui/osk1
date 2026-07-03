package io.openskeleton.backend.admin;

import io.openskeleton.backend.audit.AuditAction;
import io.openskeleton.backend.audit.AuditService;
import io.openskeleton.backend.user.ProfileChange;
import io.openskeleton.backend.user.ProfileUpdate;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.User.Role;
import io.openskeleton.backend.user.UserRepository;
import io.openskeleton.backend.web.NotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
 * <p><b>Concurrency.</b> {@link #changeRole(UUID, Role, String)} and
 * {@link #setEnabled(UUID, boolean, String)} load-mutate-save through Hibernate, so each carries the
 * {@code @Version} optimistic-lock check inherited from {@code BaseEntity}: a concurrent
 * stale write is rejected with a 409 by the global handler rather than silently overwritten.
 *
 * <p><b>Audit logging (OSK-93).</b> The two mutating methods are the natural call sites for
 * "who changed whose role / enabled flag", so each now appends a single event to the
 * append-only audit log through {@link AuditService#record} — {@code ROLE_CHANGED} (with
 * {@code {old, new}} role metadata), {@code ACCOUNT_ENABLED} or {@code ACCOUNT_DISABLED} — at
 * the exact seam OSK-71 left marked. The actor is the acting admin's Firebase uid (threaded in
 * from the controller, which reads it off the verified-token request attribute — never a
 * request body), and the target is the affected user's id. The {@code record(...)} call joins
 * each method's existing {@link Transactional} boundary, so the mutation and its audit row
 * commit atomically or roll back together (a rejected optimistic-lock write leaves no orphan
 * audit event). Reads ({@link #list(Pageable)} / {@link #get(UUID)}) are not security mutations
 * and are intentionally not audited.
 */
@Service
public class AdminUserService {

    /**
     * Coarse audit {@code target_type} stamped on every admin user-management event: these
     * actions always target a {@code users} row (the specific id rides in {@code target_id}).
     * Matches the {@code "USER"} descriptor OSK-80's tests exercise for these actions.
     */
    private static final String TARGET_TYPE_USER = "USER";

    // JSONB metadata keys for a role change, mirroring the {old, new} shape the profile-change
    // history (OSK-99) already uses, so the stored audit metadata reads consistently.
    private static final String META_OLD = "old";
    private static final String META_NEW = "new";

    private final UserRepository userRepository;
    private final AuditService auditService;

    public AdminUserService(UserRepository userRepository, AuditService auditService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
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
     * @param id               the target user's id
     * @param role             the new role (never {@code null} — the controller validates the body)
     * @param actorFirebaseUid the acting admin's Firebase uid, recorded as the audit actor
     *     (may be {@code null} only for a system-originated call; normal admin calls always
     *     carry the verified-token uid)
     * @return the updated, persisted user
     * @throws NotFoundException if no active user has that id (404)
     */
    @Transactional
    public User changeRole(UUID id, Role role, String actorFirebaseUid) {
        Objects.requireNonNull(role, "role");
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("No user with id " + id));
        Role previousRole = user.getRole();
        user.setRole(role);
        User saved = userRepository.save(user);
        // Append the role change to the append-only audit log (OSK-93). Runs inside this
        // @Transactional, so the users update and its audit row commit atomically. The
        // {old, new} metadata captures what the role changed from and to.
        auditService.record(
                actorFirebaseUid,
                AuditAction.ROLE_CHANGED,
                TARGET_TYPE_USER,
                id.toString(),
                roleChangeMetadata(previousRole, role));
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
     * @param id               the target user's id
     * @param enabled          the desired enabled state
     * @param actorFirebaseUid the acting admin's Firebase uid, recorded as the audit actor
     *     (may be {@code null} only for a system-originated call; normal admin calls always
     *     carry the verified-token uid)
     * @return the updated, persisted user
     * @throws NotFoundException if no active user has that id (404)
     */
    @Transactional
    public User setEnabled(UUID id, boolean enabled, String actorFirebaseUid) {
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("No user with id " + id));
        user.setEnabled(enabled);
        User saved = userRepository.save(user);
        // Append the enable/disable to the append-only audit log (OSK-93), inside this
        // @Transactional so it commits atomically with the users update. The action name
        // itself (ACCOUNT_ENABLED vs ACCOUNT_DISABLED) carries the whole story, so no extra
        // metadata is needed.
        auditService.record(
                actorFirebaseUid,
                enabled ? AuditAction.ACCOUNT_ENABLED : AuditAction.ACCOUNT_DISABLED,
                TARGET_TYPE_USER,
                id.toString());
        return saved;
    }

    /**
     * Edit ANOTHER user's self-service profile fields (OSK-85) — the admin-facing counterpart
     * to a user editing their own profile via {@code PATCH /api/v1/me} (OSK-67). An admin may
     * change any user's {@code displayName} plus the richer profile fields (first/last name,
     * city, age, phone, notification preference, timezone, locale); the identity/authorization
     * fields ({@code firebaseUid}, {@code email}, {@code role}, {@code enabled},
     * {@code accountType}) are intentionally NOT touched here — role/enabled have their own
     * dedicated endpoints, and identity comes from the verified token, never a body.
     *
     * <p><b>Sparse (partial) update.</b> The {@link ProfileUpdate} carries one component per
     * editable field; a {@code null} component means "leave this field unchanged" and a
     * non-null one is the requested value (already validated at the API boundary). A field is
     * written only when the requested value genuinely differs from the current one
     * ({@link Objects#equals} guard), so an idempotent edit that resubmits the current values is
     * a <b>no-op</b> — no UPDATE and no audit event — exactly like {@code UserService.updateProfile}.
     *
     * <p><b>Concurrency.</b> Runs in a single transaction so the load-mutate-save is atomic and
     * carries the {@code @Version} optimistic-lock check inherited from {@code BaseEntity}: a
     * concurrent stale write is rejected (409) rather than silently overwritten.
     *
     * <p><b>Audit (OSK-85).</b> When at least one field genuinely changes, a SINGLE
     * {@link AuditAction#PROFILE_EDITED} event is appended through {@link AuditService#record} in
     * the SAME transaction (so the profile write and its audit row commit atomically). Unlike
     * the self-service {@code PROFILE_UPDATED} history — one event per field, actor == the user
     * themselves — this records the acting <i>admin</i> as the actor, the edited user's id as the
     * target, and ALL changed fields in one metadata map ({@code {field: {old, new}, ...}}), so
     * "which admin changed what on whom" is a single row. The changed-field names reuse the
     * {@link ProfileChange} field vocabulary so the admin edit and the self-edit name fields
     * identically.
     *
     * @param id               the target user's id
     * @param update           the requested field changes (null components left unchanged)
     * @param actorFirebaseUid the acting admin's Firebase uid, recorded as the audit actor
     *     (may be {@code null} only for a system-originated call; normal admin calls always
     *     carry the verified-token uid)
     * @return the updated, persisted user (or the unchanged user when nothing changed)
     * @throws NotFoundException if no active user has that id (404)
     */
    @Transactional
    public User editProfile(UUID id, ProfileUpdate update, String actorFirebaseUid) {
        User user = userRepository.findById(id).orElseThrow(() -> new NotFoundException("No user with id " + id));

        // Apply each requested change (if genuinely different), collecting a {field: {old, new}}
        // map so a single PROFILE_EDITED event captures the whole admin edit. One helper
        // centralises the null-skip + changed-vs-unchanged logic so every field behaves alike.
        Map<String, Object> changed = new LinkedHashMap<>();
        applyIfChanged(
                changed,
                ProfileChange.FIELD_DISPLAY_NAME,
                update.displayName(),
                user::getDisplayName,
                user::setDisplayName);
        applyIfChanged(
                changed, ProfileChange.FIELD_FIRST_NAME, update.firstName(), user::getFirstName, user::setFirstName);
        applyIfChanged(changed, ProfileChange.FIELD_LAST_NAME, update.lastName(), user::getLastName, user::setLastName);
        applyIfChanged(changed, ProfileChange.FIELD_CITY, update.city(), user::getCity, user::setCity);
        applyIfChanged(changed, ProfileChange.FIELD_AGE, update.age(), user::getAge, user::setAge);
        applyIfChanged(changed, ProfileChange.FIELD_PHONE, update.phone(), user::getPhone, user::setPhone);
        applyIfChanged(
                changed,
                ProfileChange.FIELD_NOTIFICATION_PREFERENCE,
                update.notificationPreference(),
                user::getNotificationPreference,
                user::setNotificationPreference);
        applyIfChanged(changed, ProfileChange.FIELD_TIMEZONE, update.timezone(), user::getTimezone, user::setTimezone);
        applyIfChanged(changed, ProfileChange.FIELD_LOCALE, update.locale(), user::getLocale, user::setLocale);

        // Nothing genuinely changed → no write and no audit (idempotent edit is a no-op).
        if (changed.isEmpty()) {
            return user;
        }

        User saved = userRepository.save(user);
        auditService.record(actorFirebaseUid, AuditAction.PROFILE_EDITED, TARGET_TYPE_USER, id.toString(), changed);
        return saved;
    }

    /**
     * Apply one requested field change to {@code user} when it is both present and genuinely
     * different, recording the before/after under {@code field} in {@code changed} for the
     * single PROFILE_EDITED metadata. A {@code null} {@code requested} is the sparse "leave
     * unchanged" signal, and an {@link Objects#equals} match short-circuits a redundant change —
     * so neither writes. The {@code {old, new}} sub-map mirrors the {@code {old, new}} shape the
     * role-change metadata already uses.
     */
    private static <T> void applyIfChanged(
            Map<String, Object> changed, String field, T requested, Supplier<T> current, Consumer<T> setter) {
        if (requested == null) {
            return; // caller did not ask to change this field (sparse) — leave it.
        }
        T old = current.get();
        if (Objects.equals(old, requested)) {
            return; // already the requested value — no genuine change, so no write/audit.
        }
        setter.accept(requested);
        Map<String, Object> delta = new LinkedHashMap<>();
        delta.put(META_OLD, asString(old));
        delta.put(META_NEW, asString(requested));
        changed.put(field, delta);
    }

    /** Null-safe stringification for audit metadata — a null value stays null, not "null". */
    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * Build the {@code audit_events.metadata} map for a role change: {@code {old, new}} role
     * names. A {@link LinkedHashMap} keeps a stable {@code old, new} key order in the stored
     * JSON. Roles are recorded by their enum {@code name()} (stable and human-readable),
     * matching how the {@code role} column itself is persisted.
     */
    private static Map<String, Object> roleChangeMetadata(Role oldRole, Role newRole) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(META_OLD, oldRole.name());
        metadata.put(META_NEW, newRole.name());
        return metadata;
    }
}
