package io.openskeleton.backend.admin;

import io.openskeleton.backend.auth.FirebaseAuthenticationFilter;
import io.openskeleton.backend.common.PagedResponse;
import io.openskeleton.backend.user.NotificationPreference;
import io.openskeleton.backend.user.ProfileUpdate;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.User.Role;
import io.openskeleton.backend.validation.ValidLocale;
import io.openskeleton.backend.validation.ValidTimezone;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
     *     {@link AdminUserService#setEnabled(UUID, boolean, String)})
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
     * The admin-facing <b>detail</b> view of a user (OSK-85): the {@link UserSummary} fields plus
     * the OSK-67 self-service profile fields, so an admin can view and edit another user's full
     * profile. Returned by {@code GET .../{id}} and the profile {@code PATCH}. The list endpoint
     * keeps returning the leaner {@link UserSummary} — these richer fields are only surfaced on
     * the single-user detail, which is where they are needed.
     *
     * @param id                     the user's UUID primary key
     * @param email                  the user's email, or {@code null} if none is on the account
     * @param displayName            the user's display name, or {@code null} if never set
     * @param role                   the persisted authorization role, rendered as its enum name
     * @param enabled                whether the account is active
     * @param accountType            REAL vs TEST classification, rendered as its enum name
     * @param createdAt              when the account was first provisioned
     * @param firstName              the user's given name, or {@code null}
     * @param lastName               the user's family name, or {@code null}
     * @param city                   the user's city, or {@code null}
     * @param age                    the user's age, or {@code null}
     * @param phone                  the user's phone, or {@code null}
     * @param notificationPreference the user's notification channel (never {@code null}; defaults EMAIL)
     * @param timezone               the user's IANA timezone id, or {@code null}
     * @param locale                 the user's BCP-47 locale tag, or {@code null}
     */
    public record UserDetail(
            UUID id,
            String email,
            String displayName,
            String role,
            boolean enabled,
            String accountType,
            Instant createdAt,
            String firstName,
            String lastName,
            String city,
            Integer age,
            String phone,
            NotificationPreference notificationPreference,
            String timezone,
            String locale) {

        /** Project the persisted entity onto the detail wire shape (enums rendered as names). */
        static UserDetail from(User user) {
            return new UserDetail(
                    user.getId(),
                    user.getEmail(),
                    user.getDisplayName(),
                    user.getRole().name(),
                    user.isEnabled(),
                    user.getAccountType().name(),
                    user.getCreatedAt(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getCity(),
                    user.getAge(),
                    user.getPhone(),
                    user.getNotificationPreference(),
                    user.getTimezone(),
                    user.getLocale());
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
     * Request body for {@code PATCH .../{id}/profile} (OSK-85): the profile fields an admin may
     * change on ANOTHER user. Deliberately the SAME field set and validation as
     * {@code MeController.UpdateProfileRequest} (OSK-67), so an admin edit is bound and validated
     * exactly like a user's self-edit — the one difference being who the target is (the path
     * {@code id}, not the token).
     *
     * <p><b>Sparse PATCH.</b> Every field is optional: a field absent from (or {@code null} in)
     * the JSON leaves that field unchanged, so a partial PATCH never clobbers the others. The
     * constraints below only fire for a <i>present</i> (non-null) value — {@code null} always
     * passes — and any violation becomes a 400 {@code problem+json} via the global
     * {@code MethodArgumentNotValidException} handler:
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
     * {@code GET /api/v1/admin/users/{id}} — a single user's full detail (incl. the OSK-67
     * profile fields, OSK-85), or a 404 {@code problem+json} if no active user has that id.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public UserDetail getUser(@PathVariable UUID id) {
        return UserDetail.from(adminUserService.get(id));
    }

    /**
     * {@code PATCH /api/v1/admin/users/{id}/role} — set a user's role and return the updated
     * summary. 400 on a missing/invalid role, 404 on an unknown id.
     *
     * <p>The change is recorded to the audit log (OSK-93) with the acting admin as the actor:
     * the actor uid is read from the verified-token request attribute the auth filter published
     * ({@link FirebaseAuthenticationFilter#UID_ATTRIBUTE}, the same source {@code MeController}
     * uses) and threaded into the service — never taken from the request body.
     */
    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public UserSummary updateRole(
            HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest body) {
        String actorUid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        return UserSummary.from(adminUserService.changeRole(id, body.role(), actorUid));
    }

    /**
     * {@code PATCH /api/v1/admin/users/{id}/enabled} — enable/disable a user and return the
     * updated summary. Disabling takes effect immediately: the user's next authenticated
     * request is rejected with a 403 by the auth filter (see
     * {@link AdminUserService#setEnabled(UUID, boolean, String)}). 400 on a missing flag, 404 on
     * an unknown id.
     *
     * <p>The change is recorded to the audit log (OSK-93) with the acting admin as the actor,
     * read from the verified-token request attribute (see {@link #updateRole}).
     */
    @PatchMapping("/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public UserSummary updateEnabled(
            HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody UpdateEnabledRequest body) {
        String actorUid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        return UserSummary.from(adminUserService.setEnabled(id, body.enabled(), actorUid));
    }

    /**
     * {@code PATCH /api/v1/admin/users/{id}/profile} — sparse-update another user's OSK-67
     * profile fields (OSK-85) and return the updated {@link UserDetail}. Same validation as
     * {@code PATCH /api/v1/me}: 400 {@code problem+json} on an invalid field (bad age/timezone/
     * locale/blank phone), 404 on an unknown id.
     *
     * <p>The edit is recorded to the audit log with the acting admin as the actor — the actor uid
     * is read from the verified-token request attribute (see {@link #updateRole}) and threaded
     * into the service, never taken from the body — as a single
     * {@link io.openskeleton.backend.audit.AuditAction#PROFILE_EDITED} event whose metadata lists
     * the changed fields.
     */
    @PatchMapping("/{id}/profile")
    @PreAuthorize("hasRole('ADMIN')")
    public UserDetail updateProfile(
            HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody UpdateProfileRequest body) {
        String actorUid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        return UserDetail.from(adminUserService.editProfile(id, body.toProfileUpdate(), actorUid));
    }
}
