package io.openskeleton.backend.notification;

/**
 * General-purpose transactional-email delivery seam used by
 * {@link NotificationService} to reach a user on the EMAIL channel (OSK-89).
 *
 * <p><b>Why a seam and not a direct SMTP call:</b> actually putting bytes on the wire
 * needs the Workspace SMTP app-password secret, which is a separate HUMAN step (OSK-139)
 * and is not wired yet. Hiding delivery behind this one method lets the whole
 * preference-routing service be built and unit-tested now with a Mockito mock, and lets a
 * real SMTP {@code JavaMailSender}-backed implementation drop in later without touching
 * {@link NotificationService}. The default {@link LoggingEmailSender} logs-only until then.
 *
 * <p><b>Scope — deliberately distinct from email <i>verification</i>.</b> This is the
 * <i>general</i> "send an arbitrary subject/body to a user" seam. The verification flow
 * (OSK-77) owns its own narrow {@code EmailVerificationSender} for minting/dispatching a
 * one-time capability link; the two are intentionally separate concerns and this seam does
 * not replace or wrap it, so building notification delivery here cannot break verification.
 *
 * <p><b>Best-effort side channel.</b> Notifications are non-critical, so implementations
 * SHOULD avoid throwing on an ordinary delivery problem; {@link NotificationService} also
 * defends against a throwing implementation (it logs and swallows) so a delivery failure
 * can never break the caller's flow. Implementations MUST treat {@code toEmail} as PII and
 * never log it verbatim (see {@link LoggingEmailSender#mask(String)}).
 */
public interface EmailSender {

    /**
     * Dispatches a plain notification email to the given address.
     *
     * @param toEmail the recipient's email address (the authoritative address read from the
     *     persisted {@code users} row; guaranteed non-null/non-blank when called by
     *     {@link NotificationService}, which skips the EMAIL channel otherwise)
     * @param subject the email subject line (the notification's title/subject)
     * @param body the email body (the notification's body text)
     */
    void send(String toEmail, String subject, String body);
}
