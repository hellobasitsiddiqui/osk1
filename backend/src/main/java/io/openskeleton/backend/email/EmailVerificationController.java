package io.openskeleton.backend.email;

import io.openskeleton.backend.auth.FirebaseAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The authenticated caller's email-verification resend endpoint (OSK-77):
 * {@code POST /api/v1/me/email/resend-verification}.
 *
 * <p><b>Why the full {@code /api/v1} path is spelled out here</b> (rather than the bare
 * {@code /me/...} that {@code MeController} uses): the {@code /api/v1} prefix is applied
 * automatically only to controllers in the {@code io.openskeleton.backend.api} package
 * (see {@code ApiVersioningConfig}). This controller lives in the feature-local
 * {@code email} package — mirroring {@code DeviceController} — so it declares the
 * versioned path explicitly. It is still authenticated for free:
 * {@link FirebaseAuthenticationFilter} guards every {@code /api/v1/**} request by URL
 * path, so this handler only ever runs for an already-verified caller (anonymous/invalid
 * -> 401 at the filter, in the standard RFC 7807 {@code problem+json} shape).
 *
 * <p><b>Identity comes ONLY from the verified token.</b> The caller's uid is read from
 * the request attribute the auth filter published
 * ({@link FirebaseAuthenticationFilter#UID_ATTRIBUTE}); the target email and verified
 * state are then read authoritatively from Firebase by the service — never taken from
 * the request. There is no request body: who to (re)verify is always the caller.
 *
 * <p><b>Scope.</b> Deliberately tiny — resend + the current {@code emailVerified} flag.
 * It does NOT rebuild the full {@code /me} account state (that is OSK-73). The initial
 * verification on sign-up is sent client-side by Firebase; this endpoint is the
 * server-side resend + state reflection.
 */
@RestController
@RequestMapping("/api/v1/me/email")
public class EmailVerificationController {

    private final EmailVerificationService service;

    public EmailVerificationController(EmailVerificationService service) {
        this.service = service;
    }

    /**
     * The wire shape returned by the resend endpoint.
     *
     * @param email the caller's email address (the resend target)
     * @param emailVerified the caller's current verified state, read from Firebase
     * @param verificationSent whether a verification link was minted + dispatched on
     *     this call ({@code false} when the caller was already verified — nothing to send)
     */
    public record ResendVerificationResponse(String email, boolean emailVerified, boolean verificationSent) {}

    /**
     * {@code POST /api/v1/me/email/resend-verification} — mint + dispatch the caller's
     * verification link (unless already verified) and return their current verified
     * state.
     *
     * <p>Returns {@code 202 Accepted} when a link was dispatched (accepted for delivery;
     * real SMTP delivery is pending OSK-139), and {@code 200 OK} when the caller was
     * already verified so nothing was sent. Over-cooldown -> {@code 429}; no ADC / Admin
     * failure -> {@code 503}; no email to verify -> {@code 422} (see the handlers below).
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<ResendVerificationResponse> resendVerification(HttpServletRequest request) {
        String uid = (String) request.getAttribute(FirebaseAuthenticationFilter.UID_ATTRIBUTE);
        EmailVerificationService.ResendResult result = service.resend(uid);

        HttpStatus status = result.verificationSent() ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(new ResendVerificationResponse(
                        result.email(), result.emailVerified(), result.verificationSent()));
    }

    /**
     * Over-cooldown -> RFC 7807 {@code 429} with a {@code Retry-After} header, mirroring
     * the OSK-63 rate-limiter shape. Handled locally (not in the shared
     * {@code GlobalExceptionHandler}) so the {@code Retry-After} header can be set and to
     * keep this feature self-contained.
     */
    @ExceptionHandler(ResendCooldownException.class)
    ResponseEntity<ProblemDetail> handleCooldown(ResendCooldownException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
        problem.setTitle("Too Many Requests");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * Admin dependency unavailable (no ADC or a failed Firebase call) -> degraded RFC 7807
     * {@code 503} with a {@code Retry-After} hint, matching the auth filter's degraded
     * shape (OSK-65).
     */
    @ExceptionHandler(EmailVerificationUnavailableException.class)
    ResponseEntity<ProblemDetail> handleUnavailable(EmailVerificationUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Email verification is temporarily unavailable. Please retry shortly.");
        problem.setTitle("Service Unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * Caller has no email to verify (anonymous/phone-only sign-in) -> RFC 7807
     * {@code 422}. A plain {@link ProblemDetail} return is enough (no special header);
     * Spring renders it as {@code application/problem+json}.
     */
    @ExceptionHandler(EmailNotVerifiableException.class)
    ProblemDetail handleNotVerifiable(EmailNotVerifiableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Unprocessable Content");
        return problem;
    }
}
