package io.openskeleton.backend.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Turns on Spring Security method-level authorization (OSK-71) so handlers can be
 * guarded with {@link org.springframework.security.access.prepost.PreAuthorize @PreAuthorize}
 * (e.g. {@code @PreAuthorize("hasRole('ADMIN')")} on the admin user-management endpoints).
 *
 * <p><b>How the pieces fit together.</b> Authentication and the {@code /api/v1/**}
 * permit-list are still owned by the plain {@link FirebaseAuthenticationFilter} (OSK-28):
 * it verifies the caller's Firebase ID token, rejects an anonymous/invalid caller with a
 * 401, and — since OSK-71 — publishes the caller's role authorities into the Spring
 * {@link org.springframework.security.core.context.SecurityContextHolder SecurityContext}.
 * <i>This</i> config adds the second half: the AOP interceptors that read that
 * SecurityContext at method-invocation time and enforce {@code @PreAuthorize}. A caller
 * who authenticates but lacks {@code ROLE_ADMIN} is therefore rejected with a 403
 * ({@link org.springframework.security.access.AccessDeniedException}, mapped to an RFC 7807
 * {@code problem+json} body by {@code GlobalExceptionHandler}).
 *
 * <p><b>Why method security and not a URL {@code SecurityFilterChain}.</b> OSK-79
 * deliberately kept only {@code spring-security-core} on the classpath (no
 * {@code spring-boot-starter-security} / {@code spring-security-web}) so that Boot's
 * {@code SecurityAutoConfiguration} would not switch on a servlet filter chain and change
 * the whole app's request handling — the exact thing the hand-rolled auth filter exists to
 * avoid. OSK-71 keeps that posture: it adds only {@code spring-security-config} (for
 * {@code @EnableMethodSecurity}) and enforces authorization at the bean/method boundary via
 * AOP, so there is still <b>no</b> Spring web filter chain in front of the app. The web
 * pieces of Boot's security auto-configuration are switched off explicitly (see
 * {@code spring.autoconfigure.exclude} in {@code application.yml}) precisely because they
 * would try to enable a filter chain that {@code spring-security-web} — which we do not
 * ship — would be needed to build.
 *
 * <p>No properties are set on the annotation: the defaults (pre/post annotations enabled,
 * {@code ROLE_} authority prefix) are exactly what {@link RoleClaimMapper} already emits, so
 * {@code hasRole('ADMIN')} matches the {@code ROLE_ADMIN} authority the filter publishes.
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {}
