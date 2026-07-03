package io.openskeleton.backend.email;

/**
 * Delivery seam for a freshly-minted email-verification link (OSK-77).
 *
 * <p><b>Why a seam and not a direct SMTP call:</b> the backend's job in OSK-77 is to
 * <i>mint</i> the verification link (via the Firebase Admin SDK) and <i>dispatch</i>
 * it; actually putting bytes on the wire needs the Workspace SMTP app-password secret,
 * which is a separate HUMAN step (OSK-139) and is not wired yet. Hiding delivery behind
 * this one method lets the whole resend flow — mint, cooldown, HTTP contract — be built
 * and unit-tested now with a mock, and lets a real SMTP {@code JavaMailSender}-backed
 * implementation drop in later without touching the service or controller.
 *
 * <p>Implementations MUST treat {@code verificationLink} as a sensitive capability URL
 * (it carries a one-time out-of-band code that verifies the address) and never log it
 * verbatim. The default {@link LoggingEmailVerificationSender} records only that a link
 * was dispatched, for a masked recipient, until the SMTP sender lands.
 */
public interface EmailVerificationSender {

    /**
     * Dispatches the verification link to the given address.
     *
     * @param email the recipient's email address (the authoritative address read from
     *     the Firebase user record; never {@code null}/blank when called by the service)
     * @param verificationLink the minted Firebase verification link — a sensitive
     *     capability URL that must not be logged verbatim
     */
    void send(String email, String verificationLink);
}
