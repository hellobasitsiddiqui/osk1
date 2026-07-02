package io.openskeleton.backend.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Establishes the {@code /api/v1} URL versioning convention for the whole
 * application API surface (OSK-18).
 *
 * <p><b>What:</b> every {@code @RestController} that lives in the
 * {@code io.openskeleton.backend.api} package (or a sub-package) is automatically
 * served under a {@code /api/v1} path prefix. A controller that declares
 * {@code @GetMapping("/info")} is therefore reachable at {@code /api/v1/info} — the
 * controller code stays version-agnostic and the version lives in exactly one
 * place (this class).
 *
 * <p><b>Why a package-scoped prefix instead of hard-coding {@code /api/v1} on each
 * mapping:</b> it makes versioning a cross-cutting routing concern rather than a
 * per-controller copy-paste that drifts. Adding a new endpoint costs nothing extra
 * (drop a controller in the {@code api} package and it inherits the prefix), and
 * the "how do we cut v2" question has a single, documented answer (see
 * ARCHITECTURE.md → "API versioning convention").
 *
 * <p><b>What is deliberately NOT versioned:</b> the predicate is scoped to the
 * {@code api} base package only, so operational/infrastructure endpoints are left
 * untouched — Actuator ({@code /actuator/**}), the OpenAPI docs
 * ({@code /v3/api-docs}, {@code /swagger-ui}) and the deploy liveness probe
 * ({@code /health}, in the {@code health} package). Those are contracts consumed by
 * infrastructure (Docker/Cloud Run curl {@code /health}), not by API clients, so
 * they must stay at stable, unversioned paths.
 */
@Configuration
public class ApiVersioningConfig implements WebMvcConfigurer {

    /** The single source of truth for the current API version prefix. */
    static final String API_V1_PREFIX = "/api/v1";

    /** The base package whose controllers form the versioned application API. */
    static final String API_BASE_PACKAGE = "io.openskeleton.backend.api";

    /**
     * Registers the {@code /api/v1} prefix against only the controllers in the
     * {@code api} base package. {@link HandlerTypePredicate#forBasePackage} scopes
     * the prefix so unrelated controllers (health probe, and any Actuator/springdoc
     * handlers) are never rewritten.
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_V1_PREFIX, HandlerTypePredicate.forBasePackage(API_BASE_PACKAGE));
    }
}
