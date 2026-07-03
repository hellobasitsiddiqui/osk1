package io.openskeleton.backend.email;

/**
 * Raised when the caller has no email address to verify (OSK-77) — e.g. an
 * anonymous/phone-only Firebase sign-in whose token carries a uid but no email. There
 * is nothing to send a verification link to, and that is a property of the request, not
 * a transient fault, so the controller maps it to an RFC 7807 {@code 422 Unprocessable
 * Content} rather than retrying or degrading.
 */
class EmailNotVerifiableException extends RuntimeException {

    EmailNotVerifiableException(String message) {
        super(message);
    }
}
