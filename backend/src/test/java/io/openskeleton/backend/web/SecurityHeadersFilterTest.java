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
}
