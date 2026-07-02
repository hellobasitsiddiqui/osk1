package io.openskeleton.backend.config;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Fail-loud validator for the {@code prod} configuration contract (OSK-25).
 *
 * <p><b>Why this exists.</b> The prod profile must never boot on silent dev defaults or a
 * half-configured environment: a missing DB password or a leftover {@code changeme} would
 * otherwise surface much later as a confusing runtime failure (or, worse, a prod instance
 * talking to dev credentials). This bean turns any such gap into an immediate, explicit
 * {@link IllegalStateException} at startup that names the offending variable and points at
 * the contract doc — the same "fail fast, fail clearly" guarantee described in
 * {@code infra/secrets-and-env.md}.
 *
 * <p><b>What it does NOT duplicate.</b> Two other mechanisms already cover part of the
 * contract, so this validator deliberately complements rather than repeats them:
 * <ul>
 *   <li>{@link AppProperties} is {@code @NotBlank}-validated, so {@code APP_ENVIRONMENT_NAME}
 *       and {@code APP_PUBLIC_BASE_URL} already fail startup when blank (via the
 *       {@code ${VAR:}} empty-default trick in {@code application-prod.yml}). Not re-checked here.</li>
 *   <li>{@code application-prod.yml} binds {@code spring.datasource.password} to the bare
 *       {@code ${DB_PASSWORD}} placeholder (no default), so an <i>unset</i> password already
 *       aborts the datasource. What that mechanism CANNOT catch is a password that is present
 *       but equal to a known dev/placeholder default ({@code changeme} / {@code osk}) — which
 *       is exactly the extra guard this validator adds.</li>
 * </ul>
 *
 * <p><b>What it validates</b> (all sourced from the prod contract in
 * {@code infra/secrets-and-env.md} so the doc and the code agree):
 * <ul>
 *   <li>{@code INSTANCE_CONNECTION_NAME}, {@code DB_NAME}, {@code DB_USERNAME} — required,
 *       must be present and non-blank (the Cloud SQL socket-factory coordinates + app user).</li>
 *   <li>{@code DB_PASSWORD} — required, non-blank, AND must not be a known dev/placeholder
 *       default. This is the ONLY runtime secret (injected from Secret Manager in prod);
 *       {@code changeme} ({@code .env.example}) and {@code osk} ({@code scripts/dev-setup.sh} /
 *       the dev profile) are the two documented dev values that must never reach prod.</li>
 *   <li>{@code GOOGLE_CLOUD_PROJECT} — required, non-blank. It is the ambient GCP project id
 *       that Application Default Credentials (and therefore the Firebase Admin SDK used by
 *       {@code FirebaseTokenVerifier} for prod token verification) resolve against, and it is
 *       also the Cloud Monitoring metrics project. It has no prod default (bound as the empty
 *       {@code ${GOOGLE_CLOUD_PROJECT:}} in {@code application-prod.yml}), so a missing value
 *       must fail loudly. Note we intentionally do NOT require {@code FIREBASE_PROJECT_ID}: it
 *       has a safe hard default ({@code openskeleton-one}) and the Admin SDK authenticates via
 *       keyless ADC — inventing a hard requirement there would contradict the contract.</li>
 * </ul>
 *
 * <p><b>Prod-only by construction.</b> The bean is annotated {@code @Profile("prod")}, so it is
 * only created (and its {@code @PostConstruct} only runs) when the {@code prod} profile is
 * active. The {@code dev} and {@code test} profiles never instantiate it, so their local
 * defaults keep working untouched.
 *
 * <p>This class carries branching logic (present/blank/dev-default checks), so — unlike the
 * pure, branch-free config beans — it is NOT JaCoCo-excluded and is covered by
 * {@code ProdConfigValidatorTest} on both the pass and fail paths.
 */
@Configuration
@Profile("prod")
public class ProdConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ProdConfigValidator.class);

    /**
     * Required environment variables that must be present and non-blank in prod. These are the
     * no-safe-default entries of the contract that {@code @NotBlank} on {@link AppProperties}
     * does not already cover. {@code DB_PASSWORD} gets the additional dev-default check below.
     */
    private static final List<String> REQUIRED_VARS =
            List.of("INSTANCE_CONNECTION_NAME", "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "GOOGLE_CLOUD_PROJECT");

    /**
     * Known dev/placeholder passwords that must never be used in prod. Compared case-insensitively.
     * {@code changeme} is the {@code .env.example} placeholder; {@code osk} is the value written by
     * {@code scripts/dev-setup.sh} and used by the dev profile.
     */
    private static final Set<String> DEV_DEFAULT_DB_PASSWORDS = Set.of("changeme", "osk");

    private final Environment environment;

    public ProdConfigValidator(Environment environment) {
        this.environment = environment;
    }

    /**
     * Runs once, right after the bean is constructed, before the application finishes starting.
     * Throws {@link IllegalStateException} (which aborts startup) on the first violation found,
     * naming the offending variable so the failure is self-explanatory in the logs.
     */
    @PostConstruct
    void validateProdConfig() {
        // 1) Every required var must be present and non-blank.
        for (String var : REQUIRED_VARS) {
            String value = environment.getProperty(var);
            if (value == null || value.isBlank()) {
                throw missingOrBlank(var);
            }
        }

        // 2) The DB password, though present, must not be a leaked dev/placeholder default.
        //    getProperty is safe here: step 1 already proved DB_PASSWORD is present and non-blank.
        String dbPassword = environment.getProperty("DB_PASSWORD", "");
        if (DEV_DEFAULT_DB_PASSWORDS.contains(dbPassword.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "Prod startup aborted: DB_PASSWORD is set to a known dev/placeholder default; "
                            + "prod must use the Secret Manager value (osk-db-app-password). "
                            + "See infra/secrets-and-env.md for the prod configuration contract.");
        }

        // Note: the secret value is never logged — only the fact that validation passed.
        log.info("Prod configuration validated: required DB + GCP project variables present and non-default.");
    }

    private static IllegalStateException missingOrBlank(String var) {
        return new IllegalStateException("Prod startup aborted: required configuration '" + var
                + "' is missing or blank. See infra/secrets-and-env.md for the prod configuration contract.");
    }
}
