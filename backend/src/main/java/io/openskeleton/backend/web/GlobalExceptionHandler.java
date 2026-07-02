package io.openskeleton.backend.web;

import jakarta.persistence.OptimisticLockException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global API error model — turns every unhandled failure into a consistent
 * RFC 7807 {@link ProblemDetail} JSON response (media type
 * {@code application/problem+json}) so all clients handle errors uniformly and a
 * stack trace is never leaked to the caller.
 *
 * <p>Mappings implemented here: bean-validation → 400, {@link NotFoundException}
 * → 404, {@link ConflictException} → 409, optimistic-locking failures
 * ({@link ObjectOptimisticLockingFailureException} / {@link OptimisticLockException})
 * → 409, and a catch-all → 500. The remaining data-layer-specific mappings named in
 * the OSK-39 ticket ({@code DataIntegrityViolationException} → 409, bad
 * {@code ?sort=} / oversized-page → 400) are deferred until they are needed — see the
 * ticket note.
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
