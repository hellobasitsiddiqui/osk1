-- V5__create_device_tokens.sql — per-device push registration tokens (OSK-154).
--
-- This lays down the `device_tokens` table: the set of push notification tokens a
-- user's devices have registered with the backend, so a later send-push service can
-- fan a notification out to every device a user is signed in on. Each row is one
-- (device token -> user) binding. It is written through the DeviceTokenService seam:
-- upserted on `POST /api/v1/me/devices` (idempotent per token) and removed on
-- `DELETE /api/v1/me/devices/{token}` (sign-out) or pruned when a provider reports a
-- token as stale.
--
-- Version note: V1 (baseline/pgcrypto), V2 (users), V3 (soft-delete on users) and
-- V4 (audit_events) already exist, so this migration is V5 to sit next on the Flyway
-- timeline without a version collision.
--
-- PK strategy mirrors the users/audit tables (OSK-60/OSK-80): a UUID surrogate key
-- defaulted from gen_random_uuid(). The V1 baseline (OSK-32) enabled the pgcrypto
-- extension exactly so domain tables can use gen_random_uuid() without re-declaring
-- it. The application (Hibernate GenerationType.UUID) supplies the id on insert; the
-- column DEFAULT is a safety net for any row created out-of-band. The DeviceToken JPA
-- entity maps this table exactly, and Hibernate — running in `validate` mode (see
-- application.yml) — asserts that mapping on boot and fails fast on any drift.
CREATE TABLE device_tokens (
    -- Surrogate primary key. UUID (not a serial) keeps ids non-guessable and stable.
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- WHOSE device this is: the owning user. A real foreign key to users(id) so a
    -- token cannot be orphaned from a non-existent user; NOT NULL because every token
    -- belongs to exactly one user. The service resolves this id from the caller's
    -- verified Firebase UID before insert (identity comes only from the token).
    user_id     UUID         NOT NULL REFERENCES users(id),

    -- The push registration token itself (e.g. an FCM/APNs token). UNIQUE so a given
    -- device token maps to exactly one row: re-registering the SAME token is an upsert
    -- (update in place), never a duplicate — this is the idempotency the endpoint
    -- guarantees. NOT NULL because a row without a token is meaningless.
    token       TEXT         NOT NULL UNIQUE,

    -- WHICH platform the token is for (ANDROID / IOS / WEB), stored as text (the enum
    -- name) matching the DeviceToken.Platform enum. NOT NULL — a token is always tied
    -- to a concrete platform, which the send-push service needs to pick a transport.
    platform    TEXT         NOT NULL,

    -- Audit timestamps. DEFAULT now() covers any out-of-band insert; for rows written
    -- through the app the entity's @PrePersist/@PreUpdate callbacks stamp them. An
    -- upsert (token re-registered) advances updated_at so staleness can be reasoned about.
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Read pattern: the send-push service looks up all tokens for a given user to fan a
-- notification out to every device. This index keeps that per-user lookup cheap as the
-- table grows. (The token UNIQUE constraint already provides an index for the
-- upsert/deregister/prune by-token lookups, so no separate token index is needed.)
CREATE INDEX idx_device_tokens_user_id ON device_tokens (user_id);
