package io.openskeleton.backend.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import io.openskeleton.backend.notification.EmailSender;
import io.openskeleton.backend.user.UserRepository;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end acceptance test for the passwordless email-code login endpoints (OSK-129), driven
 * through the real MVC + Spring stack against a real Testcontainers Postgres (the
 * {@code jdbc:tc:...} datasource), with only the Firebase Admin client
 * ({@link FirebaseAuth#createCustomToken}) and the {@link EmailSender} dispatch seam mocked.
 *
 * <p>Proves the acceptance criteria against the genuine schema + filter chain:
 * <ul>
 *   <li><b>public start -> 202</b> with NO {@code Authorization} header (the endpoints are
 *       exempt from the auth filter), and a code is dispatched via the sender;</li>
 *   <li><b>verify round-trip</b>: submitting the emailed code returns a minted custom token,
 *       the persisted user is JIT-provisioned for the resolved uid+email, and re-submitting the
 *       same code is refused (single-use -> 401);</li>
 *   <li><b>wrong code -> 401</b>, <b>invalid email -> 400</b> (bean validation), and</li>
 *   <li><b>per-email rate limit -> 429</b> with {@code Retry-After} once the start budget is
 *       spent.</li>
 * </ul>
 *
 * <p>Each test uses a DISTINCT email because the per-email rate-limiter buckets live in the
 * (context-cached) limiter singleton and would otherwise bleed a spent budget across methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailCodeControllerIntegrationTest {

    private static final String START = "/api/v1/auth/email-code/start";
    private static final String VERIFY = "/api/v1/auth/email-code/verify";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b\\d{6}\\b");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository userRepository;

    /** Mock the Admin SDK so identity resolution + custom-token mint need no live credentials. */
    @MockBean
    private FirebaseAuth firebaseAuth;

    /** Mock the dispatch seam so we can read the code without sending a real email. */
    @MockBean
    private EmailSender emailSender;

    @Test
    void startIsPublicAndAcceptsWithoutAuth() throws Exception {
        String email = "start-public@example.com";
        // No Authorization header at all — proves the endpoint is exempt from the auth filter.
        mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON).content(json(email)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("code_sent"));

        verify(emailSender).send(eq(email), anyString(), anyString());
    }

    @Test
    void verifyRoundTripMintsTokenProvisionsUserAndIsSingleUse() throws Exception {
        String email = "round-trip@example.com";

        // 1) Start: issue + dispatch a code, and capture it from the (mocked) sender.
        mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON).content(json(email)))
                .andExpect(status().isAccepted());
        String code = captureSentCode(email);

        // 2) Stub Firebase: an existing user for this email + the token it should mint.
        UserRecord record = org.mockito.Mockito.mock(UserRecord.class);
        when(record.getUid()).thenReturn("uid-round-trip");
        when(firebaseAuth.getUserByEmail(email)).thenReturn(record);
        when(firebaseAuth.createCustomToken("uid-round-trip")).thenReturn("custom-token-rt");

        // 3) Verify the real code -> 200 + the minted token.
        mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(json(email, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("custom-token-rt"));

        // The persisted account was reconciled (OSK-163) for the resolved uid + email.
        var provisioned = userRepository.findByFirebaseUid("uid-round-trip").orElseThrow();
        assertThat(provisioned.getEmail()).isEqualTo(email);

        // 4) The SAME code cannot be replayed (single-use) -> 401.
        mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(json(email, code)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifyRejectsAWrongCode() throws Exception {
        String email = "wrong-code@example.com";
        mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON).content(json(email)))
                .andExpect(status().isAccepted());
        // A deliberately incorrect code -> generic 401, no token.
        mvc.perform(post(VERIFY).contentType(MediaType.APPLICATION_JSON).content(json(email, "000000")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void startRejectsAnInvalidEmailWith400() throws Exception {
        mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void perEmailStartBudgetReturns429WithRetryAfter() throws Exception {
        String email = "rate-limit@example.com";
        // Default per-email capacity is 5, so the first 5 starts pass and the 6th is throttled.
        for (int i = 0; i < 5; i++) {
            mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON).content(json(email)))
                    .andExpect(status().isAccepted());
        }
        mvc.perform(post(START).contentType(MediaType.APPLICATION_JSON).content(json(email)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }

    // ---- helpers ----------------------------------------------------------------------

    /** Capture the 6-digit code the service handed to the (mocked) email sender for {@code email}. */
    private String captureSentCode(String email) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(email), anyString(), body.capture());
        Matcher m = SIX_DIGITS.matcher(body.getValue());
        assertThat(m.find()).as("email body should contain a 6-digit code").isTrue();
        return m.group();
    }

    private static String json(String email) {
        return "{\"email\":\"" + email + "\"}";
    }

    private static String json(String email, String code) {
        return "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}";
    }
}
