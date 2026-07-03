-- V6__add_account_type_to_users.sql — flag test/synthetic accounts (OSK-168).
--
-- Adds one column to the OSK-60 `users` table so genuine end-user accounts can be
-- told apart from test/synthetic ones (created by e2e/QA). This is the data-model +
-- backend part only: later work uses the flag to exclude test accounts from
-- metrics/analytics/notifications and to clean them up. No auto-classification is
-- done here — that policy stays with the callers (the future test-email hook /
-- admin); this migration just lays down the column.
--
--   account_type — REAL (a genuine end-user account, the overwhelming common case)
--                  or TEST (a synthetic/QA account). NOT NULL DEFAULT 'REAL' so every
--                  existing row and every freshly provisioned user is REAL unless a
--                  caller explicitly marks it TEST. Stored as TEXT holding the enum
--                  name, mirroring how `role` is persisted (@Enumerated(STRING)), so
--                  the value is stable and human-readable. Hibernate runs in `validate`
--                  mode (see application.yml), so this column MUST exist and match the
--                  `User.accountType` mapping on boot.
--
-- A CHECK constraint pins the allowed values at the database as a backstop to the
-- in-code AccountType enum, so a bad value can never be written out-of-band.
ALTER TABLE users
    ADD COLUMN account_type TEXT NOT NULL DEFAULT 'REAL';

ALTER TABLE users
    ADD CONSTRAINT users_account_type_check CHECK (account_type IN ('REAL', 'TEST'));
