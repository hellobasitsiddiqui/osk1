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
 *   never serves HTML/scripts, and never needs a lenient CSP. That lets us ship
 *   a deliberately strict, "deny everything" content policy here without any
 *   risk to the web surface — see SECURITY.md for the rationale.
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

    /** One year, in seconds — the conventional HSTS max-age. */
    private static final String HSTS_VALUE = "max-age=31536000; includeSubDomains";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Applied unconditionally on both HTTP and HTTPS — these are safe everywhere.
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);

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
