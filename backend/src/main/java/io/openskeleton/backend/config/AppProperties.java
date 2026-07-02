package io.openskeleton.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Strongly-typed, validated application configuration bound from the {@code app.*}
 * namespace. Registered by {@code @ConfigurationPropertiesScan} on
 * {@link io.openskeleton.backend.BackendApplication}.
 *
 * <p>Bean Validation ({@link NotBlank} ...) runs at startup, so missing or blank
 * config fails fast with a clear message instead of surfacing later as a
 * {@code NullPointerException}. The {@code prod} profile supplies these from the
 * environment with no defaults, so a prod boot without the required env vars
 * fails immediately — by design (see {@code application-prod.yml}).
 */
@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** Human-readable name of the running environment (e.g. local/dev/test/prod). */
    @NotBlank
    private String environmentName;

    /** Public base URL the app is reached at; used for absolute links/CORS later. */
    @NotBlank
    private String publicBaseUrl;

    public String getEnvironmentName() {
        return environmentName;
    }

    public void setEnvironmentName(String environmentName) {
        this.environmentName = environmentName;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }
}
