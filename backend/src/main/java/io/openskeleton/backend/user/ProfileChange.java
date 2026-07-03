package io.openskeleton.backend.user;

import io.openskeleton.backend.audit.AuditAction;
import io.openskeleton.backend.audit.AuditEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry in a caller's profile-change history (OSK-99): a single field edit —
 * {@code field} name, {@code oldValue} → {@code newValue}, who made it ({@code actor})
 * and when ({@code changedAt}). This is both the JSON wire shape returned by
 * {@code GET /api/v1/me/history} and the <b>single source of truth</b> for how a
 * per-field edit is encoded into (and decoded out of) an {@code audit_events} row.
 *
 * <p><b>Why reuse {@code audit_events} instead of a dedicated table.</b> A profile edit
 * is exactly the kind of "who did what to whom, when" fact the append-only audit log
 * (OSK-80) already models, and "this user's profile changes" is a clean query over it:
 * filter by {@code actor_firebase_uid = <caller>} and {@code action = PROFILE_UPDATED},
 * newest-first (both columns are already indexed — see {@code V4__create_audit_events.sql}).
 * So OSK-99 adds no {@code V6} migration and no second history table; it records through
 * the existing {@link io.openskeleton.backend.audit.AuditService#record} seam and reads
 * back through {@link io.openskeleton.backend.audit.AuditService#findByActorAndAction}.
 * The per-field detail ({@code field}/{@code old}/{@code new}) rides in the event's JSONB
 * {@code metadata} map, whose key contract lives here so the write side ({@link UserService})
 * and the read side ({@code MeController}) can never drift apart.
 *
 * <p><b>Why this type lives in the {@code user} package, not {@code api}.</b> It is shared
 * by the writer ({@link UserService}, in {@code user}) and the reader ({@code MeController},
 * in {@code api}). Placing it in {@code user} keeps the dependency direction acyclic
 * ({@code api → user → audit}); nesting it inside {@code MeController} (as {@code CurrentUser}
 * is) would force {@code user → api} and create a package cycle.
 *
 * <p>A {@code record} is used (plain Java, no Lombok): the entry is an immutable value and
 * Jackson serialises a record's components as JSON fields out of the box. Fields carrying a
 * {@code null} ({@code oldValue} on the first time a field is set) are simply serialised as
 * absent/null, matching how {@code /api/v1/me} renders a null {@code displayName}.
 *
 * @param field the name of the profile field that changed (today only {@code displayName})
 * @param oldValue the value before the edit, or {@code null} if the field had no value
 * @param newValue the value after the edit, or {@code null} if the field was cleared
 * @param actor the Firebase UID of the caller who made the change
 * @param changedAt when the change was recorded (the audit event's creation instant)
 */
public record ProfileChange(String field, String oldValue, String newValue, String actor, Instant changedAt) {

    /**
     * {@code target_type} stamped on the {@code audit_events} row for a profile change:
     * the change targets the caller's own {@code users} row, so the coarse target is
     * simply {@code "user"} (the {@code target_id} carries the specific user id).
     */
    public static final String TARGET_TYPE = "user";

    /** The only field editable via {@code PATCH /api/v1/me} today (OSK-76). */
    public static final String FIELD_DISPLAY_NAME = "displayName";

    // JSONB metadata keys. Kept private + centralised here so the writer and reader use
    // the exact same strings — a magic-string mismatch would silently produce history
    // entries with null field/old/new, which no compiler would catch.
    private static final String META_FIELD = "field";
    private static final String META_OLD = "old";
    private static final String META_NEW = "new";

    /**
     * Build the {@code audit_events.metadata} map for a single field edit. Used by the
     * write path ({@link UserService#updateProfile}) so the encoding lives next to the
     * decoding ({@link #from(AuditEvent)}).
     *
     * <p>A {@link LinkedHashMap} (not {@link Map#of}) is deliberate on two counts: it
     * preserves a stable {@code field, old, new} key order in the stored JSON (nicer to
     * read in the DB), and — unlike {@code Map.of} — it tolerates {@code null} values,
     * which occur whenever a field is first set (old is null) or cleared (new is null).
     *
     * @param field the changed field's name
     * @param oldValue the value before the change (may be {@code null})
     * @param newValue the value after the change (may be {@code null})
     * @return a mutable metadata map ready to hand to {@code AuditService.record(...)}
     */
    public static Map<String, Object> metadata(String field, String oldValue, String newValue) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(META_FIELD, field);
        metadata.put(META_OLD, oldValue);
        metadata.put(META_NEW, newValue);
        return metadata;
    }

    /**
     * Project a persisted {@link AuditEvent} back onto a history entry. The action is not
     * re-checked here (callers query {@code action = PROFILE_UPDATED}); this only unpacks
     * the metadata written by {@link #metadata(String, String, String)} plus the event's
     * actor and timestamp.
     *
     * <p>Values are read defensively: a {@code null} metadata map (an event stored without
     * detail) yields {@code null} field/old/new rather than throwing, and each value is
     * coerced to a {@code String} so a JSONB round-trip that reads back a non-string type
     * still maps cleanly.
     *
     * @param event the source audit event (a {@code PROFILE_UPDATED} row); must not be null
     * @return the corresponding immutable history entry
     */
    public static ProfileChange from(AuditEvent event) {
        Map<String, Object> metadata = event.getMetadata() == null ? Map.of() : event.getMetadata();
        return new ProfileChange(
                asString(metadata.get(META_FIELD)),
                asString(metadata.get(META_OLD)),
                asString(metadata.get(META_NEW)),
                event.getActorFirebaseUid(),
                event.getCreatedAt());
    }

    /** Null-safe coercion to String — a null metadata value stays null (not the text "null"). */
    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * The audit action under which profile-field changes are recorded and queried. A tiny
     * convenience so call sites read {@code ProfileChange.ACTION} rather than reaching into
     * the {@link AuditAction} enum, keeping the "profile history == this action" fact in one
     * place alongside the rest of the encoding contract.
     */
    public static final AuditAction ACTION = AuditAction.PROFILE_UPDATED;
}
