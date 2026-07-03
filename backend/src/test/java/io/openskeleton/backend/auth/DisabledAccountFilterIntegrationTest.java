package io.openskeleton.backend.auth;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves that disabling a user actually takes effect (OSK-71): a caller whose token is
 * perfectly valid but whose persisted {@code users} row has {@code enabled = false} is
 * rejected with a 403 by {@link FirebaseAuthenticationFilter} before any handler runs, while
 * an enabled caller passes. This is the "make disabling meaningful" requirement — the flag
 * flipped by {@code PATCH /api/v1/admin/users/{id}/enabled} is enforced at the one choke point
 * every {@code /api/v1/**} request passes through.
 *
 * <p>As with the other auth integration tests only the {@link TokenVerifier} seam is mocked; a
 * real Testcontainers Postgres backs the repository so the {@code enabled} flag is a genuine
 * persisted value.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DisabledAccountFilterIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private TokenVerifier tokenVerifier;

    @BeforeEach
    void clean() {
        userRepository.deleteAll();
    }

    @Test
    void disabledAccountIsRejectedWith403EvenWithAValidToken() throws Exception {
        User disabled = new User("disabled-uid", "disabled@example.com", "Disabled");
        disabled.setEnabled(false);
        userRepository.save(disabled);

        // The token itself verifies fine — the rejection is purely about account status.
        when(tokenVerifier.verify(eq("disabled-token")))
                .thenReturn(new VerifiedToken("disabled-uid", "disabled@example.com"));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer disabled-token"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void enabledAccountPassesThroughTheFilter() throws Exception {
        // enabled defaults to true on a freshly constructed user.
        userRepository.save(new User("enabled-uid", "enabled@example.com", "Enabled"));

        when(tokenVerifier.verify(eq("enabled-token")))
                .thenReturn(new VerifiedToken("enabled-uid", "enabled@example.com"));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer enabled-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("enabled-uid"))
                .andExpect(jsonPath("$.email").value("enabled@example.com"));
    }
}
