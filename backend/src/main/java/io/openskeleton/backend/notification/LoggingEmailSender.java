package io.openskeleton.backend.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link EmailSender} used until the Workspace SMTP sender is wired (HUMAN step
 * OSK-139). It does NOT send an email — it logs that a notification email <i>would</i> have
 * been sent, so notification routing (OSK-89) can be built, wired and unit-tested today
 * while real delivery is still blocked on the SMTP app-password secret.
 *
 * <p><b>Why a real bean (not a no-op left out entirely):</b> it makes the email dispatch a
 * first-class, injectable collaborator {@link NotificationService} always calls on the
 * EMAIL channel, so (a) tests mock it and assert dispatch happened, and (b) swapping in an
 * SMTP-backed implementation later is a one-line bean change with no service edits. This is
 * exactly the "degrade gracefully when SMTP is not live" path — the EMAIL channel routes
 * cleanly to a logged no-op rather than erroring.
 *
 * <p><b>Secret / PII hygiene:</b> the recipient address is PII, so it is never logged
 * verbatim — only a {@link #mask(String) masked} form and the subject (subjects are not
 * sensitive here) are recorded.
 */
@Component
class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String toEmail, String subject, String body) {
        log.info(
                "Notification email to {} (subject: \"{}\") accepted for delivery; actual SMTP "
                        + "delivery is pending the Workspace SMTP sender (OSK-139).",
                mask(toEmail),
                subject);
    }

    /**
     * Masks an email for logging: keeps the first character of the local part and the whole
     * domain, hiding the rest (e.g. {@code alice@example.com} -> {@code a***@example.com}).
     * Defensive against a null/blank/domain-less value so logging never throws.
     *
     * @param email the address to mask (may be {@code null}/blank)
     * @return a masked, log-safe rendering that reveals no more than the first char + domain
     */
    static String mask(String email) {
        if (email == null || email.isBlank()) {
            return "<none>";
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            // No local part to preserve (or no '@'): reveal nothing but the shape.
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
