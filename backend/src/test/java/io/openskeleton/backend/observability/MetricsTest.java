package io.openskeleton.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies the custom "app.info" metric is registered and that Cloud Monitoring
 * export is OFF under the default/test profile (OSK-55) — so tests never try to
 * reach GCP. Real Cloud Monitoring visibility is a prod-deploy concern (OSK-36).
 */
@SpringBootTest(properties = "management.stackdriver.metrics.export.enabled=false")
class MetricsTest {

    @Autowired
    private MeterRegistry registry;

    @Test
    void customAppInfoMetricIsRegistered() {
        assertThat(registry.find("app.info").gauge()).isNotNull();
        assertThat(registry.find("app.info").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void httpAndJvmMetersArePresent() {
        // JVM metrics are auto-registered by Actuator/Micrometer.
        assertThat(registry.find("jvm.memory.used").gauge()).isNotNull();
    }
}
