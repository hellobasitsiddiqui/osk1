-- V10__add_identity_uniqueness_to_users.sql — one human = one account (OSK-163).
--
-- Enforces the platform's account-identity invariant AT THE DATABASE LAYER: a person is
-- keyed by a canonical identity — their email and/or their phone — so signing in via
-- either method must resolve to a single `users` row, never a duplicate. The V2 table
-- already makes `firebase_uid` UNIQUE (one Firebase credential → one row); that alone is
-- NOT enough, because one human can hold several Firebase uids for the SAME email/phone
-- (e.g. an email/password sign-in and a later Google or phone sign-in that Firebase issues
-- a different uid for). Without this migration each such uid would mint its own row — the
-- exact "duplicate account" this ticket forbids. These indexes are the hard backstop that
-- makes a second row for a known identity impossible even under a race; the reconciling
-- JIT provisioning in UserService is the cooperative half that resolves to the existing
-- account instead of tripping them on the happy path.
--
-- Two partial UNIQUE indexes (not table constraints) are used deliberately, because the
-- uniqueness we want is CONDITIONAL and a plain UNIQUE constraint cannot express either
-- condition:
--
--   1) ux_users_email_lower_active — UNIQUE on lower(email).
--      * lower(email): email identity is case-INSENSITIVE (Alice@x.com and alice@x.com are
--        the same person), so the uniqueness is over the normalised, lower-cased value. A
--        functional index is the only way to enforce case-insensitive uniqueness in
--        Postgres. The reconciliation query (UserRepository.findByEmailIgnoreCase) matches
--        with `lower(email) = lower(?)`, so it can USE this very index.
--      * WHERE email IS NOT NULL: email is optional on a Firebase token (phone-only /
--        anonymous sign-in carry none), so it is a NULLABLE column. The partial predicate
--        keeps NULL-email rows OUT of the index entirely, which lets ANY number of users
--        have no email — only rows that actually HAVE an email are constrained to be
--        unique. (Postgres already treats NULLs as distinct in a UNIQUE index, but making
--        it a partial index states the intent and pairs symmetrically with the phone one.)
--
--   2) ux_users_phone_active — UNIQUE on phone (exact match; phone is stored in canonical
--      E.164 form from Firebase, so no normalisation function is needed), WHERE phone IS
--      NOT NULL for the same "multiple users may have no phone" reason.
--
-- ACTIVE-SCOPED (`AND deleted_at IS NULL`): both indexes are scoped to NON-soft-deleted
-- rows on purpose, to match the entity's read model. `User` carries
-- @SQLRestriction("deleted_at is null"), so every application read — including the
-- reconciliation lookups — only ever sees active rows. Scoping the uniqueness the same way
-- keeps the constraint and the query in agreement: a soft-deleted account does NOT reserve
-- its email/phone (a genuinely new person may reuse it), and a soft-deleted row can never
-- be the hidden cause of an unreconcilable INSERT failure (the lookup would not find it, so
-- an active-only index means a violation always corresponds to a row the app CAN see and
-- reconcile against). It also means restoring a soft-deleted user re-asserts uniqueness at
-- that moment — the correct time to detect a clash.
--
-- Failure mode note: creating a UNIQUE index fails loudly if the existing data already
-- holds two active rows sharing an email (case-insensitively) or a phone. That is the
-- intended, fail-fast behaviour — such data is exactly the duplicate-account state this
-- ticket eliminates and would have to be de-duplicated before the invariant can hold. The
-- skeleton ships no seed users, so there is nothing to de-duplicate here.
--
-- No CONCURRENTLY: Flyway runs each migration inside a transaction and CREATE INDEX
-- CONCURRENTLY cannot run in one. A plain CREATE INDEX takes a brief write lock, which is
-- fine for this table's size and for a migration that runs before the app serves traffic.
CREATE UNIQUE INDEX ux_users_email_lower_active
    ON users (lower(email))
    WHERE email IS NOT NULL AND deleted_at IS NULL;

CREATE UNIQUE INDEX ux_users_phone_active
    ON users (phone)
    WHERE phone IS NOT NULL AND deleted_at IS NULL;
