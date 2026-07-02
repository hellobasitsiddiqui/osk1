package io.openskeleton.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies Micrometer Tracing is wired: a {@link Tracer} bean exists, which is
 * what populates trace.id/span.id into the logging MDC on each request (OSK-45).
 * The JSON-vs-plain format switch is profile config (ECS on prod) and is checked
 * by a runtime smoke rather than a unit test.
 */
@SpringBootTest
class TracingWiringTest {

    @Autowired
    private Tracer tracer;

    @Test
    void tracerBeanIsPresent() {
        assertThat(tracer).isNotNull();
    }
}
