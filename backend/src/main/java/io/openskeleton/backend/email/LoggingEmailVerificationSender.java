package io.openskeleton.backend.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link EmailVerificationSender} used until the Workspace SMTP sender is wired
 * (HUMAN step OSK-139). It does NOT send an email — it logs that a link was minted and
 * dispatch is pending an SMTP transport, so the endpoint can honestly return
 * {@code 202 Accepted} ("accepted for delivery") today while real delivery is still
 * blocked on the SMTP app-password secret.
 *
 * <p><b>Why keep it as a real bean (not a no-op left out entirely):</b> it makes the
 * dispatch a first-class, injectable collaborator the service always calls, so
 * (a) tests mock it and assert dispatch happened, and (b) swapping in an SMTP-backed
 * implementation later is a one-line bean change with no service edits.
 *
 * <p><b>Secret hygiene:</b> the verification link is a capability URL (it embeds a
 * one-time out-of-band code) and the recipient is PII, so neither is logged verbatim —
 * only a masked recipient and the fact that a link was produced.
 */
@Component
class LoggingEmailVerificationSender implements EmailVerificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailVerificationSender.class);

    @Override
    public void send(String email, String verificationLink) {
        log.info(
                "Email-verification link minted for {} and accepted for delivery; "
                        + "actual SMTP delivery is pending the Workspace SMTP sender (OSK-139).",
                mask(email));
    }

    /**
     * Masks an email for logging: keeps the first character of the local part and the
     * domain, hiding the rest (e.g. {@code alice@example.com} -> {@code a***@example.com}).
     * Defensive against a null/blank/domain-less value so logging never throws.
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
