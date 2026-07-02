package io.openskeleton.backend.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Firebase authentication seam (OSK-28) into the servlet filter chain.
 *
 * <p><b>Why the filter is a {@code @Bean} here and not a {@code @Component}:</b> the
 * {@link FirebaseAuthenticationFilter} depends on a {@link TokenVerifier} service
 * bean. Spring Boot's {@code @WebMvcTest} slices auto-detect {@code @Component}
 * {@code Filter}s but deliberately do NOT load {@code @Component} <i>services</i>, so
 * a component-scanned filter breaks every such slice with a missing-dependency
 * failure. User {@code @Configuration} classes are likewise excluded from those
 * slices — so declaring the filter here scopes it to the full application context
 * (production + every {@code @SpringBootTest}) and keeps the fast web slices
 * (e.g. {@code HealthControllerTest}) unaffected. Spring Boot registers any
 * {@code Filter} bean into the servlet chain automatically.
 */
@Configuration
public class FirebaseAuthConfig {

    /**
     * @param tokenVerifier the verification seam ({@link FirebaseTokenVerifier} in
     *     production; a mock in {@code @SpringBootTest}s that override the bean)
     * @param objectMapper the Spring-managed mapper (carries the {@code ProblemDetail}
     *     Jackson mixin) used to render the 401 body as {@code problem+json}
     * @return the auth filter, registered by Boot across the servlet chain and scoped
     *     to {@code /api/v1/**} internally by the filter itself
     */
    @Bean
    FirebaseAuthenticationFilter firebaseAuthenticationFilter(TokenVerifier tokenVerifier, ObjectMapper objectMapper) {
        return new FirebaseAuthenticationFilter(tokenVerifier, objectMapper);
    }
}
