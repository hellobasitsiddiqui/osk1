package io.openskeleton.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the OpenSkeleton backend — the Spring Boot "walking skeleton".
 *
 * <p>Deliberately the smallest real, runnable app: enough for the whole CI/CD
 * pipeline (build → image → deploy → health probe) to exercise end-to-end before
 * any feature exists. Persistence, auth and full actuator metrics arrive in their
 * own foundation tickets.
 *
 * <p>{@code @ConfigurationPropertiesScan} discovers and binds validated config
 * beans (e.g. {@link io.openskeleton.backend.config.AppProperties}) so bad config
 * fails startup fast rather than at first use.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
