package io.openskeleton.backend.api;

import io.openskeleton.backend.auth.FirebaseAuthenticationFilter;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
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

    public MeController(UserService userService) {
        this.userService = userService;
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

    /** Map the persisted entity to the wire shape (role rendered as its enum name). */
    private static CurrentUser toCurrentUser(User user) {
        return new CurrentUser(
                user.getFirebaseUid(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole().name());
    }
}
