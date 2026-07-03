package io.openskeleton.backend.web;

import jakarta.persistence.OptimisticLockException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Global API error model — turns every unhandled failure into a consistent
 * RFC 7807 {@link ProblemDetail} JSON response (media type
 * {@code application/problem+json}) so all clients handle errors uniformly and a
 * stack trace is never leaked to the caller.
 *
 * <p>Mappings implemented here: bean-validation → 400, unreadable/invalid body
 * ({@link HttpMessageNotReadableException}, e.g. an unknown enum value) → 400, an
 * unbindable request parameter ({@link MethodArgumentTypeMismatchException}, e.g. an
 * unknown {@code ?action=} enum value on the admin audit endpoint — OSK-93) → 400,
 * {@link NotFoundException} → 404, method-security denial
 * ({@link AccessDeniedException}) → 403, {@link ConflictException} → 409,
 * optimistic-locking failures ({@link ObjectOptimisticLockingFailureException} /
 * {@link OptimisticLockException}) → 409, a database constraint clash
 * ({@link DataIntegrityViolationException}, e.g. the email/phone identity uniqueness added
 * by OSK-163) → 409, and a catch-all → 500. The other data-layer mapping named in the
 * OSK-39 ticket (bad {@code ?sort=} / oversized-page → 400) is deferred until it is needed
 * — see the ticket note.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Requested resource missing → 404. */
    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setTitle("Not Found");
        return pd;
    }

    /** State conflict (e.g. uniqueness clash) → 409. */
    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setTitle("Conflict");
        return pd;
    }

    /**
     * Optimistic-locking failure → 409. Raised when a write targets a row another
     * transaction has already advanced (the {@code @Version} check on
     * {@code BaseEntity} matches zero rows): Hibernate throws
     * {@code StaleObjectStateException}, surfaced by Spring as
     * {@link ObjectOptimisticLockingFailureException}, and the JPA-native
     * {@link OptimisticLockException} is mapped alongside it for the paths where it is
     * not translated. Returning 409 (not a silent overwrite) tells the caller their
     * copy was stale so they can reload and retry — the concurrency guarantee OSK-84
     * adds on top of soft-delete.
     */
    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, OptimisticLockException.class})
    public ProblemDetail handleOptimisticLock(Exception ex) {
        log.debug("Optimistic-locking conflict on concurrent update", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The resource was updated concurrently. Reload the latest version and retry.");
        pd.setTitle("Conflict");
        return pd;
    }

    /**
     * Database integrity violation → 409. A UNIQUE / foreign-key / NOT-NULL constraint the write
     * clashed with surfaces from JPA as {@link DataIntegrityViolationException}. The case this
     * mapping is added for (OSK-163): a request that would create a SECOND account for an identity
     * already taken — the email/phone uniqueness enforced by the {@code V10} indexes. When
     * provisioning cannot reconcile such a clash to the existing row (a genuine, unrecoverable
     * conflict — see {@link io.openskeleton.backend.user.UserService#provisionFromToken}), the
     * violation propagates here and becomes a 409 rather than a leaked 500: a uniqueness clash is
     * a conflict with existing state, which is exactly what 409 means. The detail is generic so no
     * constraint name or SQL fragment (attacker-controlled input can appear in it) is echoed to the
     * caller; the real cause is logged server-side. This realises the {@code DataIntegrityViolation
     * → 409} mapping that OSK-39 deferred until a feature needed it.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation mapped to 409", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The request conflicts with an existing account. It may already exist.");
        pd.setTitle("Conflict");
        return pd;
    }

    /**
     * Method-security authorization denial → 403. Raised when a caller who authenticated
     * successfully invokes a handler they are not permitted to use — e.g. a non-admin hitting
     * a {@code @PreAuthorize("hasRole('ADMIN')")} admin endpoint (OSK-71). Spring Security's
     * {@code AuthorizationDeniedException} extends {@link AccessDeniedException}, so this one
     * mapping covers method-security denials. It is deliberately distinct from the 401 the
     * auth filter emits for an <i>unauthenticated</i> caller: 401 = "who are you?", 403 =
     * "you're known, but not allowed". The message is generic so it never reveals what the
     * caller was missing.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        log.debug("Authorization denied", ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
        pd.setTitle("Forbidden");
        return pd;
    }

    /**
     * Unreadable/invalid request body → 400. Covers a malformed JSON payload and — the case
     * that matters for OSK-71 — a value that cannot be bound to its target type, such as an
     * unknown enum constant in {@code {"role":"SUPERADMIN"}} (Jackson raises
     * {@link HttpMessageNotReadableException}). Without this it would fall through to the
     * catch-all 500; a bad body is a client error, so it is a 400. The message is generic to
     * avoid echoing attacker-controlled parse detail.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("Unreadable request body", ex);
        ProblemDetail pd =
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed or invalid request body.");
        pd.setTitle("Bad Request");
        return pd;
    }

    /**
     * A request parameter/path variable that cannot be bound to its target type → 400. The
     * case that matters for OSK-93: an unknown {@code ?action=} value on
     * {@code GET /api/v1/admin/audit} that does not name an {@link Enum} constant — Spring
     * raises {@link MethodArgumentTypeMismatchException} during binding, which would otherwise
     * fall through to the catch-all 500. A malformed filter/param is a client error, so it is a
     * 400. The message is generic to avoid echoing attacker-controlled input.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.debug("Unbindable request parameter '{}'", ex.getName(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Invalid value for request parameter '" + ex.getName() + "'.");
        pd.setTitle("Bad Request");
        return pd;
    }

    /** Request body failed bean validation → 400, with the field errors attached. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed.");
        pd.setTitle("Bad Request");
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        pd.setProperty("errors", errors);
        return pd;
    }

    /**
     * Anything not mapped above → 500 with a GENERIC message. The real exception
     * is logged server-side (for diagnosis) but never serialised to the client, so
     * no stack trace or internal detail leaks.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ProblemDetail pd =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        pd.setTitle("Internal Server Error");
        return pd;
    }
}
