package io.openskeleton.backend.login;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional persistence seam for email login codes (OSK-129): the two database
 * operations — <b>issue</b> a code and <b>consume</b> (verify + spend) a code — each run in
 * their own transaction here.
 *
 * <p><b>Why this is a separate bean from {@link EmailLoginCodeService}.</b> The verify flow
 * must, on success, call {@code UserService.provisionFromToken(...)}, which is <i>deliberately
 * NOT transactional</i> (OSK-76/OSK-163) — it relies on each of its repository calls being its
 * own transaction so a losing insert can roll back in isolation and be recovered by a re-read.
 * If the whole verify ran inside one transaction, that provisioning contract would break. So
 * the atomic read-modify-write on the code row lives here, behind a {@code @Transactional}
 * method that commits BEFORE the service moves on to the (non-transactional) provisioning +
 * token mint. Splitting it into its own bean is also what makes the {@code @Transactional}
 * proxy actually apply (a self-invocation would bypass it).
 *
 * <p>Both operations enforce the "one active code per email" invariant by keying on the
 * normalised email (the caller normalises before calling): {@link #issue} updates the existing
 * row in place when present (fresh code, TTL and reset counters) or inserts a new one, and
 * {@link #consume} reads that single row.
 */
@Service
class EmailLoginCodeStore {

    private final EmailLoginCodeRepository repository;

    EmailLoginCodeStore(EmailLoginCodeRepository repository) {
        this.repository = repository;
    }

    /**
     * Issue (or re-issue) the single active code for {@code email}: if a row already exists it
     * is overwritten in place with the new hash, TTL, a reset attempt counter and a cleared
     * consumed marker; otherwise a fresh row is inserted. Updating in place (rather than
     * delete-then-insert) sidesteps the UNIQUE-index flush-ordering hazard entirely.
     *
     * <p>{@code created_at} is insert-only ({@code updatable = false}), so on a re-issue it keeps
     * the instant the row was first created — it is a purely informational stamp, and the
     * meaningful "is this code still live" state is {@code expiresAt}, which IS refreshed here.
     *
     * @param email the normalised (trimmed + lower-cased) email
     * @param codeHash the SHA-256 hash of the freshly generated code
     * @param expiresAt the TTL instant after which the code is invalid
     */
    @Transactional
    void issue(String email, String codeHash, Instant expiresAt) {
        EmailLoginCode row = repository.findByEmail(email).orElseGet(EmailLoginCode::new);
        row.setEmail(email);
        row.setCodeHash(codeHash);
        row.setExpiresAt(expiresAt);
        row.setAttempts(0);
        row.setConsumedAt(null);
        repository.saveAndFlush(row);
    }

    /**
     * Verify a submitted code against the stored one and, on success, atomically spend it.
     *
     * <p>Checks run in order — missing row, already consumed, expired, attempt-cap reached,
     * then a constant-time hash comparison. A mismatch increments the attempt counter (and
     * persists it) so repeated guessing walks toward the cap. A match stamps
     * {@code consumedAt} so the code can never be replayed. All within one transaction so the
     * read and the increment/consume commit atomically.
     *
     * @param email the normalised email
     * @param submittedHash the SHA-256 hash of the code the caller submitted
     * @param now the verify instant (used for the TTL check and the consumed stamp)
     * @param maxAttempts the failed-attempt cap from configuration
     * @return {@link Optional#empty()} on success; otherwise the {@link InvalidLoginCodeException.Reason}
     *     the code was rejected for (kept for server-side logging — the wire response is generic)
     */
    @Transactional
    Optional<InvalidLoginCodeException.Reason> consume(
            String email, String submittedHash, Instant now, int maxAttempts) {
        Optional<EmailLoginCode> found = repository.findByEmail(email);
        if (found.isEmpty()) {
            return Optional.of(InvalidLoginCodeException.Reason.NOT_FOUND);
        }
        EmailLoginCode code = found.get();

        if (code.getConsumedAt() != null) {
            return Optional.of(InvalidLoginCodeException.Reason.CONSUMED);
        }
        if (!now.isBefore(code.getExpiresAt())) {
            return Optional.of(InvalidLoginCodeException.Reason.EXPIRED);
        }
        if (code.getAttempts() >= maxAttempts) {
            return Optional.of(InvalidLoginCodeException.Reason.TOO_MANY_ATTEMPTS);
        }
        if (!EmailLoginCodeHasher.matches(code.getCodeHash(), submittedHash)) {
            code.incrementAttempts();
            repository.save(code);
            return Optional.of(InvalidLoginCodeException.Reason.MISMATCH);
        }

        // Correct code, within TTL, not exhausted, not already used: spend it (single-use).
        code.markConsumed(now);
        repository.save(code);
        return Optional.empty();
    }
}
