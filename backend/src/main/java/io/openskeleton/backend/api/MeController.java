package io.openskeleton.backend.api;

import io.openskeleton.backend.audit.AuditEvent;
import io.openskeleton.backend.audit.AuditService;
import io.openskeleton.backend.auth.FirebaseAuthenticationFilter;
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
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

    public MeController(UserService userService, AuditService auditService) {
        this.userService = userService;
        this.auditService = auditService;
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
            boolean ageVerified) {}

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
        return toCurrentUser(user);
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
        return toCurrentUser(updated);
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
        return toCurrentUser(updated);
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

    /** Map the persisted entity to the wire shape (role rendered as its enum name). */
    private static CurrentUser toCurrentUser(User user) {
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
                user.isAgeVerified());
    }
}
