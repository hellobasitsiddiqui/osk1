package io.openskeleton.backend.email;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the Firebase Admin {@link FirebaseAuth} client as a Spring bean so
 * server-side features that must call the Admin SDK (here: email-verification
 * link minting + reading a user's authoritative {@code emailVerified} flag,
 * OSK-77) can inject it.
 *
 * <p><b>Why a NEW config class (not an edit to {@code auth/FirebaseAuthConfig}):</b>
 * that class only wires the request-time auth <i>filter</i> and is being touched by
 * parallel auth work; adding the admin client here keeps this ticket's wiring
 * self-contained and conflict-free. The Admin SDK is initialised from Application
 * Default Credentials (ADC) exactly like {@code FirebaseTokenVerifier} — never a
 * committed key file.
 *
 * <p><b>Reuses the same named {@link FirebaseApp}.</b> {@code FirebaseTokenVerifier}
 * already builds a named app ({@code "openskeleton-auth"}) from ADC for token
 * verification. We look that same app up first and only initialise it if absent, so
 * both the verify path and this admin path share ONE {@link FirebaseApp} rather than
 * creating a second, redundant one. The lookup+create is done under the same
 * {@code FirebaseApp.class} monitor the verifier uses, so a concurrent first-touch
 * from either side can never double-initialise the named app.
 *
 * <p><b>Fails open at startup, closed at use.</b> Most tests and a bare local run
 * have no ADC. Rather than fail the whole application context, the factory catches
 * the credential/init failure, logs a warning and returns {@code null} (a Spring
 * {@code NullBean}). Nothing depends on this bean at construction time —
 * {@link EmailVerificationService} resolves it lazily via {@code ObjectProvider} and
 * turns an absent client into a degraded {@code 503} only if the resend endpoint is
 * actually called — so a missing-ADC context still starts, and the endpoint can never
 * accidentally run without a real client. This is also why the class is excluded from
 * the JaCoCo gate (see {@code pom.xml}): the ADC init branch cannot be exercised
 * without live credentials, mirroring the {@code FirebaseTokenVerifier} exclusion.
 *
 * <p><b>Single admin client.</b> This is the one place a {@link FirebaseAuth} admin
 * bean is contributed today. If a future feature needs the same client it should inject
 * this bean rather than stand up a second one (which would be a duplicate-bean conflict
 * a reviewer must resolve at merge time).
 */
@Configuration
public class FirebaseAdminConfig {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAdminConfig.class);

    /**
     * The name of the shared Firebase Admin app. Deliberately the same name
     * {@code FirebaseTokenVerifier} uses so both paths reuse a single initialised
     * {@link FirebaseApp} instead of each standing up their own.
     */
    private static final String APP_NAME = "openskeleton-auth";

    /**
     * @param projectId the GCP/Firebase project whose users we administer; defaults to
     *     this deployment's {@code openskeleton-one} and is overridable via
     *     {@code firebase.project-id} (mirrors {@code FirebaseTokenVerifier})
     * @return the Admin SDK auth client, or {@code null} when ADC is unavailable (the
     *     endpoint then degrades to 503 rather than the whole app failing to start)
     */
    @Bean
    FirebaseAuth firebaseAuth(@Value("${firebase.project-id:openskeleton-one}") String projectId) {
        try {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .setProjectId(projectId)
                    .build();

            // Reuse the named app if the verifier already created it; otherwise create
            // it once. Guarded by the same monitor the verifier synchronises on so the
            // named app is initialised at most once across both call sites.
            FirebaseApp app;
            synchronized (FirebaseApp.class) {
                app = FirebaseApp.getApps().stream()
                        .filter(existing -> APP_NAME.equals(existing.getName()))
                        .findFirst()
                        .orElseGet(() -> FirebaseApp.initializeApp(options, APP_NAME));
            }
            log.info("Firebase Admin auth client ready for project '{}' (app '{}').", projectId, APP_NAME);
            return FirebaseAuth.getInstance(app);
        } catch (IOException | RuntimeException e) {
            // No ADC (typical in tests / bare local run). Start anyway; the resend
            // endpoint degrades to 503 until credentials are available.
            log.warn(
                    "Firebase Admin auth client not initialised (no Application Default Credentials?); "
                            + "email-verification resend will return 503 until credentials are available: {}",
                    e.getMessage());
            return null;
        }
    }
}
