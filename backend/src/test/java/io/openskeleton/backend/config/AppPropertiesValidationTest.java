package io.openskeleton.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies {@link AppProperties} binds valid config and FAILS FAST on invalid
 * config, using {@link ApplicationContextRunner} (no full Spring Boot context) so
 * the test is fast and focused on the config-validation contract.
 */
class AppPropertiesValidationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @Test
    void bindsWhenConfigValid() {
        runner.withPropertyValues("app.environment-name=dev", "app.public-base-url=http://localhost:8080")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AppProperties.class).getEnvironmentName()).isEqualTo("dev");
                });
    }

    @Test
    void failsStartupWhenRequiredConfigBlank() {
        // Blank environment-name violates @NotBlank -> binding validation fails -> context fails to start.
        runner.withPropertyValues("app.environment-name=", "app.public-base-url=http://localhost")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }
}
