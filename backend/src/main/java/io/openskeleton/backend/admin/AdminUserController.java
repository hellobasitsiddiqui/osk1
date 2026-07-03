package io.openskeleton.backend.admin;

import io.openskeleton.backend.common.PagedResponse;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.User.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only user management (OSK-71): list users, read one, and change a user's role or
 * enabled flag, all under {@code /api/v1/admin/users}.
 *
 * <p><b>Two independent gates protect every handler.</b>
 * <ol>
 *   <li><b>Authentication (401).</b> The path lives under {@code /api/v1/**}, so
 *       {@link io.openskeleton.backend.auth.FirebaseAuthenticationFilter} verifies the
 *       caller's Firebase ID token first; an anonymous/invalid caller is rejected with a 401
 *       RFC 7807 body before any handler runs. (The full {@code /api/v1} path is spelled out
 *       here because the automatic {@code /api/v1} prefix only applies to controllers in the
 *       {@code io.openskeleton.backend.api} package — this one lives in {@code admin} — the
 *       same reason {@code DeviceController} spells its path out.)</li>
 *   <li><b>Authorization (403).</b> Each method is annotated
 *       {@code @PreAuthorize("hasRole('ADMIN')")}. Method security (OSK-71) reads the caller's
 *       authorities from the SecurityContext the auth filter populated and rejects a caller
 *       who authenticated but is not an admin with a 403. {@code hasRole('ADMIN')} matches the
 *       {@code ROLE_ADMIN} authority {@code RoleClaimMapper} emits for an admin's {@code role}
 *       claim.</li>
 * </ol>
 * The annotation is repeated on every method (rather than once at class level) so each
 * endpoint's guard is explicit at its handler and cannot be silently lost.
 *
 * <p><b>Errors use the shared RFC 7807 model</b> (via {@code GlobalExceptionHandler},
 * mirroring {@code MeController}): an unknown id is a {@code NotFoundException} → 404, a
 * malformed/invalid body (bad role value, missing field) → 400, an admin denial → 403 — all
 * rendered as {@code application/problem+json}.
 *
 * <p>Identity of the target user is always the path {@code id}; the acting admin's identity is
 * never taken from the body. Audit logging of these mutations is intentionally deferred to
 * OSK-93 (see {@link AdminUserService}).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /**
     * The admin-facing view of a user. Exactly the fields OSK-71 specifies for the list and
     * detail responses — enough to administer an account without leaking internal churn
     * fields ({@code updatedAt}, {@code version}, {@code firebaseUid}, {@code deletedAt}).
     *
     * @param id          the user's UUID primary key
     * @param email       the user's email, or {@code null} if none is on the account
     * @param displayName the user's display name, or {@code null} if never set
     * @param role        the persisted authorization role, rendered as its enum name
     *     ({@code "USER"} / {@code "ADMIN"})
     * @param enabled     whether the account is active ({@code false} = locked out; see
     *     {@link AdminUserService#setEnabled(UUID, boolean)})
     * @param accountType REAL vs TEST classification, rendered as its enum name
     * @param createdAt   when the account was first provisioned
     */
    public record UserSummary(
            UUID id,
            String email,
            String displayName,
            String role,
            boolean enabled,
            String accountType,
            Instant createdAt) {

        /** Project the persisted entity onto the wire shape (enums rendered as their names). */
        static UserSummary from(User user) {
            return new UserSummary(
                    user.getId(),
                    user.getEmail(),
                    user.getDisplayName(),
                    user.getRole().name(),
                    user.isEnabled(),
                    user.getAccountType().name(),
                    user.getCreatedAt());
        }
    }

    /**
     * Request body for {@code PATCH .../{id}/role}. The role is bound straight to the
     * {@link Role} enum and required: an unknown value (e.g. {@code "SUPERADMIN"}) fails
     * deserialization → {@code HttpMessageNotReadableException} → 400, and an absent/null value
     * trips {@code @NotNull} → 400 (both via the global handler). So only {@code "USER"} /
     * {@code "ADMIN"} are ever accepted.
     *
     * @param role the new role ({@code USER} or {@code ADMIN})
     */
    public record UpdateRoleRequest(@NotNull Role role) {}

    /**
     * Request body for {@code PATCH .../{id}/enabled}. {@code Boolean} (not {@code boolean}) so
     * an omitted field is {@code null} and caught by {@code @NotNull} → 400, rather than
     * silently defaulting to {@code false}.
     *
     * @param enabled the desired enabled state
     */
    public record UpdateEnabledRequest(@NotNull Boolean enabled) {}

    /**
     * {@code GET /api/v1/admin/users} — a page of users. Uses the shared pagination convention
     * (OSK-87): {@code ?page=&size=&sort=} with {@code size} hard-capped and a deterministic
     * default sort ({@code id ASC}); results are wrapped in the canonical {@link PagedResponse}
     * envelope with {@link UserSummary} items (never the JPA entity).
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<UserSummary> listUsers(Pageable pageable) {
        return PagedResponse.of(adminUserService.list(pageable).map(UserSummary::from));
    }

    /**
     * {@code GET /api/v1/admin/users/{id}} — a single user, or a 404 {@code problem+json} if no
     * active user has that id.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserSummary getUser(@PathVariable UUID id) {
        return UserSummary.from(adminUserService.get(id));
    }

    /**
     * {@code PATCH /api/v1/admin/users/{id}/role} — set a user's role and return the updated
     * summary. 400 on a missing/invalid role, 404 on an unknown id.
     */
    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public UserSummary updateRole(@PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest body) {
        return UserSummary.from(adminUserService.changeRole(id, body.role()));
    }

    /**
     * {@code PATCH /api/v1/admin/users/{id}/enabled} — enable/disable a user and return the
     * updated summary. Disabling takes effect immediately: the user's next authenticated
     * request is rejected with a 403 by the auth filter (see
     * {@link AdminUserService#setEnabled(UUID, boolean)}). 400 on a missing flag, 404 on an
     * unknown id.
     */
    @PatchMapping("/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public UserSummary updateEnabled(@PathVariable UUID id, @Valid @RequestBody UpdateEnabledRequest body) {
        return UserSummary.from(adminUserService.setEnabled(id, body.enabled()));
    }
}
