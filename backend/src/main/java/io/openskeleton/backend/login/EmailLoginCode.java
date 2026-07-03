package io.openskeleton.backend.login;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the {@code email_login_codes} table created by
 * {@code V11__create_email_login_codes.sql} (OSK-129) — one short-lived, one-time numeric
 * code issued for the passwordless email-code sign-in flow.
 *
 * <p>Like {@code User}/{@code DeviceToken}, this is a <b>plain data mapping</b>: the flow
 * logic (issue, verify, consume, throttle) lives in {@link EmailLoginCodeStore} /
 * {@link EmailLoginCodeService}, and Hibernate — running in {@code validate} mode (see
 * {@code application.yml}) — asserts this mapping matches the Flyway-built schema on boot and
 * fails fast on any drift.
 *
 * <p><b>Deliberately does NOT extend {@code BaseEntity}.</b> This is ephemeral, single-use
 * state, not a durable domain aggregate: it needs neither soft-delete (a code is either spent
 * or expired, then purged) nor optimistic {@code @Version} locking (each code is touched by
 * one short verify transaction, and the "one active code per email" invariant is enforced by
 * the UNIQUE index rather than by concurrent updates to the same row). So it carries only its
 * own fields plus a single {@code created_at} stamp — the smaller surface the table actually
 * needs.
 *
 * <p><b>The plaintext code is never held here.</b> {@link #codeHash} is a SHA-256 hash of
 * {@code lower(email) + ":" + code} (see {@link EmailLoginCodeHasher}); the raw code exists
 * only transiently in the issuing request and in the email that is sent.
 */
@Entity
@Table(name = "email_login_codes")
public class EmailLoginCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The normalised (trimmed + lower-cased) email the code was issued for. */
    @Column(name = "email", nullable = false)
    private String email;

    /** SHA-256 hash (hex) of {@code lower(email) + ":" + code} — never the plaintext code. */
    @Column(name = "code_hash", nullable = false)
    private String codeHash;

    /** The instant after which the code is invalid ({@code now >= expiresAt} => expired). */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Count of failed verify attempts; once it reaches the configured max the code is dead. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /** {@code null} while spendable; stamped with the instant the code was successfully used. */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** When the code was issued. Stamped once on INSERT by {@link #onCreate()}. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** No-arg constructor required by JPA/Hibernate. */
    public EmailLoginCode() {}

    /**
     * Create a freshly issued code row.
     *
     * @param email the normalised email the code is for
     * @param codeHash the SHA-256 hash of the code (never the plaintext)
     * @param expiresAt the TTL instant after which the code is invalid
     */
    public EmailLoginCode(String email, String codeHash, Instant expiresAt) {
        this.email = email;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.attempts = 0;
    }

    /** Stamp {@code createdAt} immediately before the first INSERT (app supplies the value). */
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    /** Increment the failed-attempt counter (called on a code mismatch during verify). */
    public void incrementAttempts() {
        this.attempts++;
    }

    /** Mark the code as consumed (single-use) by stamping {@code consumedAt}. */
    public void markConsumed(Instant when) {
        this.consumedAt = when;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(Instant consumedAt) {
        this.consumedAt = consumedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
