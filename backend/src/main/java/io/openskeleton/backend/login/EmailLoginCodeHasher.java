package io.openskeleton.backend.login;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Pure helpers for generating, hashing and constant-time-comparing email login codes
 * (OSK-129). Stateless and side-effect-free (aside from the {@link SecureRandom} draw), so it
 * is trivially unit-testable and shared by the issue + verify paths.
 *
 * <p><b>Why hash at all for a 6-digit code:</b> the plaintext code must never be recoverable
 * from a database dump. The hash is defence-in-depth — the real brute-force barrier is the
 * short TTL, the capped attempt counter, single-use consumption and per-email/IP rate limiting
 * (see {@link EmailLoginCodeService}), because a 6-digit space is inherently small. The email
 * is folded into the hashed input ({@code lower(email) + ":" + code}) so a stored hash cannot
 * be replayed against a different address.
 *
 * <p><b>Constant-time comparison:</b> {@link #matches(String, String)} delegates to
 * {@link MessageDigest#isEqual(byte[], byte[])}, which does not short-circuit on the first
 * differing byte — so verification does not leak, via timing, how much of a guessed code was
 * correct.
 */
final class EmailLoginCodeHasher {

    /** Cryptographically-strong source for the numeric code; thread-safe, seeded once. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private EmailLoginCodeHasher() {}

    /**
     * Generate a zero-padded numeric code of the requested length using {@link SecureRandom}.
     *
     * @param length the number of digits (e.g. 6)
     * @return a string of exactly {@code length} decimal digits (leading zeros preserved)
     */
    static String generateNumericCode(int length) {
        // Draw a uniform value in [0, 10^length) and left-pad with zeros to a fixed width, so
        // "000042" is as likely as "912345" and every code is the same length.
        long bound = pow10(length);
        long value = Math.floorMod(SECURE_RANDOM.nextLong(), bound);
        return padToLength(Long.toString(value), length);
    }

    /**
     * Hash a code for storage/comparison: {@code SHA-256(lower(email) + ":" + code)} as hex.
     * The email is expected already normalised (trimmed + lower-cased) by the caller, but it
     * is lower-cased again here defensively so the hash is identity-stable regardless.
     *
     * @param email the normalised email the code belongs to
     * @param code the plaintext code
     * @return the lower-case hex SHA-256 digest
     */
    static String hash(String email, String code) {
        String normalisedEmail = email == null ? "" : email.trim().toLowerCase();
        String input = normalisedEmail + ":" + (code == null ? "" : code);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK spec, so this can never happen on a real JRE; wrap
            // it as an unchecked error rather than push a checked exception through the flow.
            throw new IllegalStateException("SHA-256 is unavailable in this JRE", e);
        }
    }

    /**
     * Constant-time equality of two hex hash strings (order of bytes matters, timing does not).
     *
     * @param expectedHash the stored hash
     * @param candidateHash the hash of the submitted code
     * @return {@code true} iff the two hashes are byte-for-byte equal
     */
    static boolean matches(String expectedHash, String candidateHash) {
        byte[] a = (expectedHash == null ? "" : expectedHash).getBytes(StandardCharsets.UTF_8);
        byte[] b = (candidateHash == null ? "" : candidateHash).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /** 10^n for a small non-negative {@code n} (the code length); kept in a long. */
    private static long pow10(int n) {
        long result = 1L;
        for (int i = 0; i < n; i++) {
            result *= 10L;
        }
        return result;
    }

    /** Left-pad {@code value} with '0' to at least {@code length} chars (never truncates). */
    private static String padToLength(String value, int length) {
        if (value.length() >= length) {
            return value;
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = value.length(); i < length; i++) {
            sb.append('0');
        }
        return sb.append(value).toString();
    }
}
