/*
 * SecurityHeadersFilterTest — verifies the baseline security headers (OSK-23).
 *
 * Uses a full @SpringBootTest context (not a web slice) so the real
 * @Component-registered SecurityHeadersFilter is in the servlet chain, exactly
 * as it will be in production. We hit the existing /health endpoint and assert:
 *   - the always-on headers are present on a plain-HTTP request, AND
 *   - HSTS is ABSENT on plain HTTP but PRESENT when the request is marked as
 *     forwarded HTTPS via X-Forwarded-Proto (the Cloud Run TLS-termination case).
 */
package io.openskeleton.backend.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityHeadersFilterTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * On a plain-HTTP request the always-on headers must be set, and HSTS must be
     * withheld (emitting it over HTTP is meaningless and would wrongly pin dev hosts).
     */
    @Test
    void plainHttpCarriesBaselineHeadersButNoHsts() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    /**
     * When an upstream proxy terminated TLS and forwarded the original scheme via
     * X-Forwarded-Proto: https, HSTS must be emitted so browsers pin HTTPS.
     */
    @Test
    void forwardedHttpsCarriesHsts() throws Exception {
        mockMvc.perform(get("/health").header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000; includeSubDomains"));
    }

    /**
     * OSK-204: the strict {@code default-src 'none'} CSP blanked out the Swagger
     * UI, so the springdoc OpenAPI/Swagger paths must instead receive a relaxed,
     * same-origin CSP that lets the docs load their own assets. We assert the
     * OpenAPI JSON path does NOT carry {@code default-src 'none'} and that it
     * carries the {@code default-src 'self'} policy — while the always-on
     * baseline headers stay intact on this path too.
     */
    @Test
    void openApiDocsPathGetsRelaxedCspNotStrict() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // Always-on baseline headers are unchanged on this path.
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                // The strict deny-everything policy must NOT be applied here...
                .andExpect(header().string("Content-Security-Policy", not(containsString("default-src 'none'"))))
                // ...instead the relaxed same-origin policy is applied.
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")));
    }

    /**
     * OSK-204 guard: non-docs (JSON API) paths must keep the strict CSP. This is
     * the existing /health assertion re-stated as a focused regression check so
     * a future over-broad relaxation of the Swagger carve-out is caught.
     */
    @Test
    void nonDocsPathKeepsStrictCsp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                                "Content-Security-Policy",
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"));
    }
}
