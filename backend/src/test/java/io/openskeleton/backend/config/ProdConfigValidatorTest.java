package io.openskeleton.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies the fail-loud prod validator (OSK-25) on BOTH paths:
 *
 * <ul>
 *   <li>the pass path — a fully, correctly configured prod environment boots cleanly;</li>
 *   <li>the fail paths — a missing var, a blank var, a missing GCP/Firebase project and a
 *       dev-default DB password each abort startup with a clear, var-naming
 *       {@link IllegalStateException};</li>
 *   <li>the prod-only guarantee — under {@code dev}/{@code test} the validator is not even
 *       created, so those profiles keep working with their local defaults (AC-3).</li>
 * </ul>
 *
 * <p>Like {@link AppPropertiesValidationTest}, this uses an {@link ApplicationContextRunner}
 * (not a full {@code @SpringBootTest}) so it is fast and focused: it registers ONLY the
 * validator, drives the active profile and the config vars as properties, and asserts on the
 * resulting context. The runner's environment overrides ambient OS env vars, so the fail
 * cases are hermetic regardless of the machine the suite runs on.
 */
class ProdConfigValidatorTest {

    // A complete, valid prod configuration. Each fail-path test starts from this and removes
    // or corrupts exactly one value, so the failure is unambiguously attributable to that var.
    private static final String PROD_PROFILE = "spring.profiles.active=prod";
    private static final String INSTANCE_CONNECTION_NAME =
            "INSTANCE_CONNECTION_NAME=openskeleton-one:europe-west2:osk-pg";
    private static final String DB_NAME = "DB_NAME=osk";
    private static final String DB_USERNAME = "DB_USERNAME=app";
    private static final String DB_PASSWORD = "DB_PASSWORD=a-real-secret-from-secret-manager";
    private static final String GOOGLE_CLOUD_PROJECT = "GOOGLE_CLOUD_PROJECT=openskeleton-one";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(ProdConfigValidator.class);

    @Test
    void bootsWhenProdConfigFullyProvided() {
        runner.withPropertyValues(
                        PROD_PROFILE, INSTANCE_CONNECTION_NAME, DB_NAME, DB_USERNAME, DB_PASSWORD, GOOGLE_CLOUD_PROJECT)
                .run(ctx -> {
                    // Valid prod: validator loads, @PostConstruct passes, context starts.
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(ProdConfigValidator.class);
                });
    }

    @Test
    void failsWhenRequiredDbVarMissing() {
        // Omit INSTANCE_CONNECTION_NAME entirely -> resolves to null -> startup aborts naming it.
        runner.withPropertyValues(PROD_PROFILE, DB_NAME, DB_USERNAME, DB_PASSWORD, GOOGLE_CLOUD_PROJECT)
                .run(ctx -> assertThatStartupFailed(ctx, "INSTANCE_CONNECTION_NAME"));
    }

    @Test
    void failsWhenRequiredDbVarBlank() {
        // DB_NAME present but blank -> also treated as missing -> startup aborts naming it.
        runner.withPropertyValues(
                        PROD_PROFILE,
                        INSTANCE_CONNECTION_NAME,
                        "DB_NAME=",
                        DB_USERNAME,
                        DB_PASSWORD,
                        GOOGLE_CLOUD_PROJECT)
                .run(ctx -> assertThatStartupFailed(ctx, "DB_NAME"));
    }

    @Test
    void failsWhenGoogleCloudProjectMissing() {
        // The GCP/Firebase project (ADC + Cloud Monitoring) has no prod default -> must be supplied.
        runner.withPropertyValues(PROD_PROFILE, INSTANCE_CONNECTION_NAME, DB_NAME, DB_USERNAME, DB_PASSWORD)
                .run(ctx -> assertThatStartupFailed(ctx, "GOOGLE_CLOUD_PROJECT"));
    }

    @Test
    void failsWhenDbPasswordIsEnvExampleDefault() {
        // Present but equal to the .env.example placeholder -> the leaked-dev-secret guard fires.
        runner.withPropertyValues(
                        PROD_PROFILE,
                        INSTANCE_CONNECTION_NAME,
                        DB_NAME,
                        DB_USERNAME,
                        "DB_PASSWORD=changeme",
                        GOOGLE_CLOUD_PROJECT)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("DB_PASSWORD")
                            .hasMessageContaining("dev/placeholder default");
                });
    }

    @Test
    void failsWhenDbPasswordIsDevProfileDefault() {
        // Present but equal to the dev-profile / dev-setup.sh value ("osk"), case-insensitively.
        runner.withPropertyValues(
                        PROD_PROFILE,
                        INSTANCE_CONNECTION_NAME,
                        DB_NAME,
                        DB_USERNAME,
                        "DB_PASSWORD=OSK",
                        GOOGLE_CLOUD_PROJECT)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("DB_PASSWORD")
                            .hasMessageContaining("dev/placeholder default");
                });
    }

    @Test
    void devProfileNeverLoadsValidatorEvenWithNoConfig() {
        // AC-3: under dev, the prod-only bean is absent, so a completely unconfigured context
        // (no DB vars at all) still starts cleanly. Dev keeps its local defaults.
        runner.withPropertyValues("spring.profiles.active=dev").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(ProdConfigValidator.class);
        });
    }

    @Test
    void testProfileNeverLoadsValidatorEvenWithNoConfig() {
        // Same guarantee for the test profile the suite itself runs under.
        runner.withPropertyValues("spring.profiles.active=test").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(ProdConfigValidator.class);
        });
    }

    /**
     * Asserts the context failed to start because of an {@link IllegalStateException} whose
     * message names the offending {@code var}.
     */
    private static void assertThatStartupFailed(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext ctx, String var) {
        assertThat(ctx).hasFailed();
        assertThat(ctx.getStartupFailure())
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(var)
                .hasMessageContaining("is missing or blank");
    }
}
