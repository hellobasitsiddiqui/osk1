package io.openskeleton.backend.user;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link LastActiveInterceptor} against the versioned API surface (OSK-73).
 *
 * <p>Scoped to {@code /api/v1/**} — the exact set of paths the {@code FirebaseAuthenticationFilter}
 * protects — so the last-active heartbeat only ever fires for authenticated API traffic and never
 * for public/operational endpoints (health, actuator, OpenAPI docs). Kept as its own tiny
 * {@link WebMvcConfigurer} so the last-active concern stays self-contained in the {@code user}
 * package rather than being bolted onto an unrelated web config.
 *
 * <p><b>Why {@link LastActiveService} is resolved via {@link ObjectProvider} (and not injected
 * directly).</b> Spring Boot's {@code @WebMvcTest} slices auto-detect {@link WebMvcConfigurer}
 * beans (so the app's MVC customisations apply) but deliberately do NOT load {@code @Service}
 * components or the JPA layer. A hard {@code LastActiveService} constructor dependency would
 * therefore fail every web-slice test (e.g. {@code HealthControllerTest}) with a missing-bean
 * error. Taking an {@code ObjectProvider} lets this config construct unconditionally and simply
 * skip registering the interceptor when the service isn't present (a slice has no {@code /api/v1}
 * controllers to stamp for anyway); in the full application context and every {@code @SpringBootTest}
 * the service is present and the interceptor is wired normally.
 */
@Configuration
public class LastActiveWebConfig implements WebMvcConfigurer {

    /** The versioned API prefix the auth filter guards; the heartbeat mirrors that scope. */
    private static final String PROTECTED_API_PATTERN = "/api/v1/**";

    private final ObjectProvider<LastActiveService> lastActiveService;

    public LastActiveWebConfig(ObjectProvider<LastActiveService> lastActiveService) {
        this.lastActiveService = lastActiveService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LastActiveService service = lastActiveService.getIfAvailable();
        if (service != null) {
            registry.addInterceptor(new LastActiveInterceptor(service)).addPathPatterns(PROTECTED_API_PATTERN);
        }
    }
}
