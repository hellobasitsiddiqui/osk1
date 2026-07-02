-- V1__init.sql — baseline schema migration (OSK-32).
--
-- This is the first Flyway migration and establishes the database baseline.
-- There are no domain tables yet; it only enables the pgcrypto extension so that
-- later migrations can use gen_random_uuid() (and other crypto helpers) for
-- primary keys without each one having to re-declare the extension.
--
-- IF NOT EXISTS keeps the statement idempotent, so re-running against a database
-- that already has the extension (e.g. a shared environment) is a harmless no-op.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
