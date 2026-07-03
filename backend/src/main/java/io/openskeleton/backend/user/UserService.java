package io.openskeleton.backend.user;

import io.openskeleton.backend.audit.AuditService;
import io.openskeleton.backend.web.NotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
    private final AuditService auditService;

    public UserService(UserRepository userRepository, AuditService auditService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
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
     * is only set later via {@link #updateProfile(String, ProfileUpdate)} (PATCH /me).
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
        // Identity-preserving overload: callers that only carry an email (the current login
        // flow — MeController/DeviceController read uid+email from the auth-filter attributes)
        // reconcile by email alone. The canonical three-argument form does the real work.
        return provisionFromToken(firebaseUid, email, null);
    }

    /**
     * Just-in-time provision the caller, reconciling by <b>canonical identity</b> so that one
     * human maps to exactly one account regardless of how they signed in (OSK-163).
     *
     * <p>This is the account-identity core of the ticket. The V2 {@code firebase_uid} UNIQUE
     * key guarantees one row per Firebase <i>credential</i>, but a single person can hold
     * several uids for the SAME email/phone (an email/password account plus a later Google or
     * phone sign-in that Firebase issues a distinct uid for). Left alone, each new uid would
     * mint its own row — a duplicate account. So provisioning resolves in three ordered steps:
     *
     * <ol>
     *   <li><b>By uid (steady state).</b> If a row already exists for this exact uid, return it
     *       unchanged — the overwhelmingly common path, unchanged from the original OSK-76
     *       behaviour.</li>
     *   <li><b>By identity (reconcile).</b> Otherwise look the caller up by their canonical
     *       identity — email (case-insensitively) first, then phone. If a DIFFERENT uid already
     *       owns that identity, we have met this human before under another credential: adopt
     *       the new uid onto that existing row (see the linking policy below) and return it —
     *       <b>no new row</b>. This is what makes "sign in via email OR phone → same account".</li>
     *   <li><b>Insert (genuinely new).</b> Only when neither the uid nor the identity is known do
     *       we insert a fresh row, carrying the entity's least-privilege defaults
     *       ({@code role = USER}, {@code enabled = true}) plus whatever identity fields the token
     *       presented ({@code email}, {@code phone}).</li>
     * </ol>
     *
     * <p><b>Linking policy — "the identity owns the account; the newest uid is adopted onto it".</b>
     * The canonical key is the email/phone, NOT the Firebase uid. When a known identity signs in
     * under a new uid, we <i>relink</i> — overwrite {@code firebase_uid} on the existing row with
     * the new uid — rather than create a second row or return a row whose uid mismatches the
     * caller. Relinking (not merely returning the old row) is deliberate and necessary: every
     * other part of the system keys off {@code firebase_uid} (the disabled-account gate in the
     * auth filter, {@link #updateProfile}, {@link #updateLifecycle}, audit actor ids), so the
     * persisted uid MUST track the credential the caller is actually presenting or those lookups
     * would 404. The trade-off — the previously stored uid is forgotten — is acceptable: it was
     * only ever an alias for the same human, and a later sign-in under it simply relinks back,
     * always resolving to the one row. Relinking touches ONLY {@code firebase_uid}; it never
     * overwrites the row's stored email/phone/profile, so a user's data is never clobbered by the
     * act of signing in through a second provider.
     *
     * <p><b>Race + genuine conflict.</b> As with the original design this method is deliberately
     * NOT {@code @Transactional} (see the sibling note below) so each repository call is its own
     * transaction and a failed insert rolls back in isolation, leaving the follow-up re-read able
     * to see a concurrently-committed row. Both the insert AND the relink use {@code saveAndFlush}
     * so any unique-constraint violation surfaces HERE, synchronously. On a violation we recover
     * by re-reading — first by uid, then by identity — and return/relink whatever a concurrent
     * request committed, so a race resolves to the single winning row (never a duplicate, never a
     * surfaced 500). If recovery still finds nothing, the violation was a GENUINE conflict we
     * cannot reconcile, so the {@link DataIntegrityViolationException} propagates and
     * {@code GlobalExceptionHandler} renders it as an RFC-7807 {@code 409 Conflict} — the correct
     * signal that the request clashed with existing state.
     *
     * <p><b>Why NOT {@code @Transactional}:</b> each repository call runs in its own transaction,
     * and that isolation is exactly what makes the recovery possible — a losing insert's
     * transaction rolls back on its own, so the subsequent re-read runs in a fresh, un-poisoned
     * transaction and can see the row the winner committed. Wrapping the whole method in a single
     * transaction would instead mark it rollback-only on the first violation, and the re-read
     * would fail too.
     *
     * @param firebaseUid the verified Firebase uid (never {@code null} — a handler is only
     *     reached for an authenticated caller)
     * @param email the caller's email from the verified token, or {@code null}
     * @param phone the caller's verified E.164 phone from the token, or {@code null}
     * @return the persisted user for this identity (existing, relinked, or freshly created)
     */
    public User provisionFromToken(String firebaseUid, String email, String phone) {
        // 1) Steady-state fast path: the user was provisioned on an earlier request under
        //    this same uid.
        Optional<User> byUid = userRepository.findByFirebaseUid(firebaseUid);
        if (byUid.isPresent()) {
            return byUid.get();
        }
        // 2) Reconcile: a DIFFERENT uid may already own this canonical identity (email/phone).
        //    Resolve to that account and adopt the new uid onto it (linking policy) — never a dup.
        Optional<User> byIdentity = findByIdentity(email, phone);
        if (byIdentity.isPresent()) {
            return relinkUid(byIdentity.get(), firebaseUid);
        }
        // 3) Genuinely new identity: insert. saveAndFlush forces the INSERT — and thus any
        //    unique-constraint violation — to surface HERE, synchronously, rather than being
        //    deferred to a later commit where this method could no longer catch it.
        try {
            User provisioned = new User(firebaseUid, email, null);
            provisioned.setPhone(phone); // capture the identity's phone so future phone sign-ins reconcile
            return userRepository.saveAndFlush(provisioned);
        } catch (DataIntegrityViolationException race) {
            // A concurrent request won the create and tripped a UNIQUE constraint for us
            // (firebase_uid, or the OSK-163 email/phone identity index). The winning row is now
            // committed: recover by re-reading — first by uid, then by identity — and return or
            // relink it so the outcome is "found" (idempotent, no duplicate). If it is somehow
            // STILL absent, the violation was a GENUINE, unreconcilable conflict, so we rethrow
            // (the handler maps it to a 409) rather than invent a result.
            Optional<User> recoveredByUid = userRepository.findByFirebaseUid(firebaseUid);
            if (recoveredByUid.isPresent()) {
                return recoveredByUid.get();
            }
            User recoveredByIdentity = findByIdentity(email, phone).orElseThrow(() -> race);
            return relinkUid(recoveredByIdentity, firebaseUid);
        }
    }

    /**
     * Resolve an existing <b>active</b> account by canonical identity (OSK-163): email first
     * (case-insensitively), then phone. Email takes precedence because it is the identity the
     * login flow always threads through provisioning; phone is the fallback for phone-only
     * sign-ins. A {@code null}/blank field is skipped (it identifies no one), and when neither
     * field is present the result is empty — an anonymous, identity-less caller reconciles to
     * nobody and is treated as genuinely new.
     */
    private Optional<User> findByIdentity(String email, String phone) {
        if (email != null && !email.isBlank()) {
            Optional<User> byEmail = userRepository.findByEmailIgnoreCase(email);
            if (byEmail.isPresent()) {
                return byEmail;
            }
        }
        if (phone != null && !phone.isBlank()) {
            return userRepository.findByPhone(phone);
        }
        return Optional.empty();
    }

    /**
     * Apply the linking policy: adopt {@code firebaseUid} onto {@code existing} (the account that
     * already owns the caller's identity) and persist, so the canonical row now tracks the
     * credential the caller is actually presenting. Only {@code firebase_uid} is touched — the
     * stored email/phone/profile are left intact. {@code saveAndFlush} surfaces any constraint
     * violation synchronously so the caller's race-recovery can handle it. A no-op when the uid
     * already matches (defensive; the callers only relink on a genuine mismatch).
     */
    private User relinkUid(User existing, String firebaseUid) {
        if (firebaseUid.equals(existing.getFirebaseUid())) {
            return existing;
        }
        existing.setFirebaseUid(firebaseUid);
        return userRepository.saveAndFlush(existing);
    }

    /**
     * Update the mutable profile of the caller's persisted user (OSK-76 displayName, extended
     * to the richer profile fields by OSK-67: first/last name, city, age, phone, notification
     * preference, timezone, locale). The identity fields ({@code firebaseUid}, {@code email},
     * {@code role}, {@code enabled}, {@code accountType}) are intentionally NOT touched here —
     * they are derived from the verified token or governed by other flows, never a request body.
     *
     * <p><b>Sparse (partial) update.</b> The {@link ProfileUpdate} carries one component per
     * editable field; a {@code null} component means "leave this field unchanged" and a
     * non-null one is the requested value (already validated at the API boundary). So a PATCH
     * that touches only one field never clobbers the others. A field is written only when the
     * requested value genuinely differs from the current one ({@link Objects#equals} guard):
     * an idempotent PATCH that resubmits current values is a <b>no-op</b> — no UPDATE, no
     * history — because history records genuine edits, not every request.
     *
     * <p>Runs in a single transaction so the load-mutate-save is atomic and carries the
     * {@code @Version} optimistic-lock check inherited from {@code BaseEntity}: a concurrent
     * stale write is rejected (409) rather than silently overwritten. The caller is expected
     * to already exist — the controller provisions first — so a missing row is a genuine
     * {@link NotFoundException} (404).
     *
     * <p><b>Change history (OSK-99), now per changed field.</b> Every field that actually
     * changes appends its own {@code PROFILE_UPDATED} event through the audit seam
     * ({@link AuditService#record}) in the SAME transaction — so the profile write and all its
     * history rows commit atomically (or roll back together). Each event records {@code who}
     * ({@code firebaseUid}), {@code what}
     * ({@link io.openskeleton.backend.audit.AuditAction#PROFILE_UPDATED}), the target user id,
     * and a {@code {field, old, new}} metadata payload encoded by {@link ProfileChange} — the
     * exact same shape {@code GET /api/v1/me/history} reads back, so the new fields appear in
     * history alongside the original {@code displayName}.
     *
     * @param firebaseUid the verified uid identifying which user to update
     * @param update the requested field changes (null components are left unchanged)
     * @return the updated, persisted user (or the unchanged user when nothing changed)
     * @throws NotFoundException if no active user exists for the uid
     */
    @Transactional
    public User updateProfile(String firebaseUid, ProfileUpdate update) {
        User user = userRepository
                .findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new NotFoundException("No active user with firebaseUid " + firebaseUid));

        // Apply each requested change (if genuinely different), collecting the per-field edits
        // so the history is recorded once, after the single save — mirroring the original
        // OSK-99 order (persist the change, then record it). One helper centralises the
        // null-skip + changed-vs-unchanged logic so every field behaves identically.
        List<FieldEdit> edits = new ArrayList<>();
        applyIfChanged(
                edits,
                ProfileChange.FIELD_DISPLAY_NAME,
                update.displayName(),
                user::getDisplayName,
                user::setDisplayName);
        applyIfChanged(
                edits, ProfileChange.FIELD_FIRST_NAME, update.firstName(), user::getFirstName, user::setFirstName);
        applyIfChanged(edits, ProfileChange.FIELD_LAST_NAME, update.lastName(), user::getLastName, user::setLastName);
        applyIfChanged(edits, ProfileChange.FIELD_CITY, update.city(), user::getCity, user::setCity);
        applyIfChanged(edits, ProfileChange.FIELD_AGE, update.age(), user::getAge, user::setAge);
        applyIfChanged(edits, ProfileChange.FIELD_PHONE, update.phone(), user::getPhone, user::setPhone);
        applyIfChanged(
                edits,
                ProfileChange.FIELD_NOTIFICATION_PREFERENCE,
                update.notificationPreference(),
                user::getNotificationPreference,
                user::setNotificationPreference);
        applyIfChanged(edits, ProfileChange.FIELD_TIMEZONE, update.timezone(), user::getTimezone, user::setTimezone);
        applyIfChanged(edits, ProfileChange.FIELD_LOCALE, update.locale(), user::getLocale, user::setLocale);

        // Nothing genuinely changed → no write and no history (idempotent PATCH is a no-op).
        if (edits.isEmpty()) {
            return user;
        }

        User saved = userRepository.save(user);
        String targetId = saved.getId().toString();
        for (FieldEdit edit : edits) {
            auditService.record(
                    firebaseUid,
                    ProfileChange.ACTION,
                    ProfileChange.TARGET_TYPE,
                    targetId,
                    ProfileChange.metadata(edit.field(), edit.oldValue(), edit.newValue()));
        }
        return saved;
    }

    /**
     * Apply the caller's account-lifecycle transitions to their persisted user (OSK-69):
     * mark onboarding complete, accept a terms version, mark age-verified. Identity fields and
     * the profile fields are untouched here — this method owns only the lifecycle state added by
     * {@code V8}.
     *
     * <p><b>Sparse (partial) update</b>, exactly like {@link #updateProfile}: a {@code null}
     * component in {@link LifecycleUpdate} means "leave unchanged"; a field is written only when
     * the requested value genuinely differs (an idempotent re-submit is a no-op — no UPDATE, no
     * history). Runs in a single transaction so the write and its history rows commit atomically
     * and carry the {@code @Version} optimistic-lock check inherited from {@code BaseEntity}.
     *
     * <p><b>Terms acceptance stamps a server timestamp.</b> When a new (different) terms version
     * is accepted, {@code termsAcceptedAt} is set to {@link Instant#now()} in the SAME write —
     * the timestamp is authoritative server state, never taken from the request. Re-accepting the
     * <i>same</i> version is a no-op (no new timestamp, no history), matching the idempotent-PATCH
     * convention. Only the version change is recorded to history (the timestamp is derived state).
     *
     * <p><b>Change history (OSK-99 reuse).</b> Each genuinely changed field appends its own
     * {@code PROFILE_UPDATED} event through {@link AuditService#record} — the same shape and feed
     * {@code GET /api/v1/me/history} reads — so lifecycle changes appear in history alongside the
     * profile edits.
     *
     * @param firebaseUid the verified uid identifying which user to update
     * @param update the requested lifecycle changes (null components are left unchanged)
     * @return the updated, persisted user (or the unchanged user when nothing changed)
     * @throws NotFoundException if no active user exists for the uid
     */
    @Transactional
    public User updateLifecycle(String firebaseUid, LifecycleUpdate update) {
        User user = userRepository
                .findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new NotFoundException("No active user with firebaseUid " + firebaseUid));

        List<FieldEdit> edits = new ArrayList<>();
        // The two booleans are plain value copies, so the shared helper applies them uniformly.
        applyIfChanged(
                edits,
                ProfileChange.FIELD_ONBOARDING_COMPLETED,
                update.onboardingCompleted(),
                user::isOnboardingCompleted,
                user::setOnboardingCompleted);
        // Terms acceptance is special: recording a new version also stamps a SERVER timestamp
        // (never client-supplied), so it can't go through the plain value-copy helper. Only a
        // genuinely different version writes — re-accepting the same version is a no-op.
        String requestedTermsVersion = update.termsAcceptedVersion();
        if (requestedTermsVersion != null && !Objects.equals(user.getTermsAcceptedVersion(), requestedTermsVersion)) {
            String oldVersion = user.getTermsAcceptedVersion();
            user.setTermsAcceptedVersion(requestedTermsVersion);
            user.setTermsAcceptedAt(Instant.now());
            edits.add(new FieldEdit(ProfileChange.FIELD_TERMS_ACCEPTED_VERSION, oldVersion, requestedTermsVersion));
        }
        applyIfChanged(
                edits,
                ProfileChange.FIELD_AGE_VERIFIED,
                update.ageVerified(),
                user::isAgeVerified,
                user::setAgeVerified);

        // Nothing genuinely changed → no write and no history (idempotent request is a no-op).
        if (edits.isEmpty()) {
            return user;
        }

        User saved = userRepository.save(user);
        String targetId = saved.getId().toString();
        for (FieldEdit edit : edits) {
            auditService.record(
                    firebaseUid,
                    ProfileChange.ACTION,
                    ProfileChange.TARGET_TYPE,
                    targetId,
                    ProfileChange.metadata(edit.field(), edit.oldValue(), edit.newValue()));
        }
        return saved;
    }

    /**
     * Apply one requested field change to {@code user} when it is both present and genuinely
     * different, recording the before/after into {@code edits} for later history. A
     * {@code null} {@code requested} is the sparse-PATCH "leave unchanged" signal, and a
     * {@link Objects#equals} match short-circuits a redundant change — so neither writes.
     *
     * @param edits accumulator the caller drains into the audit log after the save
     * @param field the {@link ProfileChange} field name recorded in history
     * @param requested the requested new value, or {@code null} to leave the field unchanged
     * @param current supplier of the field's current value (read before mutating)
     * @param setter setter that applies the new value to the entity
     * @param <T> the field's type (String, Integer, or an enum)
     */
    private static <T> void applyIfChanged(
            List<FieldEdit> edits, String field, T requested, Supplier<T> current, Consumer<T> setter) {
        if (requested == null) {
            return; // caller did not ask to change this field (sparse PATCH) — leave it.
        }
        T old = current.get();
        if (Objects.equals(old, requested)) {
            return; // already the requested value — no genuine change, so no write/history.
        }
        setter.accept(requested);
        edits.add(new FieldEdit(field, asString(old), asString(requested)));
    }

    /** Null-safe stringification for history metadata — a null value stays null, not "null". */
    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** A single applied field edit, held until the history is written after the save. */
    private record FieldEdit(String field, String oldValue, String newValue) {}

    /**
     * Mark the active user's {@link AccountType} — the reusable seam for flagging an
     * account as real vs test/synthetic (OSK-168).
     *
     * <p>This is deliberately just the setter: it applies whatever classification the
     * caller decides and persists it. It does <b>not</b> auto-classify by email or any
     * other heuristic — that policy belongs to the callers (the future test-email hook /
     * admin), which invoke this once they've decided. New users are never routed through
     * here; they default to {@link AccountType#REAL} via the entity field on
     * {@link #provisionFromToken(String, String)}.
     *
     * <p>Runs in a single transaction so the load-mutate-save is atomic and carries the
     * {@code @Version} optimistic-lock check inherited from {@code BaseEntity}: a concurrent
     * stale write is rejected (409) rather than silently overwritten. The caller is expected
     * to already exist, so a missing/soft-deleted row is a genuine {@link NotFoundException}
     * (404) — the default finder cannot see a soft-deleted row.
     *
     * @param firebaseUid the verified uid identifying which user to (re)classify
     * @param accountType the classification to apply (never {@code null})
     * @return the updated, persisted user
     * @throws NotFoundException if no active user exists for the uid
     */
    @Transactional
    public User markAccountType(String firebaseUid, AccountType accountType) {
        Objects.requireNonNull(accountType, "accountType");
        User user = userRepository
                .findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new NotFoundException("No active user with firebaseUid " + firebaseUid));
        user.setAccountType(accountType);
        return userRepository.save(user);
    }
}
