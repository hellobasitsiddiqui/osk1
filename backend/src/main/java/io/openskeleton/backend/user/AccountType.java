package io.openskeleton.backend.user;

/**
 * Whether a {@link User} row is a genuine end-user account or a test/synthetic one
 * (OSK-168).
 *
 * <p>The distinction lets later features exclude test accounts from
 * metrics/analytics/notifications and clean them up, without deleting anything now. It
 * is deliberately <b>just the classification</b>: this ticket adds the column, the enum,
 * and the {@link UserService#markAccountType(String, AccountType)} setter seam only. No
 * policy lives here — nothing auto-classifies by email; a caller (the future test-email
 * hook / admin) decides when to flag a row {@link #TEST}, so provisioning stays
 * {@link #REAL} by default.
 *
 * <p>Persisted by <i>name</i> ({@code @Enumerated(STRING)}) into the {@code account_type}
 * TEXT column added by {@code V6__add_account_type_to_users.sql}, matching how
 * {@link User.Role} is stored: the value is stable and human-readable, and adding members
 * later is backwards-safe. A CHECK constraint in the migration pins the same two values at
 * the database as a backstop.
 */
public enum AccountType {
    /** A genuine end-user account — the default for every provisioned user. */
    REAL,

    /** A synthetic/QA account (e.g. created by e2e tests), to be excluded from metrics. */
    TEST
}
