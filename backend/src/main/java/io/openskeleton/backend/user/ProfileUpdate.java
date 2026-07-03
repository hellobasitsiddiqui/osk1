package io.openskeleton.backend.user;

/**
 * The set of editable profile fields a {@code PATCH /api/v1/me} may change on the caller's
 * own {@link User} (OSK-67). It is the <b>domain-level</b> update carrier consumed by
 * {@link UserService#updateProfile(String, ProfileUpdate)}, decoupled from the API request
 * DTO so the service layer never depends on the {@code api} package (keeping the dependency
 * direction acyclic: {@code api → user → audit}, exactly as {@link ProfileChange} does).
 *
 * <p><b>Sparse (partial) semantics.</b> A {@code null} component means "the caller did not
 * ask to change this field — leave it untouched"; a non-null component is the requested new
 * value. This is what makes a partial PATCH safe: sending only {@code city} never clobbers
 * the other fields to null. (Consequently a field cannot be cleared back to {@code null}
 * through this path — a deliberate, common trade-off that avoids the absent-vs-explicit-null
 * ambiguity of JSON without a heavier {@code JsonNullable} wrapper.) The one field-level
 * validation that has happened by the time a value reaches here is done at the API boundary
 * (age range, IANA timezone, BCP-47 locale, non-blank phone); this type carries already-valid
 * values.
 *
 * @param displayName the new display name, or {@code null} to leave unchanged
 * @param firstName the new given name, or {@code null} to leave unchanged
 * @param lastName the new family name, or {@code null} to leave unchanged
 * @param city the new city, or {@code null} to leave unchanged
 * @param age the new age, or {@code null} to leave unchanged
 * @param phone the new phone, or {@code null} to leave unchanged
 * @param notificationPreference the new notification channel, or {@code null} to leave unchanged
 * @param timezone the new IANA timezone id, or {@code null} to leave unchanged
 * @param locale the new BCP-47 locale tag, or {@code null} to leave unchanged
 */
public record ProfileUpdate(
        String displayName,
        String firstName,
        String lastName,
        String city,
        Integer age,
        String phone,
        NotificationPreference notificationPreference,
        String timezone,
        String locale) {

    /**
     * Convenience factory for the common single-field case of setting only the display name
     * (the OSK-76 shape) — every other field is left unchanged. Keeps call sites that only
     * touch {@code displayName} readable without spelling out eight trailing nulls.
     *
     * @param displayName the new display name (or {@code null} to leave it unchanged)
     * @return an update that touches only {@code displayName}
     */
    public static ProfileUpdate ofDisplayName(String displayName) {
        return new ProfileUpdate(displayName, null, null, null, null, null, null, null, null);
    }
}
