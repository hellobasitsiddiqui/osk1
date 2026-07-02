package io.openskeleton.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the full Spring application context starts without error.
 *
 * <p>Cheap but high-value — it catches misconfigured beans, bad properties and
 * classpath problems at build time, so a broken wiring can never merge green.
 */
@SpringBootTest
class BackendApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: success == the context above bootstrapped cleanly.
    }
}
