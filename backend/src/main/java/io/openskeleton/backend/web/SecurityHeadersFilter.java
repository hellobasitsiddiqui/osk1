/*
 * SecurityHeadersFilter — adds a baseline set of HTTP security response headers
 * (OSK-23) to every response the backend emits.
 *
 * WHY a plain servlet filter (not Spring Security):
 *   Pulling in spring-boot-starter-security would switch on default form/basic
 *   authentication across the whole application and break every existing
 *   unauthenticated endpoint and test. Securing HTTP *response headers* does not
 *   require an authentication framework, so we do it with a lightweight
 *   OncePerRequestFilter that just decorates the response. (Full auth is the
 *   separate OSK-28 ticket.)
 *
 * WHY these particular headers:
 *   This backend is a JSON-only API. The browser-facing web UI is a completely
 *   separate nginx-served app, so the API itself never needs to be framed,
 *   never serves HTML/scripts, and (for its JSON endpoints) never needs a
 *   lenient CSP. That lets us ship a deliberately strict, "deny everything"
 *   content policy here without any risk to the web surface — see SECURITY.md.
 *
 * WHY the CSP is path-dependent (OSK-204):
 *   The one exception is springdoc's Swagger UI (/swagger-ui) and OpenAPI JSON
 *   (/v3/api-docs), which DO serve a self-contained HTML docs page that must
 *   load its own bundled scripts/styles to render in a browser. The strict
 *   default-src 'none' policy blocked those and left a blank page, so those
 *   paths get a relaxed (still same-origin) CSP while every other path keeps
 *   the strict policy. This hardens OSK-23 for replay.
 */
package io.openskeleton.backend.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sets baseline security headers on every HTTP response.
 *
 * <p>Registered automatically as a Spring bean via {@link Component}; extending
 * {@link OncePerRequestFilter} guarantees the headers are applied exactly once
 * per request even when the container performs internal forwards/dispatches.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    /** Request header set by TLS-terminating proxies (e.g. Cloud Run) to record the original scheme. */
    private static final String X_FORWARDED_PROTO = "X-Forwarded-Proto";

    /**
     * Strict, API-appropriate Content-Security-Policy. This service returns only
     * JSON and loads no sub-resources, so we forbid every content source
     * ({@code default-src 'none'}), forbid the response from being framed
     * ({@code frame-ancestors 'none'}), and forbid relative-URL rebasing
     * ({@code base-uri 'none'}). The web UI is a separate app and is unaffected.
     */
    private static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'";

    /**
     * Relaxed CSP for the springdoc Swagger UI / OpenAPI paths (OSK-204). Unlike
     * the rest of the API, {@code /swagger-ui/**} serves its own HTML page that
     * must load its bundled scripts, styles, fonts and images to render in a
     * browser. The strict {@code default-src 'none'} policy blocks all of those,
     * leaving a blank page. This policy still keeps everything same-origin
     * ({@code 'self'}) — no third-party sources — but permits the UI's own
     * inline styles/scripts and data: URIs (used for embedded SVG icons) so the
     * documentation actually renders. {@code frame-ancestors 'none'} and
     * {@code base-uri 'none'} are retained to preserve the clickjacking/base-tag
     * protections even on these paths.
     */
    private static final String SWAGGER_CONTENT_SECURITY_POLICY =
            "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:;"
                    + " script-src 'self' 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'";

    /** Springdoc Swagger UI base path (see application.yml: {@code springdoc.swagger-ui.path}). */
    private static final String SWAGGER_UI_PATH_PREFIX = "/swagger-ui";

    /** Springdoc OpenAPI JSON base path (see application.yml: {@code springdoc.api-docs.path}). */
    private static final String API_DOCS_PATH_PREFIX = "/v3/api-docs";

    /** One year, in seconds — the conventional HSTS max-age. */
    private static final String HSTS_VALUE = "max-age=31536000; includeSubDomains";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Applied unconditionally on both HTTP and HTTPS — these are safe everywhere.
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");

        // CSP is path-dependent (OSK-204): the Swagger UI/OpenAPI paths need a
        // relaxed policy to load their own assets, while every other (JSON API)
        // path keeps the strict deny-everything policy.
        response.setHeader("Content-Security-Policy", contentSecurityPolicyFor(request));

        // HSTS instructs browsers to only ever use HTTPS for this host. Emitting it
        // over plain HTTP is meaningless and can wrongly pin a dev/local host, so we
        // send it only when the request actually arrived over TLS — either directly
        // (request.isSecure()) or via a proxy that terminated TLS upstream and told
        // us so through X-Forwarded-Proto: https (the Cloud Run deployment shape).
        if (isSecureRequest(request)) {
            response.setHeader("Strict-Transport-Security", HSTS_VALUE);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Selects the Content-Security-Policy for a request based on its path.
     *
     * <p>Requests to the springdoc Swagger UI ({@code /swagger-ui...}) or the
     * OpenAPI JSON ({@code /v3/api-docs...}) get the {@link
     * #SWAGGER_CONTENT_SECURITY_POLICY relaxed policy} so the docs UI can load
     * its bundled assets in a browser (OSK-204); everything else gets the strict
     * {@link #CONTENT_SECURITY_POLICY deny-everything policy}.
     *
     * <p>We match on {@link HttpServletRequest#getRequestURI()} (which includes
     * any context path) with {@code startsWith} so that both the base paths and
     * their sub-resources (e.g. {@code /swagger-ui/swagger-ui-bundle.js},
     * {@code /v3/api-docs/swagger-config}) are covered.
     */
    private static String contentSecurityPolicyFor(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path != null && (path.startsWith(SWAGGER_UI_PATH_PREFIX) || path.startsWith(API_DOCS_PATH_PREFIX))) {
            return SWAGGER_CONTENT_SECURITY_POLICY;
        }
        return CONTENT_SECURITY_POLICY;
    }

    /**
     * @return {@code true} if the request reached us over HTTPS, either directly
     *     or according to the {@code X-Forwarded-Proto} header set by an upstream
     *     TLS-terminating proxy.
     */
    private static boolean isSecureRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwardedProto = request.getHeader(X_FORWARDED_PROTO);
        return "https".equalsIgnoreCase(forwardedProto);
    }
}
