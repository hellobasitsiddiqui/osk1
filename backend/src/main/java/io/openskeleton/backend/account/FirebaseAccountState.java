package io.openskeleton.backend.account;

import com.google.firebase.auth.UserRecord;
import java.time.Instant;
import org.springframework.lang.Nullable;

/**
 * The <b>Firebase-owned, read-only</b> slice of a user's account state that {@code
 * GET /api/v1/me} surfaces (OSK-73).
 *
 * <p><b>Why this is separate from {@code User}.</b> None of these fields are persisted in
 * this backend's {@code users} table — they live in Firebase Authentication and are the
 * authoritative record there (a user verifies their email, enrols MFA, or links a phone
 * <i>in Firebase</i>, not through our API). So we read them <i>live</i> per request via the
 * Admin SDK ({@code FirebaseAuth.getUser(uid)} → {@link UserRecord}) rather than storing a
 * copy that would immediately drift. This record is the immutable projection of that live
 * lookup onto exactly the fields the client needs, keeping the Firebase SDK types out of the
 * controller/DTO layer.
 *
 * <p><b>Every field is nullable and degrades to {@code null}.</b> The lookup is best-effort:
 * when Firebase Admin is unavailable (no ADC in tests / local runs, a transient SDK error, or
 * an anonymous caller with no Firebase record) the whole state collapses to {@link #empty()}
 * — all {@code null} — so /me still answers with the persisted profile and simply omits these
 * fields. Callers therefore must treat each as optional.
 *
 * @param emailVerified whether Firebase considers the caller's email verified, or {@code null}
 *     if unknown/unavailable
 * @param mfaEnabled whether the caller has any enrolled multi-factor second factors. <b>Always
 *     {@code null} on firebase-admin 9.9.0</b>: this SDK version's {@link UserRecord} exposes no
 *     MFA/second-factor accessor at all (verified against the jar), so enrolment cannot be read.
 *     The field is kept in the contract so it "lights up" once the SDK/version exposes MFA,
 *     without another API change — see {@link #from(UserRecord)}.
 * @param phoneVerified whether the caller has a verified phone on their Firebase account —
 *     derived from {@code phoneNumber} being present, since Firebase only stores a phone number
 *     once it has been verified (so a non-null number == verified). {@code null} when unavailable
 * @param phoneNumber the caller's verified E.164 phone number, or {@code null} if none/unavailable
 * @param photoUrl the caller's Firebase profile photo URL, or {@code null} if none/unavailable
 * @param lastLoginAt when Firebase last saw the caller <i>sign in</i> (from Firebase user
 *     metadata), or {@code null} if never/unavailable. Distinct from the backend's own
 *     {@code lastActiveAt} heartbeat: this only advances on a fresh Firebase sign-in.
 */
public record FirebaseAccountState(
        @Nullable Boolean emailVerified,
        @Nullable Boolean mfaEnabled,
        @Nullable Boolean phoneVerified,
        @Nullable String phoneNumber,
        @Nullable String photoUrl,
        @Nullable Instant lastLoginAt) {

    /** The all-{@code null} state used whenever the Firebase lookup can't or shouldn't run. */
    private static final FirebaseAccountState EMPTY = new FirebaseAccountState(null, null, null, null, null, null);

    /**
     * The degraded, all-{@code null} state. Returned by {@link FirebaseAccountService} whenever
     * Firebase Admin is unavailable or the lookup fails, so /me never crashes on a missing
     * dependency and simply omits these fields.
     */
    public static FirebaseAccountState empty() {
        return EMPTY;
    }

    /**
     * Project a live Firebase {@link UserRecord} onto the fields /me exposes.
     *
     * <ul>
     *   <li>{@code emailVerified} ← {@link UserRecord#isEmailVerified()}.</li>
     *   <li>{@code phoneNumber} ← {@link UserRecord#getPhoneNumber()} (E.164, or {@code null}).</li>
     *   <li>{@code phoneVerified} ← whether that number is present: Firebase only records a phone
     *       number after it has been verified, so presence == verified.</li>
     *   <li>{@code photoUrl} ← {@link UserRecord#getPhotoUrl()}.</li>
     *   <li>{@code lastLoginAt} ← the last-sign-in epoch-millis in the user metadata, converted to
     *       an {@link Instant}; {@code 0}/absent metadata means "never signed in" → {@code null}.</li>
     *   <li>{@code mfaEnabled} ← {@code null}: unreadable on firebase-admin 9.9.0 (see the record
     *       javadoc). Isolated to this one line so upgrading the SDK is a localised change.</li>
     * </ul>
     *
     * @param record the live Firebase user record (never {@code null} — the caller only maps a
     *     successful lookup)
     * @return the projected, immutable account state
     */
    public static FirebaseAccountState from(UserRecord record) {
        String phoneNumber = record.getPhoneNumber();
        boolean phoneVerified = phoneNumber != null && !phoneNumber.isBlank();
        return new FirebaseAccountState(
                record.isEmailVerified(),
                // MFA enrolment is not exposed by firebase-admin 9.9.0's UserRecord — surface null.
                null,
                phoneVerified,
                phoneNumber,
                record.getPhotoUrl(),
                lastLoginAt(record));
    }

    /**
     * Convert the Firebase last-sign-in timestamp (epoch millis) to an {@link Instant}, treating a
     * non-positive value (or absent metadata) as "never signed in" → {@code null}.
     */
    @Nullable private static Instant lastLoginAt(UserRecord record) {
        if (record.getUserMetadata() == null) {
            return null;
        }
        long lastSignInMillis = record.getUserMetadata().getLastSignInTimestamp();
        return lastSignInMillis > 0 ? Instant.ofEpochMilli(lastSignInMillis) : null;
    }
}
