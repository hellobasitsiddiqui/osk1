package io.openskeleton.backend.email;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Mints and dispatches an email-verification link for the authenticated caller, and
 * reports their current {@code emailVerified} state (OSK-77).
 *
 * <p><b>Where the truth comes from:</b> identity is the verified Firebase uid the auth
 * filter published; everything else (the caller's email and whether it is already
 * verified) is read authoritatively from the Firebase user record via
 * {@link FirebaseAuth#getUser(String)} — never from the request. The link itself is
 * minted by {@link FirebaseAuth#generateEmailVerificationLink(String)} (Firebase owns
 * verification) and handed to the pluggable {@link EmailVerificationSender} for
 * delivery (a logging stub today; a real SMTP sender once OSK-139 lands).
 *
 * <p><b>Flow / short-circuits</b> (in order):
 * <ol>
 *   <li>No Admin client configured (no ADC) -> {@link EmailVerificationUnavailableException}
 *       (503). Resolved lazily via {@code ObjectProvider} so a no-ADC context still starts.</li>
 *   <li>Already verified -> return {@code emailVerified=true}, send nothing (idempotent;
 *       the cooldown is not even consumed — there is nothing to throttle).</li>
 *   <li>No email on the account -> {@link EmailNotVerifiableException} (422).</li>
 *   <li>Inside the per-user cooldown window -> {@link ResendCooldownException} (429).</li>
 *   <li>Otherwise mint + dispatch and return {@code emailVerified=false},
 *       {@code verificationSent=true}.</li>
 * </ol>
 * The cooldown is checked <i>after</i> the already-verified/no-email guards so those
 * cheap, idempotent outcomes are never rate-limited, and <i>before</i> the mint so a
 * rapid repeat does not incur a Firebase link-generation round-trip.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private final ObjectProvider<FirebaseAuth> firebaseAuthProvider;
    private final EmailVerificationSender sender;
    private final ResendCooldown cooldown;

    /**
     * @param firebaseAuthProvider lazy handle to the Admin client — {@code null} when
     *     no ADC is configured (see {@link FirebaseAdminConfig}); resolved per call so
     *     an absent client degrades to 503 only when the endpoint is actually used
     * @param sender the dispatch seam (logging stub until OSK-139 wires SMTP)
     * @param properties env-bound settings; supplies the per-user resend cooldown
     */
    public EmailVerificationService(
            ObjectProvider<FirebaseAuth> firebaseAuthProvider,
            EmailVerificationSender sender,
            EmailVerificationProperties properties) {
        this.firebaseAuthProvider = firebaseAuthProvider;
        this.sender = sender;
        this.cooldown = new ResendCooldown(properties.getResendCooldown(), Clock.systemUTC());
    }

    /**
     * Resends (mints + dispatches) the caller's verification link if appropriate, and
     * always reports their current verified state.
     *
     * @param uid the verified Firebase uid of the caller (from the auth filter)
     * @return the outcome: the target email, the current {@code emailVerified} flag, and
     *     whether a link was dispatched on this call
     * @throws EmailVerificationUnavailableException if the Admin client is missing or a
     *     Firebase call fails (mapped to 503)
     * @throws EmailNotVerifiableException if the account has no email to verify (mapped to 422)
     * @throws ResendCooldownException if a resend was requested inside the cooldown window
     *     (mapped to 429)
     */
    public ResendResult resend(String uid) {
        FirebaseAuth auth = firebaseAuthProvider.getIfAvailable();
        if (auth == null) {
            throw new EmailVerificationUnavailableException(
                    "Firebase Admin client is not configured; cannot mint a verification link.");
        }

        UserRecord user = lookUp(auth, uid);
        String email = user.getEmail();

        // Already verified: nothing to send. Report the state and return — idempotent,
        // and deliberately not subject to the cooldown (no email is dispatched).
        if (user.isEmailVerified()) {
            return new ResendResult(email, true, false);
        }

        // Unverified but no address to send to (anonymous/phone-only sign-in): this is a
        // property of the request, not a transient fault -> 422.
        if (email == null || email.isBlank()) {
            throw new EmailNotVerifiableException("Account has no email address to verify.");
        }

        // Throttle per user: a rapid repeat is rejected with a Retry-After (429).
        ResendCooldown.Decision decision = cooldown.check(uid);
        if (!decision.allowed()) {
            throw new ResendCooldownException(decision.retryAfterSeconds());
        }

        // Mint the link (Firebase owns verification) and hand it to the dispatch seam.
        String link = mintLink(auth, email);
        sender.send(email, link);
        log.info("Dispatched email-verification resend for uid '{}'.", uid);
        return new ResendResult(email, false, true);
    }

    /** Read the authoritative user record; a Firebase failure degrades to 503. */
    private static UserRecord lookUp(FirebaseAuth auth, String uid) {
        try {
            return auth.getUser(uid);
        } catch (FirebaseAuthException e) {
            throw new EmailVerificationUnavailableException(
                    "Failed to read the Firebase user record for the caller.", e);
        }
    }

    /** Mint the verification link; a Firebase failure degrades to 503. */
    private static String mintLink(FirebaseAuth auth, String email) {
        try {
            return auth.generateEmailVerificationLink(email);
        } catch (FirebaseAuthException e) {
            throw new EmailVerificationUnavailableException("Failed to mint a verification link.", e);
        }
    }

    /**
     * Outcome of a {@link #resend} call — the small, tightly-scoped payload OSK-77
     * returns (deliberately NOT the full {@code /me} account state, which is OSK-73).
     *
     * @param email the caller's email (may be {@code null} only in the already-verified
     *     path if Firebase reports none)
     * @param emailVerified the caller's current verified state, read from Firebase
     * @param verificationSent whether a verification link was minted + dispatched on this call
     */
    public record ResendResult(String email, boolean emailVerified, boolean verificationSent) {}
}
