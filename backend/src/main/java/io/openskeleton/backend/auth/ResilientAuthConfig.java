package io.openskeleton.backend.auth;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wraps the live Firebase verifier with the OSK-65 resilience layer and makes the
 * wrapped instance the primary {@link TokenVerifier} the auth filter sees.
 *
 * <p><b>Bean wiring:</b> {@link FirebaseTokenVerifier} stays a plain {@code @Component}
 * (the real adapter). This config decorates it with a {@link ResilientTokenVerifier}
 * and marks that {@code @Primary}, so every {@code TokenVerifier} injection point —
 * notably {@code FirebaseAuthConfig}'s filter bean — resolves to the resilient wrapper
 * without any caller change. The delegate is injected by its concrete type so there is
 * no self-referential ambiguity between the two {@code TokenVerifier} beans.
 *
 * <p>The three operators are pulled from the registries that the
 * {@code resilience4j-spring-boot3} starter auto-configures from the
 * {@code resilience4j.*} keys in {@code application.yml}, so all tuning (timeout,
 * retry attempts/backoff, breaker thresholds) lives in config, not code.
 */
@Configuration
public class ResilientAuthConfig {

    /** The shared Resilience4j instance name; must match the {@code resilience4j.*} keys in application.yml. */
    static final String INSTANCE_NAME = "tokenVerifier";

    /**
     * @param delegate the live Firebase adapter to protect (injected by concrete type
     *     to avoid ambiguity with the resilient wrapper, which is also a
     *     {@link TokenVerifier})
     * @param circuitBreakerRegistry auto-configured from {@code resilience4j.circuitbreaker.*}
     * @param retryRegistry auto-configured from {@code resilience4j.retry.*}
     * @param timeLimiterRegistry auto-configured from {@code resilience4j.timelimiter.*}
     * @return the primary {@link TokenVerifier} — the Firebase adapter behind a
     *     TimeLimiter + Retry + CircuitBreaker
     */
    @Bean
    @Primary
    ResilientTokenVerifier resilientTokenVerifier(
            FirebaseTokenVerifier delegate,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            TimeLimiterRegistry timeLimiterRegistry) {
        return new ResilientTokenVerifier(
                delegate,
                circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME),
                retryRegistry.retry(INSTANCE_NAME),
                timeLimiterRegistry.timeLimiter(INSTANCE_NAME));
    }
}
