-- V2__create_users.sql — the users table (OSK-60), the Epic-2 data-layer root.
--
-- This is the first domain table. It is keyed by the Firebase UID (the stable
-- identity every authenticated caller carries): the application will look users up
-- by `firebase_uid`, so that column is UNIQUE + NOT NULL. There is no business
-- logic here yet — this migration only lays down the schema that the `User` JPA
-- entity maps and that Hibernate (running in `validate` mode) asserts against on
-- boot. Several later Epic-2 tickets (JIT provisioning, RBAC, /me) build on it.
--
-- PK strategy: a UUID surrogate key defaulted from gen_random_uuid(). The V1
-- baseline (OSK-32) already enabled the pgcrypto extension precisely so domain
-- tables could use gen_random_uuid() without re-declaring it, so we follow that
-- convention here. The application (Hibernate GenerationType.UUID) supplies the id
-- on insert; the column DEFAULT is a safety net for any row created out-of-band.
CREATE TABLE users (
    -- Surrogate primary key. UUID (not a serial) keeps ids non-guessable and
    -- decoupled from the external Firebase identity below.
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),

    -- The Firebase UID — the natural identity of an authenticated user. UNIQUE so a
    -- Firebase user maps to exactly one row; NOT NULL because every user must have one.
    firebase_uid  TEXT         NOT NULL UNIQUE,

    -- Profile fields copied from the verified Firebase token. Nullable: a token need
    -- not carry an email/display name, and this migration adds no business rules.
    email         TEXT,
    display_name  TEXT,

    -- Coarse-grained role for later RBAC (OSK-71). Stored as text (the enum name);
    -- defaults to USER so a freshly provisioned account is a normal user.
    role          TEXT         NOT NULL DEFAULT 'USER',

    -- Soft on/off switch so an account can be disabled without deletion. Enabled by default.
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Audit timestamps. DEFAULT now() covers out-of-band inserts; the entity's
    -- @PrePersist/@PreUpdate callbacks set them for rows written through the app.
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
