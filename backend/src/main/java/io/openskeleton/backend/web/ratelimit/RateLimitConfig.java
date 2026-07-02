package io.openskeleton.backend.web.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the API rate limiter (OSK-63) into the servlet filter chain.
 *
 * <p><b>Why the filter is a {@code @Bean} here and not a {@code @Component}</b> — the
 * same reasoning as {@link io.openskeleton.backend.auth.FirebaseAuthConfig}: a
 * component-scanned {@code Filter} is auto-detected by {@code @WebMvcTest} slices and
 * would drag its dependencies into those slices. Declaring it in user {@code
 * @Configuration} (which slices do not load) scopes it to the full application
 * context — production and every {@code @SpringBootTest} — while keeping the fast web
 * slices unaffected. Spring Boot registers any {@code Filter} bean into the chain
 * automatically.
 *
 * <p><b>Ordering relative to the auth filter</b> — both this filter and {@link
 * io.openskeleton.backend.auth.FirebaseAuthenticationFilter} are plain (un-ordered)
 * {@code Filter} beans, so Spring Boot registers them at {@code LOWEST_PRECEDENCE}
 * and orders them by registration order; the {@code auth} package is scanned before
 * {@code web}, so the auth filter runs first and has already published the verified
 * {@code uid} request attribute by the time this limiter runs — which is exactly what
 * lets the limiter key its bucket on {@code uid} (falling back to IP otherwise). The
 * {@code RateLimitIntegrationTest} asserts this end-to-end (same uid across different
 * IPs still shares one bucket), so any accidental re-ordering is caught by the build.
 */
@Configuration
public class RateLimitConfig {

    /**
     * @param properties the env-bound limits, discovered via {@code
     *     @ConfigurationPropertiesScan}
     * @param objectMapper the Spring-managed mapper used to render the 429
     *     {@code problem+json} body
     * @return the rate-limit filter, registered by Boot across the servlet chain and
     *     scoped to {@code /api/**} internally by the filter itself
     */
    @Bean
    RateLimitFilter rateLimitFilter(RateLimitProperties properties, ObjectMapper objectMapper) {
        return new RateLimitFilter(properties, objectMapper);
    }
}
