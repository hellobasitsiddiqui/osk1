package io.openskeleton.backend.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers a sample custom application metric (OSK-55). Spring Boot already
 * exposes HTTP-server and JVM metrics via Micrometer/Actuator; this adds one
 * app-owned gauge ("app.info" = 1, tagged with the environment) to prove custom
 * metrics flow through the same registry — and therefore to the Cloud Monitoring
 * (Stackdriver) exporter under prod. Export itself is profile-gated (off in
 * dev/test, on in prod) in the application YAML.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public MeterBinder appInfoMetric(@Value("${app.environment-name}") String environment) {
        return registry -> Gauge.builder("app.info", () -> 1.0)
                .description("Constant 1 gauge carrying app metadata as tags.")
                .tag("environment", environment)
                .register(registry);
    }
}
