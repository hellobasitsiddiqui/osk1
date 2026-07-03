package io.openskeleton.backend.login;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EmailLoginCodeHasher} — the pure code generation / hashing / compare
 * helpers behind the OSK-129 flow. No Spring, no DB.
 */
class EmailLoginCodeHasherTest {

    private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");

    @RepeatedTest(50)
    void generatesAFixedLengthNumericCodeIncludingLeadingZeros() {
        String code = EmailLoginCodeHasher.generateNumericCode(6);
        // Always exactly 6 decimal digits — leading zeros preserved (so "000042" is valid).
        assertThat(SIX_DIGITS.matcher(code).matches())
                .as("code '%s' should be exactly 6 digits", code)
                .isTrue();
    }

    @Test
    void generatesDifferentCodesAcrossManyDraws() {
        // Not a strict guarantee, but across 200 secure-random 6-digit draws collisions should be
        // rare — assert we see a healthy amount of variety (proves it isn't a constant).
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            seen.add(EmailLoginCodeHasher.generateNumericCode(6));
        }
        assertThat(seen.size()).isGreaterThan(150);
    }

    @Test
    void hashIsDeterministicAndCaseInsensitiveInTheEmail() {
        String a = EmailLoginCodeHasher.hash("alice@example.com", "123456");
        String b = EmailLoginCodeHasher.hash("ALICE@example.com", "123456");
        // Same email (case-folded) + same code => identical hash.
        assertThat(a).isEqualTo(b);
        // A SHA-256 hex digest is 64 chars and never contains the plaintext code.
        assertThat(a).hasSize(64).doesNotContain("123456");
    }

    @Test
    void hashBindsTheCodeToTheEmail() {
        // The SAME code hashed for a DIFFERENT email must differ — so a stolen hash cannot be
        // replayed against another address.
        String forAlice = EmailLoginCodeHasher.hash("alice@example.com", "123456");
        String forBob = EmailLoginCodeHasher.hash("bob@example.com", "123456");
        assertThat(forAlice).isNotEqualTo(forBob);
    }

    @Test
    void matchesIsTrueOnlyForEqualHashes() {
        String hash = EmailLoginCodeHasher.hash("alice@example.com", "123456");
        String same = EmailLoginCodeHasher.hash("alice@example.com", "123456");
        String different = EmailLoginCodeHasher.hash("alice@example.com", "654321");

        assertThat(EmailLoginCodeHasher.matches(hash, same)).isTrue();
        assertThat(EmailLoginCodeHasher.matches(hash, different)).isFalse();
        // Null-tolerant (never throws) and treats null as non-matching against a real hash.
        assertThat(EmailLoginCodeHasher.matches(hash, null)).isFalse();
        assertThat(EmailLoginCodeHasher.matches(null, null)).isTrue();
    }
}
