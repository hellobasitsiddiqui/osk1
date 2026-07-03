package io.openskeleton.backend.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openskeleton.backend.auth.TokenVerifier;
import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance test for OSK-154 — the device-token registration endpoints — driven
 * through the real MVC stack (auth filter + {@link DeviceController}) against a real
 * Testcontainers Postgres (the {@code jdbc:tc:...} datasource), with ONLY the
 * {@link TokenVerifier} seam mocked, exactly as {@code MeControllerTest} /
 * {@code MeProvisioningIntegrationTest} do (a live Firebase project and a genuinely
 * signed token are what the seam abstracts away).
 *
 * <p>Covers the endpoint acceptance criteria against the genuine schema + UNIQUE
 * constraint:
 * <ul>
 *   <li><b>AC-1 register (upsert-new):</b> first {@code POST} inserts the token bound to
 *       the caller.</li>
 *   <li><b>AC-1 idempotent (upsert-existing):</b> re-{@code POST}ing the SAME token does
 *       not create a duplicate — one row, updated in place.</li>
 *   <li><b>AC-2 deregister:</b> {@code DELETE /{token}} removes the caller's token and is
 *       idempotent (a second delete is a 204 no-op).</li>
 *   <li><b>Auth path:</b> an anonymous {@code POST}/{@code DELETE} is a 401 in the
 *       standard RFC 7807 {@code problem+json} shape, before any handler runs.</li>
 *   <li><b>Validation:</b> a blank token is a 400 via the global bean-validation handler.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeviceControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Autowired
    private DataSource dataSource;

    /** Mock the verification seam so no live Firebase project/credentials are needed. */
    @MockBean
    private TokenVerifier tokenVerifier;

    @Test
    void postRegistersNewTokenBoundToCaller() throws Exception {
        String bearer = "reg-token";
        when(tokenVerifier.verify(eq(bearer))).thenReturn(new VerifiedToken("uid-dev-1", "dev1@example.com"));

        String pushToken = "fcm-token-new";
        assertThat(deviceTokenRepository.findByToken(pushToken)).isEmpty();

        mvc.perform(post("/api/v1/me/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + pushToken + "\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").value(pushToken))
                .andExpect(jsonPath("$.platform").value("ANDROID"));

        // Persisted: exactly one row, owned by the caller, with the right platform.
        var stored = deviceTokenRepository.findByToken(pushToken).orElseThrow();
        assertThat(stored.getPlatform()).isEqualTo(DeviceToken.Platform.ANDROID);
        assertThat(stored.getUserId()).isNotNull();
        assertThat(countRows(pushToken)).isEqualTo(1);
    }

    @Test
    void reRegisteringSameTokenIsIdempotentAndUpdatesInPlace() throws Exception {
        String bearer = "reg-token-2";
        when(tokenVerifier.verify(eq(bearer))).thenReturn(new VerifiedToken("uid-dev-2", "dev2@example.com"));
        String pushToken = "fcm-token-idem";

        // First registration (ANDROID) → inserts.
        mvc.perform(post("/api/v1/me/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + pushToken + "\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isOk());
        var afterFirst = deviceTokenRepository.findByToken(pushToken).orElseThrow();

        // Re-register the SAME token, now as WEB → updates in place, no duplicate.
        mvc.perform(post("/api/v1/me/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + pushToken + "\",\"platform\":\"WEB\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platform").value("WEB"));

        // Same row id (updated, not replaced), platform refreshed, still exactly one row.
        var afterSecond = deviceTokenRepository.findByToken(pushToken).orElseThrow();
        assertThat(afterSecond.getId()).isEqualTo(afterFirst.getId());
        assertThat(afterSecond.getPlatform()).isEqualTo(DeviceToken.Platform.WEB);
        assertThat(countRows(pushToken)).isEqualTo(1);
    }

    @Test
    void deleteDeregistersCallerTokenAndIsIdempotent() throws Exception {
        String bearer = "reg-token-3";
        when(tokenVerifier.verify(eq(bearer))).thenReturn(new VerifiedToken("uid-dev-3", "dev3@example.com"));
        String pushToken = "fcm-token-del";

        // Register, then confirm it exists.
        mvc.perform(post("/api/v1/me/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + pushToken + "\",\"platform\":\"IOS\"}"))
                .andExpect(status().isOk());
        assertThat(deviceTokenRepository.findByToken(pushToken)).isPresent();

        // Deregister → 204 and the row is gone.
        mvc.perform(delete("/api/v1/me/devices/{token}", pushToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                .andExpect(status().isNoContent());
        assertThat(deviceTokenRepository.findByToken(pushToken)).isEmpty();

        // Idempotent: a second delete of the now-absent token is still a 204 no-op.
        mvc.perform(delete("/api/v1/me/devices/{token}", pushToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                .andExpect(status().isNoContent());
    }

    @Test
    void anonymousPostIsUnauthorizedProblemDetail() throws Exception {
        // No Authorization header → the auth filter short-circuits with a 401 problem+json
        // before the controller runs (identity is never taken from the body).
        mvc.perform(post("/api/v1/me/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"whatever\",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void anonymousDeleteIsUnauthorized() throws Exception {
        mvc.perform(delete("/api/v1/me/devices/{token}", "some-token")).andExpect(status().isUnauthorized());
    }

    @Test
    void blankTokenIsBadRequest() throws Exception {
        String bearer = "reg-token-4";
        when(tokenVerifier.verify(eq(bearer))).thenReturn(new VerifiedToken("uid-dev-4", "dev4@example.com"));

        // Blank token fails @NotBlank → 400 via the global bean-validation handler.
        mvc.perform(post("/api/v1/me/devices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"   \",\"platform\":\"ANDROID\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request"));
    }

    /** Raw count of device_tokens rows for a given token (there is at most one). */
    private int countRows(String token) {
        Integer n = new JdbcTemplate(dataSource)
                .queryForObject("SELECT count(*) FROM device_tokens WHERE token = ?", Integer.class, token);
        return n == null ? 0 : n;
    }
}
