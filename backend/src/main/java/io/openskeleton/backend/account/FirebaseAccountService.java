package io.openskeleton.backend.account;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Reads the <b>Firebase-owned, read-only</b> account state ({@link FirebaseAccountState}) for a
 * caller, live per request, via the Firebase Admin SDK (OSK-73).
 *
 * <p><b>Reuses the existing admin client — does not stand up its own.</b> The Admin SDK
 * {@link FirebaseAuth} bean is contributed once by {@code email.FirebaseAdminConfig} (OSK-77), which
 * builds it from Application Default Credentials on the shared {@code "openskeleton-auth"}
 * {@link com.google.firebase.FirebaseApp}. Per that config's own guidance ("if a future feature needs
 * the same client it should inject this bean rather than stand up a second one"), we consume that bean
 * here rather than create a duplicate. It is resolved through an {@link ObjectProvider} — exactly as
 * {@code EmailVerificationService} consumes it — so an <i>absent</i> client (no ADC in tests / local
 * runs, or Firebase not configured) resolves to {@code null} instead of failing context startup.
 *
 * <p><b>Fail-soft, never fail the request.</b> This state is decoration on top of the persisted
 * profile, not identity, so a Firebase hiccup must not turn a working {@code GET /me} into an error.
 * Two degradation paths both return {@link FirebaseAccountState#empty()} (all {@code null}):
 * <ul>
 *   <li>no admin client available ({@link ObjectProvider#getIfAvailable()} is {@code null}) — nothing
 *       to query;</li>
 *   <li>the live {@link FirebaseAuth#getUser(String)} lookup throws — a {@link FirebaseAuthException}
 *       (e.g. the uid has no Firebase record — anonymous sign-in) or any other SDK/runtime error is
 *       caught and logged at debug, not propagated.</li>
 * </ul>
 * So the controller can always call {@link #stateFor(String)} and simply map whatever it gets.
 */
@Service
public class FirebaseAccountService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAccountService.class);

    /**
     * Lazy handle to the shared Admin-SDK auth client. An {@link ObjectProvider} (not a direct
     * injection) so a missing bean — no ADC / Firebase not configured — resolves to {@code null} via
     * {@link ObjectProvider#getIfAvailable()} rather than breaking startup; tests pass a provider that
     * yields a Mockito mock or {@code null}.
     */
    private final ObjectProvider<FirebaseAuth> firebaseAuthProvider;

    public FirebaseAccountService(ObjectProvider<FirebaseAuth> firebaseAuthProvider) {
        this.firebaseAuthProvider = firebaseAuthProvider;
    }

    /**
     * Fetch the live Firebase-owned account state for {@code firebaseUid}, or the degraded
     * all-{@code null} {@link FirebaseAccountState#empty()} when Firebase is unavailable or the lookup
     * fails. Never throws.
     *
     * @param firebaseUid the verified Firebase uid of the caller (never {@code null} on the
     *     authenticated {@code /me} path)
     * @return the projected Firebase account state, or {@link FirebaseAccountState#empty()}
     */
    public FirebaseAccountState stateFor(String firebaseUid) {
        FirebaseAuth firebaseAuth = firebaseAuthProvider.getIfAvailable();
        if (firebaseAuth == null) {
            // No Admin SDK client on this instance (no ADC / not configured) — degrade to nulls.
            return FirebaseAccountState.empty();
        }
        try {
            UserRecord record = firebaseAuth.getUser(firebaseUid);
            return FirebaseAccountState.from(record);
        } catch (FirebaseAuthException | RuntimeException e) {
            // The uid may have no Firebase record (e.g. anonymous sign-in) or the SDK/network may be
            // misbehaving. This state is optional decoration, so log quietly and degrade rather than
            // failing an otherwise-valid /me. Debug level: this can be attacker-influenced and is not
            // actionable per-request.
            log.debug(
                    "Firebase account lookup failed for uid '{}'; surfacing empty state: {}",
                    firebaseUid,
                    e.getMessage());
            return FirebaseAccountState.empty();
        }
    }
}
