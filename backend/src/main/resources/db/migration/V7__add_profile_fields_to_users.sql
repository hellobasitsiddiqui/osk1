-- V7__add_profile_fields_to_users.sql — richer self-service profile fields (OSK-67).
--
-- Extends the OSK-60 `users` table with the optional profile attributes a caller can
-- set on themselves via PATCH /api/v1/me (names, city, age, phone, notification
-- preference, timezone, locale). This is the data-model half of the ticket; the entity
-- mapping (User), the /me read/write surface (MeController) and the per-field change
-- history (reusing audit_events via ProfileChange) are the code half.
--
-- Hibernate runs in `validate` mode (see application.yml), so every column below MUST
-- exist and match the `User` field mapping exactly or the context fails to boot — which
-- is precisely the fast-fail guarantee this migration pairs with.
--
-- Nullability: all of these are OPTIONAL profile decoration, so they are NULLABLE and a
-- freshly JIT-provisioned user (OSK-76) simply has them all NULL until it fills them in
-- — the first-login upsert never sets them and must not break. The one exception is
-- notification_preference: it is NOT NULL DEFAULT 'EMAIL' so every existing row and every
-- new user has a well-defined delivery channel (email) unless they opt into another. It
-- is stored as TEXT holding the enum NAME (@Enumerated(STRING)), mirroring how `role` and
-- `account_type` are persisted, so the value is stable and human-readable.
--
--   first_name / last_name — the caller's given/family names (display_name stays a
--                            separate, independently settable field).
--   city                   — free-text city.
--   age                    — integer age; the sane-range check (13..120) is enforced in
--                            the API layer (bean validation), not pinned here, since the
--                            acceptable range is product policy that may evolve.
--   phone                  — free-text phone; format is validated leniently in the API.
--   notification_preference— EMAIL (default), PUSH or NONE — the channel the platform
--                            should use to reach this user.
--   timezone               — IANA zone id (e.g. 'Europe/London'), validated in the API.
--   locale                 — BCP-47 language tag (e.g. 'en-GB'), validated in the API.
ALTER TABLE users
    ADD COLUMN first_name              TEXT    NULL,
    ADD COLUMN last_name               TEXT    NULL,
    ADD COLUMN city                    TEXT    NULL,
    ADD COLUMN age                     INT     NULL,
    ADD COLUMN phone                   TEXT    NULL,
    ADD COLUMN notification_preference TEXT    NOT NULL DEFAULT 'EMAIL',
    ADD COLUMN timezone                TEXT    NULL,
    ADD COLUMN locale                  TEXT    NULL;

-- Pin the allowed notification_preference values at the database as a backstop to the
-- in-code NotificationPreference enum, mirroring the users_account_type_check pattern —
-- a bad value can never be written out-of-band.
ALTER TABLE users
    ADD CONSTRAINT users_notification_preference_check
        CHECK (notification_preference IN ('EMAIL', 'PUSH', 'NONE'));
