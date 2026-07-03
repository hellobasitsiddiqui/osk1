package io.openskeleton.backend.api;

import io.openskeleton.backend.audit.AuditEvent;
import io.openskeleton.backend.audit.AuditService;
import io.openskeleton.backend.auth.FirebaseAuthenticationFilter;
import io.openskeleton.backend.common.PagedResponse;
import io.openskeleton.backend.user.ProfileChange;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
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
     * The persisted identity of the current caller, as returned by both endpoints.
     *
     * @param uid         the verified Firebase user id (always present)
     * @param email       the caller's email, or {@code null} if the token carried none
     *     (e.g. anonymous/phone-only sign-in)
     * @param displayName the caller's display name, or {@code null} until set via
     *     {@code PATCH /me} (the auth filter does not publish the token's display name,
     *     so a freshly-provisioned user starts with none)
     * @param role        the caller's persisted role (the {@code USER} least-privilege
     *     default at provisioning time)
     */
    public record CurrentUser(String uid, String email, String displayName, String role) {}

    /**
     * Request body for {@code PATCH /api/v1/me}: only the fields a caller may change on
     * their own profile. Kept intentionally tiny — {@code displayName} is the sole
     * mutable field today; identity ({@code uid}/{@code email}) and authorization
     * ({@code role}) are derived from the verified token, never accepted from the body.
     *
     * @param displayName the desired display name ({@code null} clears it)
     */
    public record UpdateProfileRequest(String displayName) {}

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
    public CurrentUser updateMe(HttpServletRequest request, @RequestBody UpdateProfileRequest body) {
        String uid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        String email = (String) request.getAttribute(FirebaseAuthenticationFilter.EMAIL_ATTRIBUTE);
        // Ensure the persisted row exists before updating it (identity from the token
        // only); then set the client-supplied displayName on the persisted user.
        userService.provisionFromToken(uid, email);
        User updated = userService.updateProfile(uid, body.displayName());
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
                user.getRole().name());
    }
}
