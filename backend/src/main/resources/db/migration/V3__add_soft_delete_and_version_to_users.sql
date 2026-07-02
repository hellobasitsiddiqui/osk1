-- V3__add_soft_delete_and_version_to_users.sql — soft-delete + optimistic
-- concurrency on the users table (OSK-84), the first adopter of the reusable
-- BaseEntity (io.openskeleton.backend.common.BaseEntity).
--
-- Two columns are added to the OSK-60 `users` table so the shared BaseEntity
-- mapping validates against the real schema (Hibernate runs in `validate` mode —
-- see application.yml — so these columns MUST exist and match the entity types):
--
--   deleted_at  — soft-delete marker. NULL = active (the overwhelming common case),
--                 a timestamp = logically deleted. Nullable by design: normal reads
--                 filter on `deleted_at IS NULL` (the entity carries an
--                 @SQLRestriction) so deleted rows are excluded by default, while a
--                 restore path clears it back to NULL. We never physically DELETE.
--
--   version     — optimistic-locking counter for @Version. NOT NULL DEFAULT 0 so
--                 every existing row starts at a well-defined 0 and Hibernate can
--                 increment it on each UPDATE; a concurrent stale-version write is
--                 rejected (StaleObjectStateException -> HTTP 409) instead of
--                 silently overwriting a newer change.
ALTER TABLE users
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ADD COLUMN version    BIGINT      NOT NULL DEFAULT 0;
