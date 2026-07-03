package io.openskeleton.backend.push;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.lang.Nullable;

/**
 * Exposes the Firebase Cloud Messaging client as a Spring bean for the send-push service
 * (OSK-155), <b>reusing the {@link FirebaseApp} the auth layer already initialised</b>
 * (OSK-28) rather than creating a second one.
 *
 * <p><b>Why we do not initialise (or re-initialise) a FirebaseApp here.</b> The Firebase
 * Admin SDK keeps a process-wide registry of named {@code FirebaseApp} instances. The auth
 * layer ({@code FirebaseTokenVerifier}) already builds one from Application Default
 * Credentials (ADC) — a single ADC-backed app is all the project needs, and initialising a
 * second one would duplicate credentials and risk clobbering the registry. So this config
 * only <i>looks up</i> the existing app and asks it for a {@link FirebaseMessaging} client
 * via {@link FirebaseMessaging#getInstance(FirebaseApp)}. It deliberately does <b>not</b>
 * touch {@code FirebaseAuthConfig} / {@code FirebaseTokenVerifier} (keeps this change
 * conflict-free with parallel auth work).
 *
 * <p><b>Ordering ({@code @DependsOn}).</b> The auth app is created inside the
 * {@code firebaseTokenVerifier} bean's constructor, so that bean must be fully constructed
 * before we look the app up. {@code @DependsOn("firebaseTokenVerifier")} forces that order
 * without a compile-time coupling to the auth package's internals. (User
 * {@code @Configuration} classes like this one are excluded from Spring Boot's sliced web
 * tests, so the dependency is only ever resolved in the full application context where the
 * auth component is present.)
 *
 * <p><b>Graceful degradation (OSK-165 is a human step).</b> Enabling the Firebase Cloud
 * Messaging API on the GCP project is a manual, out-of-band action. Until it is done — and
 * in every environment/test with no ADC — no app is initialised, so this bean resolves to
 * {@code null}. That is intentional: {@link PushNotificationService} treats a {@code null}
 * messaging client as "push disabled" and no-ops (logs, never throws), so non-FCM
 * environments and unit tests start and run cleanly. The bean method therefore never
 * throws and never fabricates a fake client.
 *
 * <p>This class is pure SDK-lookup plumbing that cannot be meaningfully unit-tested without
 * live Firebase credentials, so — like {@code FirebaseTokenVerifier} — it is excluded from
 * the JaCoCo coverage gate in {@code pom.xml}. The branching logic that <i>is</i> testable
 * lives in {@link PushNotificationService}, exercised with a mocked {@link FirebaseMessaging}.
 */
@Configuration
public class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    /**
     * The name of the {@link FirebaseApp} the auth layer initialises
     * ({@code FirebaseTokenVerifier.APP_NAME}). Duplicated here as a literal — rather than
     * referencing a shared constant — precisely so this config does not have to edit or
     * depend on the auth class's source (avoids a merge conflict with parallel auth work).
     * If the auth layer ever renames its app, the lookup below falls back to "the single
     * initialised app", so reuse still works.
     */
    private static final String AUTH_APP_NAME = "openskeleton-auth";

    /**
     * The Firebase Cloud Messaging client, or {@code null} when no {@link FirebaseApp} has
     * been initialised (no ADC / FCM not yet enabled — OSK-165). Declared to return a
     * {@link Nullable} value so Spring registers the absence as an injectable {@code null}
     * that {@link PushNotificationService}'s {@code @Nullable} constructor parameter picks
     * up, driving its no-op degradation path.
     *
     * @return the messaging client bound to the reused auth app, or {@code null} if none
     *     exists
     */
    @Bean
    @Nullable @DependsOn("firebaseTokenVerifier")
    FirebaseMessaging firebaseMessaging() {
        FirebaseApp app = resolveFirebaseApp();
        if (app == null) {
            log.warn("No FirebaseApp is initialised (no ADC / Firebase Cloud Messaging not enabled — "
                    + "OSK-165); push notifications are DISABLED and PushNotificationService will no-op.");
            return null;
        }
        log.info("Firebase Cloud Messaging client bound to app '{}'; push notifications enabled.", app.getName());
        return FirebaseMessaging.getInstance(app);
    }

    /**
     * Locate the {@link FirebaseApp} to reuse: prefer the auth layer's named app, and fall
     * back to the sole initialised app if the name ever changes. Returns {@code null} when
     * the SDK registry is empty (nothing to reuse).
     */
    private static FirebaseApp resolveFirebaseApp() {
        List<FirebaseApp> apps = FirebaseApp.getApps();
        if (apps.isEmpty()) {
            return null;
        }
        return apps.stream()
                .filter(app -> AUTH_APP_NAME.equals(app.getName()))
                .findFirst()
                .orElseGet(() -> apps.size() == 1 ? apps.get(0) : null);
    }
}
