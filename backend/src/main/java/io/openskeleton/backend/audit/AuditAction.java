package io.openskeleton.backend.audit;

/**
 * The set of security-relevant actions the append-only audit log (OSK-80) records.
 *
 * <p>Each value names a discrete, auditable thing the platform does to an account.
 * The enum is persisted by <i>name</i> (see {@code AuditEvent#action}, mapped
 * {@link jakarta.persistence.EnumType#STRING}) so the stored value is stable and
 * human-readable, and adding new values later is backwards-safe (existing rows keep
 * their names).
 *
 * <p>This is a deliberately small <b>starter set</b> sized to the account/admin
 * touchpoints that Epic-2 will grow: JIT provisioning (OSK-76) records
 * {@link #USER_PROVISIONED}, and the admin endpoints (OSK-71) record
 * {@link #ROLE_CHANGED} / {@link #ACCOUNT_ENABLED} / {@link #ACCOUNT_DISABLED}. Those
 * call sites are not built yet, so today these are the vocabulary the
 * {@code AuditService.record(...)} seam is ready to accept; more can be appended as
 * new auditable actions appear.
 */
public enum AuditAction {

    /** A new user row was created (e.g. just-in-time provisioning on first sign-in). */
    USER_PROVISIONED,

    /** A user's mutable profile fields (email, display name, ...) were changed. */
    PROFILE_UPDATED,

    /** A user's authorization role was changed (e.g. USER → ADMIN). */
    ROLE_CHANGED,

    /** A previously disabled account was re-enabled. */
    ACCOUNT_ENABLED,

    /** An account was disabled (soft off-switch, no row deletion). */
    ACCOUNT_DISABLED
}
