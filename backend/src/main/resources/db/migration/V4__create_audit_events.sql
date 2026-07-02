-- V4__create_audit_events.sql — the append-only audit log (OSK-80).
--
-- This lays down the `audit_events` table: an immutable, append-only record of the
-- security-relevant actions the platform takes (a user being provisioned, a profile
-- updated, a role changed, an account enabled/disabled, ...). It is written through
-- the AuditService.record(...) seam and is only ever INSERTed into and read back —
-- never UPDATEd or DELETEd. There is deliberately NO `updated_at` column: an audit
-- row is history, and history does not change.
--
-- Version note: V1 (baseline/pgcrypto) and V2 (users) already exist, and a sibling
-- ticket (OSK-84) is taking V3, so this migration is intentionally V4 to avoid a
-- version collision on the Flyway timeline.
--
-- PK strategy mirrors the users table (OSK-60): a UUID surrogate key defaulted from
-- gen_random_uuid(). The V1 baseline (OSK-32) enabled the pgcrypto extension exactly
-- so domain tables can use gen_random_uuid() without re-declaring it. The application
-- (Hibernate GenerationType.UUID) supplies the id on insert; the column DEFAULT is a
-- safety net for any row created out-of-band. The AuditEvent JPA entity maps this
-- table exactly, and Hibernate — running in `validate` mode (see application.yml) —
-- asserts that mapping on boot and fails fast on any drift.
CREATE TABLE audit_events (
    -- Surrogate primary key. UUID (not a serial) keeps ids non-guessable and stable.
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- WHO performed the action: the Firebase UID of the acting user. Nullable because
    -- some events are system-originated (no human actor) — this is the natural actor
    -- identity every authenticated caller carries, matching users.firebase_uid, but is
    -- stored as a plain value (not a FK) so an audit row survives even if the user row
    -- is ever removed: the log must outlive the subject.
    actor_firebase_uid  TEXT,

    -- WHAT happened: the action name (the AuditAction enum, persisted by name). NOT
    -- NULL — every audit row records some action.
    action              TEXT         NOT NULL,

    -- WHAT it was done to: a coarse target descriptor (e.g. "USER") and the target's
    -- id. Both nullable because not every action targets a specific entity.
    target_type         TEXT,
    target_id           TEXT,

    -- Optional structured context for the event (e.g. which fields changed), stored as
    -- JSONB so it is queryable. Nullable — most events need no extra detail. By policy
    -- this must NEVER contain secrets or tokens; it is for benign, auditable metadata.
    metadata            JSONB,

    -- WHEN it happened. DEFAULT now() covers any out-of-band insert; for rows written
    -- through the app the entity's @PrePersist callback stamps it. There is no
    -- updated_at: audit rows are immutable, so they are never updated.
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Read pattern: audit logs are typically browsed newest-first and filtered by actor.
-- These indexes keep those queries cheap as the (append-only, ever-growing) table
-- scales. They add nothing to the append-only guarantee; they only serve reads.
CREATE INDEX idx_audit_events_created_at ON audit_events (created_at DESC);
CREATE INDEX idx_audit_events_actor ON audit_events (actor_firebase_uid);
