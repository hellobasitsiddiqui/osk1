-- V8__add_account_lifecycle_fields_to_users.sql — account lifecycle fields (OSK-69).
--
-- Extends the OSK-60 `users` table with the account-lifecycle state a caller records on
-- themselves through the /api/v1/me surface: whether they have finished onboarding, which
-- terms version they accepted (and when), and whether their age has been verified. This is
-- the data-model half of the ticket; the entity mapping (User), the /me read/write surface
-- (MeController + the new PATCH /me/lifecycle) and the reuse of the profile-change history
-- (audit_events via ProfileChange) are the code half.
--
-- Hibernate runs in `validate` mode (see application.yml), so every column below MUST exist
-- and match the `User` field mapping exactly or the context fails to boot — the same
-- fast-fail guarantee V7 pairs with.
--
-- Nullability / defaults:
--   onboarding_completed   — has the user completed the first-run onboarding flow? A boolean
--                            state that every row must carry, so NOT NULL DEFAULT false: every
--                            existing row and every freshly JIT-provisioned user (OSK-76)
--                            starts "not yet onboarded" until the client marks it complete.
--   terms_accepted_version — the version string of the terms/policy the user accepted (free
--                            text, e.g. 'v1.0' / '2026-01-01'). NULLABLE: null means the user
--                            has never accepted any terms yet. Paired with terms_accepted_at.
--   terms_accepted_at      — when the acceptance was recorded, as a SERVER timestamp (never
--                            client-supplied). NULLABLE: null until the first acceptance. Stored
--                            as timestamptz to match the java.time.Instant mapping (UTC), like
--                            the audit/base-entity timestamps.
--   age_verified           — has the user's age been verified? NOT NULL DEFAULT false, same
--                            shape as onboarding_completed: a well-defined state on every row.
ALTER TABLE users
    ADD COLUMN onboarding_completed   BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN terms_accepted_version TEXT        NULL,
    ADD COLUMN terms_accepted_at      TIMESTAMPTZ NULL,
    ADD COLUMN age_verified           BOOLEAN     NOT NULL DEFAULT false;
