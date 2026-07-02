package io.openskeleton.backend.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Production {@link TokenVerifier}: the thin adapter over the Firebase Admin SDK
 * that actually validates a caller's ID token (OSK-28).
 *
 * <p><b>Credential model (no key file, ever):</b> the SDK initialises from
 * Application Default Credentials (ADC). On Cloud Run that is the runtime service
 * account, resolved automatically; locally it is whatever {@code gcloud auth
 * application-default login} (or a {@code GOOGLE_APPLICATION_CREDENTIALS}-pointed
 * file) provides. A service-account key is never committed to the repo.
 *
 * <p><b>Why initialisation is guarded:</b> most tests and a bare local run have no
 * ADC available. Rather than fail the whole application context on startup, we catch
 * the credential/init failure, log a warning, and leave the verifier "unconfigured".
 * In that state {@link #verify} fails closed (throws → the filter returns 401), so a
 * missing-credentials instance is safe-by-default: it can never accidentally treat a
 * token as valid. This is also why the real end-to-end auth tests mock the
 * {@link TokenVerifier} seam instead of exercising this class.
 *
 * <p>This adapter is deliberately excluded from the JaCoCo coverage gate (see
 * {@code pom.xml}) because it cannot be meaningfully unit-tested without live Firebase
 * credentials and a genuine signed token.
 */
@Component
public class FirebaseTokenVerifier implements TokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(FirebaseTokenVerifier.class);

    /**
     * Named (not default) FirebaseApp so we never clobber or depend on a {@code
     * [DEFAULT]} app another library might create, and so re-initialisation across
     * multiple Spring test contexts is idempotent (we look the app up by this name
     * before creating it).
     */
    private static final String APP_NAME = "openskeleton-auth";

    /**
     * The initialised auth client, or {@code null} when no credentials were available
     * at startup. {@code null} means "fail closed" — see {@link #verify}.
     */
    private final FirebaseAuth firebaseAuth;

    /**
     * @param projectId the GCP/Firebase project whose tokens we trust. Defaults to
     *     {@code openskeleton-one} (this deployment's project) and is overridable via
     *     the {@code firebase.project-id} property / env for other environments.
     */
    public FirebaseTokenVerifier(@Value("${firebase.project-id:openskeleton-one}") String projectId) {
        this.firebaseAuth = initialize(projectId);
    }

    /**
     * Verifies the ID token against Firebase. A {@code null} client (no credentials)
     * or any SDK-level failure (expired/forged/revoked token) is translated into a
     * {@link TokenVerificationException} so the filter renders a uniform 401.
     */
    @Override
    public VerifiedToken verify(String idToken) throws TokenVerificationException {
        if (firebaseAuth == null) {
            // Fail closed: without credentials we cannot trust ANY token.
            throw new TokenVerificationException("Firebase authentication is not configured on this instance.");
        }
        try {
            FirebaseToken token = firebaseAuth.verifyIdToken(idToken);
            // email is optional on a Firebase token (e.g. anonymous/phone sign-in).
            return new VerifiedToken(token.getUid(), token.getEmail());
        } catch (FirebaseAuthException e) {
            throw new TokenVerificationException("Firebase rejected the ID token.", e);
        }
    }

    /**
     * Builds (or reuses) the named FirebaseApp from ADC and returns its
     * {@link FirebaseAuth}, or {@code null} if credentials/initialisation are
     * unavailable. Never throws — a missing-credentials instance must still start.
     */
    private static FirebaseAuth initialize(String projectId) {
        try {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .setProjectId(projectId)
                    .build();

            // Idempotent across test contexts: create the named app once, then reuse it.
            FirebaseApp app;
            synchronized (FirebaseApp.class) {
                app = FirebaseApp.getApps().stream()
                        .filter(existing -> APP_NAME.equals(existing.getName()))
                        .findFirst()
                        .orElseGet(() -> FirebaseApp.initializeApp(options, APP_NAME));
            }
            log.info("Firebase Admin SDK initialised for project '{}' (app '{}').", projectId, APP_NAME);
            return FirebaseAuth.getInstance(app);
        } catch (IOException | RuntimeException e) {
            // No ADC (typical in tests / bare local run). Start anyway and fail closed.
            log.warn(
                    "Firebase Admin SDK not initialised (no Application Default Credentials?); "
                            + "/api/v1/** requests will be rejected with 401 until credentials are available: {}",
                    e.getMessage());
            return null;
        }
    }
}
