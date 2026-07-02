package io.openskeleton.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openskeleton.backend.auth.TokenVerifier;
import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.UserRepository;
import io.openskeleton.backend.user.UserService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * Acceptance test for OSK-76 — JIT user provisioning + persisted {@code /api/v1/me},
 * driven through the real MVC stack against a real Testcontainers Postgres (the
 * {@code jdbc:tc:...} datasource), with ONLY the {@link TokenVerifier} seam mocked
 * exactly as the OSK-66 {@code MeControllerTest} does (a live Firebase project and a
 * genuinely signed token are what the seam abstracts away).
 *
 * <p>Covers the three acceptance criteria against the genuine unique constraint and
 * schema:
 * <ul>
 *   <li><b>AC-1/AC-3 provisioning:</b> the first {@code GET /me} creates the persisted
 *       row; the second reuses the SAME row (no duplicate).</li>
 *   <li><b>AC-1 idempotent under a race:</b> N concurrent first-provisions of the same
 *       brand-new uid all resolve to one row and the same id.</li>
 *   <li><b>AC-2/AC-3 profile update:</b> {@code PATCH /me} persists {@code displayName}
 *       and a later {@code GET /me} reflects it.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeProvisioningIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private DataSource dataSource;

    /** Mock the verification seam so no live Firebase project/credentials are needed. */
    @MockBean
    private TokenVerifier tokenVerifier;

    @Test
    void firstMeCallProvisionsPersistedUserAndSecondReusesIt() throws Exception {
        String token = "jit-token";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken("uid-jit-1", "jit1@example.com"));

        // Precondition: nothing persisted yet for this uid.
        assertThat(userRepository.findByFirebaseUid("uid-jit-1")).isEmpty();

        // First call → provisions the row and returns the persisted profile.
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("uid-jit-1"))
                .andExpect(jsonPath("$.email").value("jit1@example.com"))
                // Freshly provisioned: no displayName yet, default role.
                .andExpect(jsonPath("$.displayName").doesNotExist())
                .andExpect(jsonPath("$.role").value("USER"));

        User afterFirst = userRepository.findByFirebaseUid("uid-jit-1").orElseThrow();
        UUID id = afterFirst.getId();
        assertThat(afterFirst.getEmail()).isEqualTo("jit1@example.com");
        assertThat(afterFirst.getDisplayName()).isNull();

        // Second call → reuses the SAME row (same id), never a duplicate.
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("uid-jit-1"));

        assertThat(userRepository.findByFirebaseUid("uid-jit-1").orElseThrow().getId())
                .isEqualTo(id);
        assertThat(countRows("uid-jit-1")).isEqualTo(1);
    }

    @Test
    void patchMePersistsDisplayNameAndIsReflectedByGet() throws Exception {
        String token = "patch-token";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken("uid-jit-patch", "patch@example.com"));

        // PATCH provisions-then-updates in one shot, even with no prior GET.
        mvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Alice Example\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("uid-jit-patch"))
                .andExpect(jsonPath("$.email").value("patch@example.com"))
                .andExpect(jsonPath("$.displayName").value("Alice Example"))
                .andExpect(jsonPath("$.role").value("USER"));

        // Persisted: visible to a fresh repository read...
        assertThat(userRepository
                        .findByFirebaseUid("uid-jit-patch")
                        .orElseThrow()
                        .getDisplayName())
                .isEqualTo("Alice Example");

        // ...and reflected on the next GET /me (the persisted profile, not the token).
        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Alice Example"));

        assertThat(countRows("uid-jit-patch")).isEqualTo(1);
    }

    @Test
    void concurrentFirstProvisionCreatesExactlyOneRow() throws Exception {
        String uid = "uid-jit-concurrent";
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try {
            List<Future<UUID>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit((Callable<UUID>) () -> {
                    // Release all threads together to maximise the race on the insert.
                    barrier.await();
                    return userService
                            .provisionFromToken(uid, "conc@example.com")
                            .getId();
                }));
            }

            // Every racer returns the SAME persisted id — the unique-constraint race
            // resolved to one shared row for all of them, none saw a 500.
            UUID first = futures.get(0).get();
            for (Future<UUID> f : futures) {
                assertThat(f.get()).isEqualTo(first);
            }
        } finally {
            pool.shutdownNow();
        }

        // Exactly one row exists despite N concurrent first-provisions (AC-1: no duplicate).
        assertThat(countRows(uid)).isEqualTo(1);
    }

    /** Raw count (bypasses the entity soft-delete restriction) of rows for a uid. */
    private int countRows(String firebaseUid) {
        Integer n = new JdbcTemplate(dataSource)
                .queryForObject("SELECT count(*) FROM users WHERE firebase_uid = ?", Integer.class, firebaseUid);
        return n == null ? 0 : n;
    }
}
