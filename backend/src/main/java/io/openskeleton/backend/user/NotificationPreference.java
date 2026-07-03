package io.openskeleton.backend.user;

/**
 * The channel the platform should use to reach a {@link User} (OSK-67).
 *
 * <p>Part of the richer self-service profile: a caller sets this via
 * {@code PATCH /api/v1/me}. It is deliberately a tiny, closed set of delivery channels —
 * the notification-sending features (e.g. OSK-155 push) consult it to decide whether/how
 * to contact a user; no policy lives here, only the classification.
 *
 * <p>Persisted by <i>name</i> ({@code @Enumerated(STRING)}) into the
 * {@code notification_preference} TEXT column added by
 * {@code V7__add_profile_fields_to_users.sql}, mirroring how {@link User.Role} and
 * {@link AccountType} are stored: the value is stable and human-readable, and adding
 * members later is backwards-safe. Every user has one — the column is
 * {@code NOT NULL DEFAULT 'EMAIL'} and the entity field defaults to {@link #EMAIL} — and a
 * CHECK constraint in the migration pins the same three values at the database as a
 * backstop.
 */
public enum NotificationPreference {
    /** Reach the user by email — the default for every user unless they opt into another. */
    EMAIL,

    /** Reach the user by push notification to their registered devices (OSK-154/OSK-155). */
    PUSH,

    /** Do not send this user notifications on any channel — they have opted out. */
    NONE
}
