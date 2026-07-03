package io.openskeleton.backend.login;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, pre-login endpoints for the passwordless email-code sign-in flow (OSK-129):
 * {@code POST /api/v1/auth/email-code/start} and {@code POST /api/v1/auth/email-code/verify}.
 *
 * <p><b>Why the full {@code /api/v1} path is spelled out</b> (rather than a bare mapping): the
 * {@code /api/v1} prefix is applied automatically only to controllers in the
 * {@code io.openskeleton.backend.api} package (see {@code ApiVersioningConfig}). This
 * controller lives in the feature-local {@code login} package — mirroring
 * {@code EmailVerificationController} / {@code DeviceController} — so it declares the versioned
 * path explicitly.
 *
 * <p><b>These two endpoints are deliberately PUBLIC.</b> They are how a signed-OUT visitor
 * authenticates, so they must run before any bearer token exists. {@code FirebaseAuthenticationFilter}
 * guards {@code /api/v1/**} by default, so it is given an explicit exemption for the
 * {@code /api/v1/auth/email-code/} prefix (see that filter). They are still covered by the
 * coarse per-IP request limiter (OSK-63, which filters all {@code /api/**}), and the service
 * adds a finer per-email token-bucket on top — so "rate-limit per email/IP" holds even though
 * the endpoints are unauthenticated.
 *
 * <p><b>Error model.</b> Bad input (missing/invalid email or blank code) is a bean-validation
 * {@code 400} via the shared {@code GlobalExceptionHandler}. The three feature-specific
 * failures are handled locally so they can carry the right status + headers: over the per-email
 * limit -> {@code 429} + {@code Retry-After}; a wrong/expired/exhausted/reused code -> a generic
 * {@code 401}; the Firebase Admin dependency unavailable -> a degraded {@code 503} +
 * {@code Retry-After}. All are RFC 7807 {@code application/problem+json}, matching the rest of
 * the API.
 */
@RestController
@RequestMapping("/api/v1/auth/email-code")
public class EmailCodeController {

    private final EmailLoginCodeService service;

    public EmailCodeController(EmailLoginCodeService service) {
        this.service = service;
    }

    /** Request body for {@code /start}: the email to send a code to. */
    public record StartRequest(@NotBlank @Email String email) {}

    /** Response body for {@code /start}: a fixed status token (never leaks whether the email is known). */
    public record StartResponse(String status) {}

    /** Request body for {@code /verify}: the email and the numeric code the user received. */
    public record VerifyRequest(@NotBlank @Email String email, @NotBlank String code) {}

    /**
     * Response body for {@code /verify}: the minted Firebase custom token. The web client
     * exchanges it for a session via {@code signInWithCustomToken(token)}.
     */
    public record VerifyResponse(String token) {}

    /**
     * {@code POST /api/v1/auth/email-code/start} — issue + dispatch a login code for the email.
     *
     * <p>Always returns {@code 202 Accepted} on success (delivery is accepted for sending; real
     * SMTP delivery is pending OSK-139). The response is identical whether or not the address is
     * already a known user, so it cannot be used to enumerate accounts.
     */
    @PostMapping("/start")
    public ResponseEntity<StartResponse> start(@Valid @RequestBody StartRequest request) {
        service.start(request.email());
        return ResponseEntity.accepted().body(new StartResponse("code_sent"));
    }

    /**
     * {@code POST /api/v1/auth/email-code/verify} — verify a code and mint a Firebase custom
     * token on success ({@code 200 OK}).
     */
    @PostMapping("/verify")
    public ResponseEntity<VerifyResponse> verify(@Valid @RequestBody VerifyRequest request) {
        String token = service.verify(request.email(), request.code());
        return ResponseEntity.ok(new VerifyResponse(token));
    }

    /**
     * Over the per-email limit -> RFC 7807 {@code 429} with a {@code Retry-After} header,
     * mirroring the OSK-63 rate-limiter / OSK-77 cooldown shape.
     */
    @ExceptionHandler(EmailLoginRateLimitedException.class)
    ResponseEntity<ProblemDetail> handleRateLimited(EmailLoginRateLimitedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in attempts for this email. Please wait and try again.");
        problem.setTitle("Too Many Requests");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * A wrong/expired/exhausted/reused code -> RFC 7807 {@code 401}. The detail is intentionally
     * generic (never says WHICH of those it was), so it cannot reveal whether a live code exists
     * for the address.
     */
    @ExceptionHandler(InvalidLoginCodeException.class)
    ResponseEntity<ProblemDetail> handleInvalidCode(InvalidLoginCodeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Invalid or expired code. Request a new one and try again.");
        problem.setTitle("Unauthorized");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    /**
     * Firebase Admin unavailable (no ADC or a failed Firebase call) -> degraded RFC 7807
     * {@code 503} with a {@code Retry-After} hint, matching the auth filter's degraded shape
     * (OSK-65). Only {@code verify} can raise this; {@code start} never needs Firebase.
     */
    @ExceptionHandler(EmailLoginUnavailableException.class)
    ResponseEntity<ProblemDetail> handleUnavailable(EmailLoginUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "Sign-in is temporarily unavailable. Please retry shortly.");
        problem.setTitle("Service Unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
