package io.openskeleton.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the OpenSkeleton backend — the Spring Boot "walking skeleton".
 *
 * <p>This is deliberately the smallest real, runnable app: enough for the whole
 * CI/CD pipeline (build → image → deploy → health probe) to exercise end-to-end
 * before any feature exists. Business logic, persistence, auth and full actuator
 * metrics arrive in their own foundation tickets (they build off this seam).
 *
 * <p>{@code @SpringBootApplication} enables component scanning from this package
 * downwards, so controllers under {@code io.openskeleton.backend.*} (e.g. the
 * health probe) are picked up automatically.
 */
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
