package io.openskeleton.backend.api;

import io.openskeleton.backend.account.FirebaseAccountService;
import io.openskeleton.backend.account.FirebaseAccountState;
import io.openskeleton.backend.audit.AuditEvent;
import io.openskeleton.backend.audit.AuditService;
import io.openskeleton.backend.auth.FirebaseAuthenticationFilter;
import io.openskeleton.backend.avatar.AvatarService;
import io.openskeleton.backend.avatar.AvatarUnavailableException;
import io.openskeleton.backend.avatar.InvalidAvatarException;
import io.openskeleton.backend.common.PagedResponse;
import io.openskeleton.backend.user.LifecycleUpdate;
import io.openskeleton.backend.user.NotificationPreference;
import io.openskeleton.backend.user.ProfileChange;
import io.openskeleton.backend.user.ProfileUpdate;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.UserService;
import io.openskeleton.backend.validation.ValidLocale;
import io.openskeleton.backend.validation.ValidTimezone;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated caller's own profile, backed by the persisted {@code users} row
 * (OSK-28, OSK-66, OSK-76): {@code GET /api/v1/me} and {@code PATCH /api/v1/me}, both
 * returning {@code {"uid": ..., "email": ..., "displayName": ..., "role": ...}}.
 *
 * <p><b>Why it exists / where it sits:</b> it is the concrete round-trip target proving
 * the Firebase auth seam plus JIT provisioning work end-to-end — a client presents its
 * ID token and gets back the profile the backend verified <i>and persisted</i> for it.
 * It lives in {@code io.openskeleton.backend.api}, so it inherits the {@code /api/v1}
 * prefix (see {@link ApiVersioningConfig}) and, being under {@code /api/v1/**}, is
 * authenticated by {@link FirebaseAuthenticationFilter} — these handlers therefore only
 * ever run for an already-verified caller (anonymous/invalid → 401 at the filter).
 *
 * <p><b>Identity comes ONLY from the verified token.</b> The {@code uid} and
 * {@code email} are read back from the request attributes the auth filter published
 * ({@link FirebaseAuthenticationFilter#UID_ATTRIBUTE} /
 * {@link FirebaseAuthenticationFilter#EMAIL_ATTRIBUTE}); a client-supplied uid/email in
 * a body is never trusted. Reaching a handler at all means the token was valid, so
 * {@code uid} is always present here.
 *
 * <p><b>DB-backed since OSK-76.</b> Previously {@code /me} echoed the token attributes
 * directly; now it delegates to {@link UserService#provisionFromToken(String, String)}
 * so the first authenticated request just-in-time creates the {@code users} row and
 * every response reflects the PERSISTED profile (including {@code role} and any
 * {@code displayName} set via PATCH). A newly-provisioned user has {@code displayName ==
 * null} — the filter does not expose the token's display name — until it is set through
 * {@code PATCH /me}.
 */
@RestController
public class MeController {

    private final UserService userService;
    private final AuditService auditService;
    private final FirebaseAccountService firebaseAccountService;
    private final AvatarService avatarService;

    public MeController(
            UserService userService,
            AuditService auditService,
            FirebaseAccountService firebaseAccountService,
            AvatarService avatarService) {
        this.userService = userService;
        this.auditService = auditService;
        this.firebaseAccountService = firebaseAccountService;
        this.avatarService = avatarService;
    }

    /**
     * The persisted identity + profile of the current caller, as returned by both endpoints.
     * Extends the original OSK-66 identity ({@code uid}/{@code email}/{@code displayName}/
     * {@code role}) with the OSK-67 self-service profile fields; any the caller has not set
     * are {@code null} (rendered as absent JSON), except {@code notificationPreference} which
     * always has a value (defaults to {@code EMAIL}).
     *
     * @param uid the verified Firebase user id (always present)
     * @param email the caller's email, or {@code null} if the token carried none
     *     (e.g. anonymous/phone-only sign-in)
     * @param displayName the caller's display name, or {@code null} until set via {@code PATCH /me}
     * @param role the caller's persisted role (the {@code USER} least-privilege default)
     * @param firstName the caller's given name, or {@code null}
     * @param lastName the caller's family name, or {@code null}
     * @param city the caller's city, or {@code null}
     * @param age the caller's age, or {@code null}
     * @param phone the caller's phone, or {@code null}
     * @param notificationPreference the caller's notification channel (never {@code null}; defaults EMAIL)
     * @param timezone the caller's IANA timezone id, or {@code null}
     * @param locale the caller's BCP-47 locale tag, or {@code null}
     * @param onboardingCompleted whether the caller finished onboarding (never {@code null}; defaults false)
     * @param termsAcceptedVersion the terms version the caller accepted, or {@code null} if none yet
     * @param termsAcceptedAt when the caller accepted terms (server timestamp), or {@code null} if none yet
     * @param ageVerified whether the caller's age has been verified (never {@code null}; defaults false)
     * @param emailVerified whether Firebase considers the caller's email verified (OSK-73), or
     *     {@code null} if Firebase is unavailable — READ-ONLY, owned by Firebase, not persisted here
     * @param mfaEnabled whether the caller has any enrolled MFA second factors (OSK-73). Currently
     *     always {@code null}: firebase-admin 9.9.0 exposes no MFA accessor, so it cannot be read —
     *     the field is in the contract so it lights up on a future SDK/version bump
     * @param phoneVerified whether the caller has a verified phone on Firebase (OSK-73; derived from
     *     a phone number being present), or {@code null} if unavailable — READ-ONLY, Firebase-owned
     * @param phoneNumber the caller's verified E.164 phone from Firebase (OSK-73), or {@code null} —
     *     READ-ONLY, Firebase-owned; distinct from the self-service {@code phone} profile field above
     * @param photoUrl the caller's Firebase profile photo URL (OSK-73), or {@code null} — READ-ONLY,
     *     Firebase-owned
     * @param lastLoginAt when Firebase last saw the caller sign in (OSK-73), or {@code null} — advances
     *     only on a fresh Firebase sign-in
     * @param lastActiveAt when the backend last saw the caller make an authenticated request (OSK-73;
     *     this app's own throttled heartbeat), or {@code null} until the first stamp
     */
    public record CurrentUser(
            String uid,
            String email,
            String displayName,
            String role,
            String firstName,
            String lastName,
            String city,
            Integer age,
            String phone,
            NotificationPreference notificationPreference,
            String timezone,
            String locale,
            boolean onboardingCompleted,
            String termsAcceptedVersion,
            Instant termsAcceptedAt,
            boolean ageVerified,
            Boolean emailVerified,
            Boolean mfaEnabled,
            Boolean phoneVerified,
            String phoneNumber,
            String photoUrl,
            Instant lastLoginAt,
            Instant lastActiveAt) {}

    /**
     * Request body for {@code PATCH /api/v1/me}: the fields a caller may change on their own
     * profile — {@code displayName} (OSK-76) plus the richer OSK-67 fields. Identity
     * ({@code uid}/{@code email}) and authorization ({@code role}) are derived from the
     * verified token, never accepted from the body.
     *
     * <p><b>Sparse PATCH.</b> Every field is optional: a field absent from (or {@code null}
     * in) the JSON leaves that field unchanged, so a partial PATCH never clobbers the others.
     * The validation constraints below only fire for a <i>present</i> (non-null) value —
     * {@code null} always passes — and any violation becomes a 400 {@code problem+json} via
     * the global {@code MethodArgumentNotValidException} handler:
     * <ul>
     *   <li>{@code age} must be in the sane human range 13..120,</li>
     *   <li>{@code phone} must be non-blank (lenient — any non-whitespace content),</li>
     *   <li>{@code timezone} must be a valid IANA zone id (e.g. {@code Europe/London}),</li>
     *   <li>{@code locale} must be a well-formed BCP-47 tag (e.g. {@code en-GB}).</li>
     * </ul>
     * {@code notificationPreference} is typed as the {@link NotificationPreference} enum, so an
     * unrecognised value is rejected during JSON binding.
     *
     * @param displayName the desired display name, or {@code null} to leave unchanged
     * @param firstName the desired given name, or {@code null} to leave unchanged
     * @param lastName the desired family name, or {@code null} to leave unchanged
     * @param city the desired city, or {@code null} to leave unchanged
     * @param age the desired age (13..120), or {@code null} to leave unchanged
     * @param phone the desired phone (non-blank), or {@code null} to leave unchanged
     * @param notificationPreference the desired channel, or {@code null} to leave unchanged
     * @param timezone the desired IANA timezone id, or {@code null} to leave unchanged
     * @param locale the desired BCP-47 locale tag, or {@code null} to leave unchanged
     */
    public record UpdateProfileRequest(
            String displayName,
            String firstName,
            String lastName,
            String city,
            @Min(value = 13, message = "must be at least 13") @Max(value = 120, message = "must be at most 120") Integer age,
            @Pattern(regexp = ".*\\S.*", message = "must not be blank") String phone,
            NotificationPreference notificationPreference,
            @ValidTimezone String timezone,
            @ValidLocale String locale) {

        /** Map this API request to the domain-level {@link ProfileUpdate} the service consumes. */
        ProfileUpdate toProfileUpdate() {
            return new ProfileUpdate(
                    displayName, firstName, lastName, city, age, phone, notificationPreference, timezone, locale);
        }
    }

    /**
     * Request body for {@code PATCH /api/v1/me/lifecycle}: the account-lifecycle transitions a
     * caller may apply to their own profile (OSK-69) — mark onboarding complete, accept a terms
     * version, mark age-verified. Identity/authorization are still derived from the verified
     * token, never the body.
     *
     * <p><b>Sparse PATCH</b> (mirrors {@link UpdateProfileRequest}): every field is optional and a
     * field absent from (or {@code null} in) the JSON leaves that state unchanged, so a request
     * that only marks onboarding complete never disturbs the terms or age-verified state.
     *
     * <p><b>Terms acceptance.</b> {@code termsAcceptedVersion} is the version being accepted; when
     * present it must be non-blank (a violation becomes a 400 {@code problem+json}), and the
     * accompanying acceptance <i>timestamp</i> is stamped server-side by the service — it is never
     * accepted from the body, so there is no timestamp field here.
     *
     * @param onboardingCompleted the desired onboarding-complete flag, or {@code null} to leave unchanged
     * @param termsAcceptedVersion the terms version to accept (non-blank), or {@code null} to leave unchanged
     * @param ageVerified the desired age-verified flag, or {@code null} to leave unchanged
     */
    public record UpdateLifecycleRequest(
            Boolean onboardingCompleted,
            @Pattern(regexp = ".*\\S.*", message = "must not be blank") String termsAcceptedVersion,
            Boolean ageVerified) {

        /** Map this API request to the domain-level {@link LifecycleUpdate} the service consumes. */
        LifecycleUpdate toLifecycleUpdate() {
            return new LifecycleUpdate(onboardingCompleted, termsAcceptedVersion, ageVerified);
        }
    }

    /**
     * Request body for {@code PUT /api/v1/me/avatar}: the reference to an avatar image the caller has
     * ALREADY uploaded to Cloud Storage under their own {@code users/{uid}/…} area (OSK-83). Identity
     * still comes only from the verified token — the body carries no uid — so the object path is
     * re-validated server-side to belong to the caller ({@link AvatarService}).
     *
     * <p><b>Why the backend takes a reference, not the bytes.</b> The image is uploaded directly by the
     * browser to Cloud Storage via the Firebase Storage Web SDK (authorised by the caller's ID token
     * against {@code storage.rules}); the bytes never transit this backend. The client then passes back
     * the two facts the backend needs to reflect the upload as the user's {@code photoURL}: WHERE the
     * object lives ({@code objectPath}) and its {@code getDownloadURL()} result ({@code downloadUrl}).
     *
     * @param objectPath the storage object path the client uploaded to, e.g.
     *     {@code users/<uid>/avatar/1720000000000.png} (non-blank; validated to be under the caller's own area)
     * @param downloadUrl the tokenised Firebase Storage download URL for that object (non-blank; validated
     *     to reference our bucket and the same object path)
     */
    public record SetAvatarRequest(@NotBlank String objectPath, @NotBlank String downloadUrl) {}

    /**
     * {@code GET /api/v1/me} — return the caller's PERSISTED profile, provisioning the
     * {@code users} row just-in-time on the first authenticated request (OSK-76).
     *
     * <p>{@code uid}/{@code email} are taken from the verified-token attributes the auth
     * filter published; {@link UserService#provisionFromToken(String, String)} then
     * upserts by {@code firebase_uid} (idempotent and race-safe) and the persisted row is
     * mapped to {@link CurrentUser}.
     */
    @GetMapping("/me")
    public CurrentUser me(HttpServletRequest request) {
        String uid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        String email = (String) request.getAttribute(FirebaseAuthenticationFilter.EMAIL_ATTRIBUTE);
        User user = userService.provisionFromToken(uid, email);
        // Firebase-owned, read-only account state (OSK-73), fetched live from the Admin SDK; degrades
        // to all-null when Firebase is unavailable so /me never fails on a missing dependency.
        FirebaseAccountState accountState = firebaseAccountService.stateFor(uid);
        return toCurrentUser(user, accountState);
    }

    /**
     * {@code PATCH /api/v1/me} — update the caller's allowed profile fields (currently
     * just {@code displayName}) and return the updated, persisted profile (OSK-76).
     *
     * <p>Identity is derived only from the verified token: {@code uid}/{@code email} come
     * from the filter attributes, never from {@code body}. We provision first (idempotent)
     * so a caller can PATCH even before ever calling {@code GET /me} — the row is
     * guaranteed to exist — then apply the update.
     */
    @PatchMapping("/me")
    public CurrentUser updateMe(HttpServletRequest request, @Valid @RequestBody UpdateProfileRequest body) {
        String uid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        String email = (String) request.getAttribute(FirebaseAuthenticationFilter.EMAIL_ATTRIBUTE);
        // Ensure the persisted row exists before updating it (identity from the token
        // only); then apply the client-supplied field changes (sparse — see ProfileUpdate).
        userService.provisionFromToken(uid, email);
        User updated = userService.updateProfile(uid, body.toProfileUpdate());
        // Mirror GET /me: return the same shape including the live Firebase-owned account state.
        FirebaseAccountState accountState = firebaseAccountService.stateFor(uid);
        return toCurrentUser(updated, accountState);
    }

    /**
     * {@code PATCH /api/v1/me/lifecycle} — apply the caller's account-lifecycle transitions
     * (OSK-69): mark onboarding complete, accept a terms version, mark age-verified. Returns the
     * updated, persisted profile.
     *
     * <p>Kept as a dedicated sub-endpoint (rather than folding into {@code PATCH /me}) because
     * accepting terms has a distinct side effect the generic profile PATCH does not — the server
     * stamps the acceptance timestamp — so the semantics read more clearly on their own path.
     *
     * <p>Identity is derived only from the verified token ({@code uid}/{@code email} from the
     * filter attributes, never the body). We provision first (idempotent) so a caller can hit this
     * even before ever calling {@code GET /me}, then apply the sparse update
     * ({@link UserService#updateLifecycle}).
     */
    @PatchMapping("/me/lifecycle")
    public CurrentUser updateLifecycle(HttpServletRequest request, @Valid @RequestBody UpdateLifecycleRequest body) {
        String uid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        String email = (String) request.getAttribute(FirebaseAuthenticationFilter.EMAIL_ATTRIBUTE);
        userService.provisionFromToken(uid, email);
        User updated = userService.updateLifecycle(uid, body.toLifecycleUpdate());
        // Mirror GET /me: return the same shape including the live Firebase-owned account state.
        FirebaseAccountState accountState = firebaseAccountService.stateFor(uid);
        return toCurrentUser(updated, accountState);
    }

    /**
     * {@code PUT /api/v1/me/avatar} — reflect an avatar the caller has already uploaded to Cloud
     * Storage as their Firebase {@code photoURL}, and return the updated, persisted profile (OSK-83).
     *
     * <p>The image bytes were uploaded client-side straight to Storage under the caller's own
     * {@code users/{uid}/…} prefix (the Firebase Storage Web SDK, authorised by the ID token against
     * {@code storage.rules}); this endpoint receives only the object reference. {@link AvatarService}
     * re-validates that reference server-side (ownership + image type + URL binding) and, when valid,
     * sets the Firebase user's {@code photoURL} via the Admin SDK. Because the {@link CurrentUser} we
     * return reads {@code photoUrl} LIVE from Firebase (OSK-73), the response already reflects the new
     * avatar — there is no stored column to keep in sync (no migration needed).
     *
     * <p><b>Idempotent PUT.</b> Setting the same avatar twice yields the same {@code photoURL}, so PUT
     * (not POST) is the honest verb. Identity is derived only from the verified token; we provision
     * first (idempotent) so a caller can set an avatar even before ever calling {@code GET /me}.
     *
     * <p>Validation failure → 400; Firebase/Admin unavailable → 503 (see the local handlers below).
     */
    @PutMapping("/me/avatar")
    public CurrentUser updateAvatar(HttpServletRequest request, @Valid @RequestBody SetAvatarRequest body) {
        String uid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        String email = (String) request.getAttribute(FirebaseAuthenticationFilter.EMAIL_ATTRIBUTE);
        // Ensure the persisted row exists (identity from the token only), then set the avatar. The
        // returned User row is unchanged by the avatar (photoUrl is Firebase-owned), so it is safe to
        // reuse it for the response — only the live Firebase state below reflects the new photoURL.
        User user = userService.provisionFromToken(uid, email);
        avatarService.updateAvatar(uid, body.objectPath(), body.downloadUrl());
        // Mirror GET /me: the live Firebase-owned account state now carries the just-set photoURL.
        FirebaseAccountState accountState = firebaseAccountService.stateFor(uid);
        return toCurrentUser(user, accountState);
    }

    /**
     * Avatar reference failed server-side validation (bad ownership/type/URL) → RFC 7807 {@code 400}.
     * Handled locally (like {@code EmailVerificationController}) to keep this feature self-contained;
     * the message is safe to surface (it names which invariant failed, never attacker-controlled data).
     */
    @ExceptionHandler(InvalidAvatarException.class)
    ProblemDetail handleInvalidAvatar(InvalidAvatarException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Bad Request");
        return problem;
    }

    /**
     * Firebase Admin unavailable (no ADC / Storage not yet enabled — OSK-95 — or a failed Admin call)
     * → degraded RFC 7807 {@code 503} with a {@code Retry-After} hint, matching the email-verification
     * resend degradation (OSK-77). A write must fail loudly so the client knows to retry.
     */
    @ExceptionHandler(AvatarUnavailableException.class)
    ResponseEntity<ProblemDetail> handleAvatarUnavailable(AvatarUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Avatar updates are temporarily unavailable. Please retry shortly.");
        problem.setTitle("Service Unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * {@code GET /api/v1/me/history} — the caller's own profile-change history, newest
     * first and paginated (OSK-99).
     *
     * <p>Each entry is one per-field edit recorded by {@link UserService#updateProfile}
     * (field, old → new, actor, timestamp). The history is read straight from the
     * append-only audit log ({@code audit_events}, OSK-80) by filtering to the caller's own
     * {@code PROFILE_UPDATED} events — so there is no separate history table to keep in sync.
     *
     * <p><b>Identity from the verified token only</b> (mirrors {@code GET /me}): the actor
     * uid comes from the auth-filter attribute, never a body/param, so a caller can only ever
     * see their own changes. Unlike {@code GET/PATCH /me} this does <i>not</i> provision a
     * {@code users} row first — a caller with no edits simply gets an empty page; there is
     * nothing to create just to read history.
     *
     * <p><b>Pagination (OSK-87):</b> the {@link Pageable} is resolved by the shared
     * convention — {@code size} is hard-capped by {@code PageableConfig}. We override only
     * the default to newest-first ({@code createdAt DESC}) with the app's default page size
     * (20), since a change log is read most-recent-first; callers may still page/sort via the
     * standard {@code page}/{@code size}/{@code sort} params. Results are wrapped in the
     * canonical {@link PagedResponse} envelope (items are {@link ProfileChange} DTOs, never
     * the {@link AuditEvent} entity).
     */
    @GetMapping("/me/history")
    public PagedResponse<ProfileChange> history(
            HttpServletRequest request,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        String uid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        Page<AuditEvent> events = auditService.findByActorAndAction(uid, ProfileChange.ACTION, pageable);
        return PagedResponse.of(events.map(ProfileChange::from));
    }

    /**
     * Map the persisted entity plus the live Firebase-owned account state to the wire shape (role
     * rendered as its enum name). The persisted profile supplies identity + the self-service fields
     * and {@code lastActiveAt}; {@code accountState} supplies the read-only Firebase-owned fields,
     * which are all {@code null} when Firebase is unavailable (OSK-73).
     */
    private static CurrentUser toCurrentUser(User user, FirebaseAccountState accountState) {
        return new CurrentUser(
                user.getFirebaseUid(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole().name(),
                user.getFirstName(),
                user.getLastName(),
                user.getCity(),
                user.getAge(),
                user.getPhone(),
                user.getNotificationPreference(),
                user.getTimezone(),
                user.getLocale(),
                user.isOnboardingCompleted(),
                user.getTermsAcceptedVersion(),
                user.getTermsAcceptedAt(),
                user.isAgeVerified(),
                accountState.emailVerified(),
                accountState.mfaEnabled(),
                accountState.phoneVerified(),
                accountState.phoneNumber(),
                accountState.photoUrl(),
                accountState.lastLoginAt(),
                user.getLastActiveAt());
    }
}
