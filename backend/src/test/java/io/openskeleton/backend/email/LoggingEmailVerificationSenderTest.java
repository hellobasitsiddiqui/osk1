package io.openskeleton.backend.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LoggingEmailVerificationSender} — the stub dispatch seam used
 * until the Workspace SMTP sender lands (OSK-139). Verifies the masking that keeps the
 * recipient out of the logs in cleartext and that dispatch never throws (it must not be
 * able to break the request just because delivery is still stubbed).
 */
class LoggingEmailVerificationSenderTest {

    private final LoggingEmailVerificationSender sender = new LoggingEmailVerificationSender();

    @Test
    void masksAnOrdinaryAddressToFirstCharPlusDomain() {
        assertThat(LoggingEmailVerificationSender.mask("alice@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void nullOrBlankAddressMasksToPlaceholder() {
        assertThat(LoggingEmailVerificationSender.mask(null)).isEqualTo("<none>");
        assertThat(LoggingEmailVerificationSender.mask("")).isEqualTo("<none>");
        assertThat(LoggingEmailVerificationSender.mask("   ")).isEqualTo("<none>");
    }

    @Test
    void addressWithNoUsableLocalPartMasksToAsterisks() {
        assertThat(LoggingEmailVerificationSender.mask("no-at-sign")).isEqualTo("***"); // no '@'
        assertThat(LoggingEmailVerificationSender.mask("@leading.example")).isEqualTo("***"); // '@' at index 0
    }

    @Test
    void sendDispatchesWithoutThrowing() {
        assertThatCode(() -> sender.send("bob@example.com", "https://verify.example/oob?code=secret"))
                .doesNotThrowAnyException();
    }
}
