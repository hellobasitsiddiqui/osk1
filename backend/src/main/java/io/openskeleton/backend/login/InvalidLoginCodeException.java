package io.openskeleton.backend.login;

/**
 * Raised when a submitted email login code cannot be accepted (OSK-129): no code on file, the
 * wrong code, an expired code, a code whose attempt budget is exhausted, or a code that was
 * already used. Mapped to an RFC 7807 {@code 401 Unauthorized} by {@link EmailCodeController}.
 *
 * <p><b>Deliberately undifferentiated to the caller.</b> All of these failure modes surface
 * the SAME generic 401 ("Invalid or expired code."), so a caller — or an attacker — cannot
 * distinguish "wrong code" from "expired" from "no such code", which would otherwise leak
 * whether an address has a live code outstanding. The specific {@link Reason} is kept only for
 * server-side logging/metrics.
 */
public class InvalidLoginCodeException extends RuntimeException {

    /** The concrete reason a code was rejected — for server-side logging only, never the wire. */
    public enum Reason {
        /** No code row exists for the email. */
        NOT_FOUND,
        /** The code has already been successfully used (single-use replay). */
        CONSUMED,
        /** The code is past its TTL. */
        EXPIRED,
        /** The failed-attempt cap was reached. */
        TOO_MANY_ATTEMPTS,
        /** The submitted code did not match. */
        MISMATCH
    }

    private final Reason reason;

    public InvalidLoginCodeException(Reason reason) {
        super("Invalid or expired code (" + reason + ").");
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
