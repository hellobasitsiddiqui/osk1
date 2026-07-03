package io.openskeleton.backend.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link EmailLoginCodeStore} — the transactional issue/consume seam — with a
 * mocked repository, so the TTL / attempt-cap / single-use / constant-time-compare branches are
 * all exercised without a database. The DB-backed happy path is separately proven end-to-end in
 * {@code EmailCodeControllerIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class EmailLoginCodeStoreTest {

    private static final String EMAIL = "alice@example.com";
    private static final int MAX_ATTEMPTS = 5;

    @Mock
    private EmailLoginCodeRepository repository;

    private EmailLoginCodeStore store() {
        return new EmailLoginCodeStore(repository);
    }

    // ---- issue ------------------------------------------------------------------------

    @Test
    void issueInsertsAFreshRowWhenNoneExists() {
        when(repository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        Instant now = Instant.parse("2026-07-03T10:00:00Z");
        Instant expiresAt = now.plus(10, ChronoUnit.MINUTES);

        store().issue(EMAIL, "hash-abc", expiresAt);

        ArgumentCaptor<EmailLoginCode> saved = ArgumentCaptor.forClass(EmailLoginCode.class);
        verify(repository).saveAndFlush(saved.capture());
        EmailLoginCode row = saved.getValue();
        assertThat(row.getEmail()).isEqualTo(EMAIL);
        assertThat(row.getCodeHash()).isEqualTo("hash-abc");
        assertThat(row.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getConsumedAt()).isNull();
    }

    @Test
    void issueReusesAndResetsAnExistingRowInPlace() {
        // An existing row with prior attempts + a consumed marker must be reset by a re-issue.
        EmailLoginCode existing = new EmailLoginCode(EMAIL, "old-hash", Instant.parse("2026-07-03T09:00:00Z"));
        existing.setAttempts(3);
        existing.markConsumed(Instant.parse("2026-07-03T09:05:00Z"));
        when(repository.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

        Instant now = Instant.parse("2026-07-03T10:00:00Z");
        Instant expiresAt = now.plus(10, ChronoUnit.MINUTES);
        store().issue(EMAIL, "new-hash", expiresAt);

        // Same instance saved, fields overwritten with the new code + counters reset.
        verify(repository).saveAndFlush(existing);
        assertThat(existing.getCodeHash()).isEqualTo("new-hash");
        assertThat(existing.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(existing.getAttempts()).isZero();
        assertThat(existing.getConsumedAt()).isNull();
    }

    // ---- consume ----------------------------------------------------------------------

    @Test
    void consumeFailsWhenNoCodeExists() {
        when(repository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        Optional<InvalidLoginCodeException.Reason> result =
                store().consume(EMAIL, "any", Instant.parse("2026-07-03T10:00:00Z"), MAX_ATTEMPTS);
        assertThat(result).contains(InvalidLoginCodeException.Reason.NOT_FOUND);
        verify(repository, never()).save(any());
    }

    @Test
    void consumeFailsWhenAlreadyConsumed() {
        EmailLoginCode code = codeWithHash("hash-abc", Instant.parse("2026-07-03T10:10:00Z"));
        code.markConsumed(Instant.parse("2026-07-03T10:01:00Z"));
        when(repository.findByEmail(EMAIL)).thenReturn(Optional.of(code));

        Optional<InvalidLoginCodeException.Reason> result =
                store().consume(EMAIL, "hash-abc", Instant.parse("2026-07-03T10:02:00Z"), MAX_ATTEMPTS);

        // Even the CORRECT hash is refused once the code was already used (no replay).
        assertThat(result).contains(InvalidLoginCodeException.Reason.CONSUMED);
    }

    @Test
    void consumeFailsWhenExpired() {
        EmailLoginCode code = codeWithHash("hash-abc", Instant.parse("2026-07-03T10:00:00Z"));
        when(repository.findByEmail(EMAIL)).thenReturn(Optional.of(code));

        // now == expiresAt is already expired (boundary is exclusive).
        Optional<InvalidLoginCodeException.Reason> result =
                store().consume(EMAIL, "hash-abc", Instant.parse("2026-07-03T10:00:00Z"), MAX_ATTEMPTS);

        assertThat(result).contains(InvalidLoginCodeException.Reason.EXPIRED);
    }

    @Test
    void consumeFailsWhenAttemptCapReached() {
        EmailLoginCode code = codeWithHash("hash-abc", Instant.parse("2026-07-03T10:10:00Z"));
        code.setAttempts(MAX_ATTEMPTS);
        when(repository.findByEmail(EMAIL)).thenReturn(Optional.of(code));

        Optional<InvalidLoginCodeException.Reason> result =
                store().consume(EMAIL, "hash-abc", Instant.parse("2026-07-03T10:01:00Z"), MAX_ATTEMPTS);

        assertThat(result).contains(InvalidLoginCodeException.Reason.TOO_MANY_ATTEMPTS);
    }

    @Test
    void consumeMismatchIncrementsAttemptsAndPersists() {
        EmailLoginCode code = codeWithHash("hash-correct", Instant.parse("2026-07-03T10:10:00Z"));
        when(repository.findByEmail(EMAIL)).thenReturn(Optional.of(code));

        Optional<InvalidLoginCodeException.Reason> result =
                store().consume(EMAIL, "hash-wrong", Instant.parse("2026-07-03T10:01:00Z"), MAX_ATTEMPTS);

        assertThat(result).contains(InvalidLoginCodeException.Reason.MISMATCH);
        assertThat(code.getAttempts()).isEqualTo(1);
        assertThat(code.getConsumedAt()).isNull();
        verify(repository).save(code);
    }

    @Test
    void consumeSuccessMarksTheCodeConsumed() {
        EmailLoginCode code = codeWithHash("hash-correct", Instant.parse("2026-07-03T10:10:00Z"));
        when(repository.findByEmail(EMAIL)).thenReturn(Optional.of(code));
        Instant now = Instant.parse("2026-07-03T10:01:00Z");

        Optional<InvalidLoginCodeException.Reason> result = store().consume(EMAIL, "hash-correct", now, MAX_ATTEMPTS);

        assertThat(result).isEmpty();
        assertThat(code.getConsumedAt()).isEqualTo(now);
        assertThat(code.getAttempts()).isZero();
        verify(repository).save(code);
    }

    private static EmailLoginCode codeWithHash(String hash, Instant expiresAt) {
        return new EmailLoginCode(EMAIL, hash, expiresAt);
    }
}
