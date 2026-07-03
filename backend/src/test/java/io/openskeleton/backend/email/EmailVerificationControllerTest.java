package io.openskeleton.backend.email;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import io.openskeleton.backend.auth.TokenVerifier;
import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance tests for {@code POST /api/v1/me/email/resend-verification} (OSK-77),
 * driven through the real MVC stack — the {@link io.openskeleton.backend.auth.FirebaseAuthenticationFilter}
 * plus {@link EmailVerificationController} — with the Firebase seams mocked, exactly as
 * {@code MeControllerTest} / {@code DeviceControllerTest} do (a live Firebase project and
 * a genuinely signed token / real SMTP are what the seams abstract away).
 *
 * <p>Mocks: {@link TokenVerifier} (so the auth filter authenticates without live
 * Firebase), {@link FirebaseAuth} (so the Admin SDK link-minting + user lookup are
 * stubbed), and {@link EmailVerificationSender} (so dispatch is asserted, never a real
 * email). Covers the endpoint ACs:
 * <ul>
 *   <li><b>unverified -> 202</b> with {@code emailVerified=false}, and the link is minted
 *       for the caller's email and handed to the sender;</li>
 *   <li><b>already verified -> 200</b> with {@code emailVerified=true} and nothing sent;</li>
 *   <li><b>rapid repeat -> 429</b> with {@code Retry-After} (per-user cooldown), only the
 *       first call dispatches;</li>
 *   <li><b>anonymous -> 401</b> problem+json (auth filter);</li>
 *   <li><b>no email -> 422</b>, and a Firebase failure -> degraded <b>503</b>.</li>
 * </ul>
 *
 * <p>Each test uses a DISTINCT uid because the per-user cooldown lives in the (context-
 * cached) service singleton and would otherwise bleed a consumed window across methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EmailVerificationControllerTest {

    private static final String PATH = "/api/v1/me/email/resend-verification";

    @Autowired
    private MockMvc mvc;

    /** Mock the verification seam so the auth filter authenticates without live Firebase. */
    @MockBean
    private TokenVerifier tokenVerifier;

    /** Mock the Admin SDK so link minting + user lookup need no live credentials. */
    @MockBean
    private FirebaseAuth firebaseAuth;

    /** Mock the dispatch seam so we assert delivery was invoked without sending a real email. */
    @MockBean
    private EmailVerificationSender sender;

    /** Authenticate the given token as the given uid/email via the (mocked) verifier. */
    private void authenticate(String token, String uid, String email) throws Exception {
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, email));
    }

    /** Stub the (mocked) Firebase user record returned for a uid. */
    private void stubUser(String uid, String email, boolean verified) throws Exception {
        UserRecord record = mock(UserRecord.class);
        when(record.getEmail()).thenReturn(email);
        when(record.isEmailVerified()).thenReturn(verified);
        when(firebaseAuth.getUser(eq(uid))).thenReturn(record);
    }

    @Test
    void unverifiedCallerGets202AndLinkIsMintedAndDispatched() throws Exception {
        String token = "tok-send";
        String uid = "uid-send";
        String email = "alice@ev.example.com";
        String link = "https://verify.example/oob?code=abc123";
        authenticate(token, uid, email);
        stubUser(uid, email, false);
        when(firebaseAuth.generateEmailVerificationLink(eq(email))).thenReturn(link);

        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.verificationSent").value(true));

        // The link is minted for the caller's authoritative email and dispatched.
        verify(firebaseAuth).generateEmailVerificationLink(email);
        verify(sender).send(email, link);
    }

    @Test
    void alreadyVerifiedCallerGets200AndNothingIsSent() throws Exception {
        String token = "tok-verified";
        String uid = "uid-verified";
        String email = "verified@ev.example.com";
        authenticate(token, uid, email);
        stubUser(uid, email, true);

        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.verificationSent").value(false));

        // No link minted, no dispatch: an already-verified caller is a no-op.
        verify(firebaseAuth, never()).generateEmailVerificationLink(any());
        verifyNoInteractions(sender);
    }

    @Test
    void rapidRepeatIsRateLimitedWith429AndRetryAfter() throws Exception {
        String token = "tok-cooldown";
        String uid = "uid-cooldown";
        String email = "cool@ev.example.com";
        authenticate(token, uid, email);
        stubUser(uid, email, false);
        when(firebaseAuth.generateEmailVerificationLink(eq(email))).thenReturn("https://verify.example/oob?code=z");

        // First send succeeds (202).
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted());

        // Immediate repeat is throttled by the per-user cooldown.
        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too Many Requests"));

        // Only the first request actually dispatched.
        verify(sender, times(1)).send(eq(email), any());
    }

    @Test
    void anonymousCallerGets401ProblemDetail() throws Exception {
        // No Authorization header -> the auth filter short-circuits with 401 before any handler.
        mvc.perform(post(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        verifyNoInteractions(sender);
    }

    @Test
    void callerWithNullEmailGets422() throws Exception {
        String token = "tok-noemail";
        String uid = "uid-noemail";
        authenticate(token, uid, null);
        stubUser(uid, null, false);

        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.title").value("Unprocessable Content"));

        verifyNoInteractions(sender);
    }

    @Test
    void callerWithBlankEmailGets422() throws Exception {
        String token = "tok-blankemail";
        String uid = "uid-blankemail";
        authenticate(token, uid, "");
        stubUser(uid, "", false);

        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));

        verifyNoInteractions(sender);
    }

    @Test
    void firebaseUserLookupFailureDegradesTo503() throws Exception {
        String token = "tok-lookupfail";
        String uid = "uid-lookupfail";
        authenticate(token, uid, "x@ev.example.com");
        when(firebaseAuth.getUser(eq(uid))).thenThrow(FirebaseAuthException.class);

        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.title").value("Service Unavailable"));

        verifyNoInteractions(sender);
    }

    @Test
    void linkMintingFailureDegradesTo503() throws Exception {
        String token = "tok-mintfail";
        String uid = "uid-mintfail";
        String email = "mint@ev.example.com";
        authenticate(token, uid, email);
        stubUser(uid, email, false);
        when(firebaseAuth.generateEmailVerificationLink(eq(email))).thenThrow(FirebaseAuthException.class);

        mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));

        verifyNoInteractions(sender);
    }
}
