-- V11__create_email_login_codes.sql — passwordless email-code login store (OSK-129).
--
-- This lays down the `email_login_codes` table: the short-lived, one-time numeric codes the
-- backend issues for the DEFAULT passwordless sign-in flow. A caller POSTs their email to
-- `/api/v1/auth/email-code/start`; the backend generates a 6-digit code, stores it HASHED
-- here with a short TTL, and emails it (delivery is a logging no-op until the Workspace SMTP
-- sender lands — HUMAN step OSK-139). The caller then POSTs the code to
-- `/api/v1/auth/email-code/verify`; on a valid, unexpired, un-exhausted, un-reused code the
-- backend mints a Firebase custom token for that identity and the web client completes
-- sign-in with `signInWithCustomToken`.
--
-- Version note: V10 (identity-uniqueness indexes on users) is the highest existing migration,
-- so this is V11 — it sits next on the Flyway timeline without a version collision. OSK-161
-- later moves this ephemeral state to a shared cache/store; a plain table is the right,
-- simplest home for it now (durable across restarts, transactional, and trivially queryable).
--
-- SECURITY — the code itself is NEVER stored. `code_hash` holds a SHA-256 hash of
-- `lower(email) + ":" + code`, so a database dump reveals no usable codes (and the email is
-- folded into the hash so a hash cannot be replayed against a different address). The code is
-- also defended by three independent limits below (short TTL, a capped attempt counter, and
-- single-use consumption) plus per-email/IP rate limiting in the service layer — a 6-digit
-- space is small, so those limits, not the hash, are the real brute-force barrier.
--
-- PK strategy mirrors the users/device_tokens/audit tables (OSK-60/OSK-80/OSK-154): a UUID
-- surrogate key defaulted from gen_random_uuid() (the pgcrypto extension enabled by the V1
-- baseline). The application (Hibernate GenerationType.UUID) supplies the id on insert; the
-- column DEFAULT is a safety net for any out-of-band row. The EmailLoginCode JPA entity maps
-- this table exactly, and Hibernate — running in `validate` mode (see application.yml) —
-- asserts that mapping on boot and fails fast on any drift.
CREATE TABLE email_login_codes (
    -- Surrogate primary key. UUID (not a serial) keeps ids non-guessable and stable.
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- WHO the code was issued for: the caller's email, stored already NORMALISED
    -- (trimmed + lower-cased) by the service so lookups on start/verify are exact and the
    -- UNIQUE index below can enforce "one active code per email" without a functional index.
    -- Email identity is case-insensitive (Alice@x.com == alice@x.com), which is why the
    -- normalisation happens before persistence — matching how OSK-163 reconciles users by
    -- lower(email).
    email       TEXT         NOT NULL,

    -- The SHA-256 hash (hex) of `lower(email) + ":" + code`. The plaintext code is never
    -- stored; verify hashes the submitted code the same way and compares in constant time.
    code_hash   TEXT         NOT NULL,

    -- TTL: the instant after which the code is invalid regardless of attempts. Verify treats
    -- `now >= expires_at` as expired. A short window (default 10 min, see application.yml) is
    -- the first of the three brute-force defences.
    expires_at  TIMESTAMPTZ  NOT NULL,

    -- FAILED verify attempts against this code. Incremented on every mismatch; once it reaches
    -- the configured maximum (default 5) the code is dead even before it expires — the second
    -- brute-force defence. Starts at 0 for a freshly issued code.
    attempts    INTEGER      NOT NULL DEFAULT 0,

    -- Single-use marker: NULL while the code is still spendable, stamped with the instant it
    -- was successfully verified. A code with a non-NULL `consumed_at` is rejected on any later
    -- verify — the third defence (no replay of an already-used code). Nullable, mirroring the
    -- soft-delete marker convention on other tables.
    consumed_at TIMESTAMPTZ,

    -- When the code was issued. DEFAULT now() covers any out-of-band insert; rows written
    -- through the app are stamped by the entity's @PrePersist callback. Handy for auditing and
    -- for a future scheduled purge of stale rows.
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- At most ONE active code per email: issuing a new code deletes the previous row for that
-- email and inserts a fresh one (the service does this in a single transaction), so a user
-- always has exactly one code in play. The UNIQUE index both enforces that invariant as a
-- hard backstop and gives the by-email lookup on verify a cheap index to use.
CREATE UNIQUE INDEX ux_email_login_codes_email ON email_login_codes (email);

-- Supports an inexpensive sweep of expired rows (a future janitor / OSK-161 store migration
-- can prune `WHERE expires_at < now()` without scanning the table).
CREATE INDEX idx_email_login_codes_expires_at ON email_login_codes (expires_at);
