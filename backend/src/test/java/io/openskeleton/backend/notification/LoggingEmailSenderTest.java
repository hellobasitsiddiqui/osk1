package io.openskeleton.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link LoggingEmailSender} (OSK-89): the default, SMTP-not-yet-wired EMAIL
 * seam. Verifies it is a safe no-op (never throws) and that the PII-masking used for logging
 * covers the normal, null/blank, and domain-less shapes.
 */
class LoggingEmailSenderTest {

    private final LoggingEmailSender sender = new LoggingEmailSender();

    @Test
    void sendIsASafeNoOpAndNeverThrows() {
        assertThatCode(() -> sender.send("alice@example.com", "Subject", "Body"))
                .doesNotThrowAnyException();
    }

    @Test
    void sendToleratesANullRecipient() {
        // Defensive: the service guards against this, but the sender must not throw either.
        assertThatCode(() -> sender.send(null, "Subject", "Body")).doesNotThrowAnyException();
    }

    @Test
    void maskKeepsFirstCharAndDomain() {
        assertThat(LoggingEmailSender.mask("alice@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void maskRendersNullAndBlankAsPlaceholder() {
        assertThat(LoggingEmailSender.mask(null)).isEqualTo("<none>");
        assertThat(LoggingEmailSender.mask("   ")).isEqualTo("<none>");
    }

    @Test
    void maskHidesEverythingWhenThereIsNoLocalPart() {
        // No '@' at all, and an '@' with an empty local part, both reveal only the shape.
        assertThat(LoggingEmailSender.mask("not-an-email")).isEqualTo("***");
        assertThat(LoggingEmailSender.mask("@example.com")).isEqualTo("***");
    }
}
