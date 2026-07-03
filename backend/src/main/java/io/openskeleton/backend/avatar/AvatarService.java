package io.openskeleton.backend.avatar;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Applies a user's newly-uploaded avatar as their Firebase {@code photoURL} via the Admin SDK
 * (OSK-83).
 *
 * <p><b>Where the avatar bytes live vs. what this does.</b> The image itself is uploaded
 * <i>client-side</i> straight to Cloud Storage under the caller's own {@code users/{uid}/…} prefix
 * (the Firebase Storage Web SDK, authorised by the ID token against {@code storage.rules}) — the
 * bytes never transit this backend. This service is the thin, authoritative "confirm + reflect" step:
 * it re-validates the uploaded object reference server-side ({@link AvatarObject}) and, when valid,
 * sets the Firebase user's {@code photoURL} to the object's download URL. Because {@code GET /me}
 * already reads {@code photoUrl} LIVE from Firebase (OSK-73, via {@code FirebaseAccountService}), the
 * new avatar surfaces on {@code /me} with <b>no schema change</b> — no stored column, no second source
 * of truth to drift from Firebase.
 *
 * <p><b>Reuses the existing Admin client.</b> Like {@code FirebaseAccountService} (OSK-73) and
 * {@code EmailVerificationService} (OSK-77), the Admin {@link FirebaseAuth} bean contributed once by
 * {@code email.FirebaseAdminConfig} is consumed here through an {@link ObjectProvider} — so an ABSENT
 * client (no ADC in tests / local runs, or Storage/Firebase not yet enabled — OSK-95) resolves to
 * {@code null} instead of failing context startup, and the endpoint degrades to a 503 only when it is
 * actually called.
 *
 * <p><b>Fail-closed on a write.</b> Unlike the read-only account-state lookup (which degrades to
 * empty), setting the avatar is a mutation: if the Admin client is missing or the live
 * {@code updateUser} call fails, we raise {@link AvatarUnavailableException} (→ 503) rather than
 * silently pretending success, so the client knows the avatar was NOT applied and can retry.
 */
@Service
public class AvatarService {

    private static final Logger log = LoggerFactory.getLogger(AvatarService.class);

    /**
     * Lazy handle to the shared Admin-SDK auth client (see {@code FirebaseAccountService} for the same
     * pattern). {@link ObjectProvider#getIfAvailable()} yields {@code null} when no client is
     * configured, which we turn into a 503 at call time rather than a startup failure.
     */
    private final ObjectProvider<FirebaseAuth> firebaseAuthProvider;

    /**
     * The expected Storage bucket the download URL must reference, so a client can't hand us a URL
     * pointing at some other bucket. Defaults to this deployment's bucket and is overridable via
     * {@code firebase.storage-bucket} (mirrors {@code FirebaseAdminConfig}'s {@code firebase.project-id}).
     */
    private final String bucket;

    public AvatarService(
            ObjectProvider<FirebaseAuth> firebaseAuthProvider,
            @Value("${firebase.storage-bucket:openskeleton-one.firebasestorage.app}") String bucket) {
        this.firebaseAuthProvider = firebaseAuthProvider;
        this.bucket = bucket;
    }

    /**
     * Validate the uploaded object reference and, when valid, set the caller's Firebase
     * {@code photoURL} to its download URL.
     *
     * @param uid the verified caller uid (from the auth filter)
     * @param objectPath the storage object path the client uploaded to
     * @param downloadUrl the tokenised download URL from {@code getDownloadURL()}
     * @return the applied {@code photoURL} (the stripped download URL)
     * @throws InvalidAvatarException if the object reference fails validation (mapped to 400)
     * @throws AvatarUnavailableException if the Admin client is missing or the Firebase call fails
     *     (mapped to 503)
     */
    public String updateAvatar(String uid, String objectPath, String downloadUrl) {
        // Defence-in-depth ownership/type/URL validation BEFORE touching Firebase (throws 400).
        AvatarObject.validate(uid, objectPath, downloadUrl, bucket);

        FirebaseAuth auth = firebaseAuthProvider.getIfAvailable();
        if (auth == null) {
            // No Admin client on this instance (no ADC / Storage not enabled yet — OSK-95). A write
            // must fail loudly, not pretend success, so the client can retry once Firebase is up.
            throw new AvatarUnavailableException("Firebase Admin client is not configured; cannot update the avatar.");
        }

        String photoUrl = downloadUrl.strip();
        try {
            // Firebase owns the user's photoURL; set it authoritatively via the Admin SDK so the
            // change is reflected the moment /me next reads the live Firebase record (OSK-73).
            auth.updateUser(new UserRecord.UpdateRequest(uid).setPhotoUrl(photoUrl));
        } catch (FirebaseAuthException | RuntimeException e) {
            // A live Firebase failure (network / permission / unexpected SDK error) on a write —
            // degrade to 503 like the email-verification resend rather than leaking a 500.
            throw new AvatarUnavailableException("Failed to update the Firebase user photo URL.", e);
        }
        log.info("Updated avatar photoURL for uid '{}'.", uid);
        return photoUrl;
    }
}
