package io.openskeleton.backend.admin;

import io.openskeleton.backend.audit.AuditAction;
import io.openskeleton.backend.audit.AuditEvent;
import io.openskeleton.backend.audit.AuditService;
import io.openskeleton.backend.common.PagedResponse;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only read view over the append-only audit log (OSK-93):
 * {@code GET /api/v1/admin/audit} — a paginated, newest-first browse of the security-relevant
 * events the platform records (role changes, account enable/disable, provisioning, profile
 * edits, ...), with optional filters.
 *
 * <p><b>Where the events come from.</b> The mutating admin endpoints in
 * {@link AdminUserController}/{@link AdminUserService} now append to the log through
 * {@link AuditService#record} (OSK-93 wired the {@code ROLE_CHANGED} / {@code ACCOUNT_ENABLED}
 * / {@code ACCOUNT_DISABLED} seams OSK-71 marked). This controller is the read counterpart:
 * it never writes, delegating entirely to {@link AuditService#findEvents} so the append-only
 * guarantee holds and the indexed finders back each filter combination.
 *
 * <p><b>Two independent gates protect the handler</b>, exactly as {@link AdminUserController}
 * documents: authentication (401) at {@link io.openskeleton.backend.auth.FirebaseAuthenticationFilter}
 * because the path is under {@code /api/v1/**}, then authorization (403) via
 * {@code @PreAuthorize("hasRole('ADMIN')")}. The full {@code /api/v1} path is spelled out here
 * because this controller lives in {@code admin}, not {@code api}, so it does not inherit the
 * automatic {@code /api/v1} prefix.
 *
 * <p><b>Errors use the shared RFC 7807 model</b> (via {@code GlobalExceptionHandler}): a
 * non-admin caller → 403, and an unknown {@code ?action=} value that cannot bind to the
 * {@link AuditAction} enum → 400 — both rendered as {@code application/problem+json}.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
public class AdminAuditController {

    private final AuditService auditService;

    public AdminAuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /**
     * The admin-facing wire shape of one audit event — the "who did what to whom, when, with
     * what detail" fields, projected off the {@link AuditEvent} entity (never the JPA entity
     * itself, matching {@link AdminUserController.UserSummary}).
     *
     * @param id         the event's UUID id
     * @param actor      the acting user's Firebase uid, or {@code null} for a system-originated
     *     event
     * @param action     the action, rendered as its {@link AuditAction} enum name (e.g.
     *     {@code "ROLE_CHANGED"})
     * @param targetType coarse type of the affected entity (e.g. {@code "USER"}), or {@code null}
     * @param targetId   the affected entity's id, or {@code null}
     * @param metadata   optional benign structured context (e.g. {@code {old, new}} for a role
     *     change), or {@code null}
     * @param createdAt  when the event was recorded
     */
    public record AuditEventResponse(
            UUID id,
            String actor,
            String action,
            String targetType,
            String targetId,
            Map<String, Object> metadata,
            Instant createdAt) {

        /** Project the persisted entity onto the wire shape (action rendered as its enum name). */
        static AuditEventResponse from(AuditEvent event) {
            return new AuditEventResponse(
                    event.getId(),
                    event.getActorFirebaseUid(),
                    event.getAction().name(),
                    event.getTargetType(),
                    event.getTargetId(),
                    event.getMetadata(),
                    event.getCreatedAt());
        }
    }

    /**
     * {@code GET /api/v1/admin/audit} — a page of audit events, newest-first, wrapped in the
     * canonical {@link PagedResponse} envelope with {@link AuditEventResponse} items.
     *
     * <p><b>Pagination (OSK-87):</b> the {@link Pageable} is resolved by the shared convention —
     * {@code size} is hard-capped by {@code PageableConfig}. We default the sort to newest-first
     * ({@code createdAt DESC}) with the default page size (20), since an audit trail is read
     * most-recent-first; callers may still page/sort via the standard {@code page}/{@code size}/
     * {@code sort} params.
     *
     * <p><b>Optional filters</b> (either, both, or neither), composed in {@link AuditService#findEvents}:
     * <ul>
     *   <li>{@code ?targetUserId=} — narrow to events recorded against one user id
     *       ({@code target_id}); a plain string, matching how the id is stored, so a non-UUID
     *       value simply matches nothing rather than erroring.</li>
     *   <li>{@code ?action=} — narrow to one {@link AuditAction}; an unknown value fails to bind
     *       and is a 400 {@code problem+json}.</li>
     * </ul>
     *
     * @param targetUserId optional target-user filter ({@code target_id}); {@code null} = no filter
     * @param action optional action filter; {@code null} = no filter
     * @param pageable the resolved page request (newest-first by default)
     * @return the requested page of audit events
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResponse<AuditEventResponse> listAudit(
            @RequestParam(name = "targetUserId", required = false) String targetUserId,
            @RequestParam(name = "action", required = false) AuditAction action,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PagedResponse.of(
                auditService.findEvents(targetUserId, action, pageable).map(AuditEventResponse::from));
    }
}
