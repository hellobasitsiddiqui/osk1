package io.openskeleton.backend.device;

import io.openskeleton.backend.auth.FirebaseAuthenticationFilter;
import io.openskeleton.backend.device.DeviceToken.Platform;
import io.openskeleton.backend.user.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated caller's registered push devices (OSK-154):
 * {@code POST /api/v1/me/devices} to register/upsert a token and
 * {@code DELETE /api/v1/me/devices/{token}} to deregister one on sign-out.
 *
 * <p><b>Why the full {@code /api/v1} path is declared here</b> (rather than the bare
 * {@code /me/devices} that {@code MeController} uses): the {@code /api/v1} prefix is
 * applied automatically only to controllers under the {@code io.openskeleton.backend.api}
 * package (see {@code ApiVersioningConfig}). This controller lives in the feature-local
 * {@code device} package, which is outside that scope, so it spells the versioned path
 * out. It is still authenticated for free: {@link FirebaseAuthenticationFilter} guards
 * every {@code /api/v1/**} request by URL path (independent of package), so these
 * handlers only ever run for an already-verified caller (anonymous/invalid → 401 at the
 * filter, in the standard RFC 7807 {@code problem+json} shape).
 *
 * <p><b>Identity comes ONLY from the verified token.</b> The caller's {@code uid} is read
 * from the request attribute the auth filter published
 * ({@link FirebaseAuthenticationFilter#UID_ATTRIBUTE}) — mirroring {@code MeController} —
 * never from the request body or path. The body carries only the {@code token} and
 * {@code platform}; who they belong to is always the authenticated caller.
 *
 * <p><b>JIT provisioning.</b> Like {@code MeController}, it provisions the caller's
 * {@code users} row first ({@link UserService#provisionFromToken(String, String)}, OSK-76)
 * so a client can register a device even before it has ever called {@code GET /me} — the
 * owning row the token's foreign key needs is guaranteed to exist.
 */
@RestController
@RequestMapping("/api/v1/me/devices")
public class DeviceController {

    private final DeviceTokenService deviceTokenService;
    private final UserService userService;

    public DeviceController(DeviceTokenService deviceTokenService, UserService userService) {
        this.deviceTokenService = deviceTokenService;
        this.userService = userService;
    }

    /**
     * Request body for {@code POST /api/v1/me/devices}: the device's push token and the
     * platform it is for. Both are required — a blank token or absent platform is a 400
     * via the global bean-validation handler. The owner is intentionally NOT a field:
     * identity is derived from the verified token, never accepted from the body.
     *
     * @param token the push registration token (e.g. an FCM/APNs token), non-blank
     * @param platform the platform the token belongs to, required
     */
    public record RegisterDeviceRequest(@NotBlank String token, @NotNull Platform platform) {}

    /**
     * The wire shape returned after a successful registration: the stored token and its
     * platform. Kept minimal — enough to confirm what was persisted without echoing
     * internal ids/timestamps.
     *
     * @param token the registered push token
     * @param platform the platform name (the {@link Platform} enum rendered as its name)
     */
    public record DeviceResponse(String token, String platform) {}

    /**
     * {@code POST /api/v1/me/devices} — register (upsert) a push token for the caller
     * (AC-1). Idempotent per token: re-registering the same token updates the existing
     * binding rather than creating a duplicate.
     *
     * <p>The caller's {@code uid}/{@code email} come from the verified-token attributes;
     * the {@code users} row is provisioned just-in-time (so the token's owner exists),
     * then the token is upserted for that caller.
     */
    @PostMapping
    public DeviceResponse register(HttpServletRequest request, @Valid @RequestBody RegisterDeviceRequest body) {
        String uid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        String email = (String) request.getAttribute(FirebaseAuthenticationFilter.EMAIL_ATTRIBUTE);
        // Ensure the caller's users row exists (identity from the token only) before the
        // device token's foreign key references it.
        userService.provisionFromToken(uid, email);
        DeviceToken saved = deviceTokenService.register(uid, body.token(), body.platform());
        return new DeviceResponse(saved.getToken(), saved.getPlatform().name());
    }

    /**
     * {@code DELETE /api/v1/me/devices/{token}} — deregister a token on sign-out (AC-2),
     * scoped to the caller. Returns {@code 204 No Content} whether or not a row was
     * removed: the operation is idempotent, so a repeated sign-out (or a token the caller
     * does not own) is a safe no-op, not an error.
     */
    @DeleteMapping("/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deregister(HttpServletRequest request, @PathVariable String token) {
        String uid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        deviceTokenService.deregister(uid, token);
    }
}
