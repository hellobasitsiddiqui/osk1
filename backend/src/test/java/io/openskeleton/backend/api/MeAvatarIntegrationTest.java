package io.openskeleton.backend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.google.firebase.auth.FirebaseAuth;
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
 * Acceptance test for OSK-83 — {@code PUT /api/v1/me/avatar} reflecting a client-uploaded avatar as
 * the caller's Firebase {@code photoURL}, driven through the real MVC stack (auth filter +
 * {@link MeController}) with ONLY the {@link TokenVerifier} seam and the Admin {@link FirebaseAuth}
 * mocked — exactly as {@code MeControllerTest} mocks the verifier and {@code FirebaseAccountServiceTest}
 * mocks the Admin client. No live Firebase project, Storage bucket, or ADC is needed.
 *
 * <p><b>Why {@code @MockBean FirebaseAuth}:</b> the Admin bean is normally {@code null} in tests (no
 * ADC), which would make the endpoint degrade to 503. Registering a mock lets us exercise the happy
 * path — {@link AvatarService} calls {@code updateUser(photoURL)}, and the SAME mock backs the live
 * {@code /me} read ({@code FirebaseAccountService.getUser}) so the response reflects the new avatar
 * end-to-end — as well as the 503-on-Firebase-failure degradation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MeAvatarIntegrationTest {

    private static final String BUCKET = "openskeleton-one.firebasestorage.app";

    @Autowired
    private MockMvc mvc;

    /** Mock the verification seam so no live Firebase project/credentials are needed. */
    @MockBean
    private TokenVerifier tokenVerifier;

    /** Mock the Admin SDK client (normally null in tests) so the avatar write + live read succeed. */
    @MockBean
    private FirebaseAuth firebaseAuth;

    /** A genuine-shaped Firebase download URL for {@code users/<uid>/avatar/<name>}. */
    private static String downloadUrl(String uid, String name) {
        return "https://firebasestorage.googleapis.com/v0/b/" + BUCKET + "/o/users%2F" + uid + "%2Favatar%2F" + name
                + "?alt=media&token=tok-123";
    }

    @Test
    void putAvatarSetsPhotoUrlAndReflectsItOnTheResponse() throws Exception {
        String token = "avatar-token-ok";
        String uid = "uid-avatar-ok";
        String objectPath = "users/" + uid + "/avatar/1720000000000.png";
        String url = downloadUrl(uid, "1720000000000.png");
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "ok@avatar.example.com"));

        // After updateUser(photoURL), the live /me read (getUser) must return the new photoURL so the
        // response reflects it end-to-end.
        UserRecord record = org.mockito.Mockito.mock(UserRecord.class);
        when(record.isEmailVerified()).thenReturn(true);
        when(record.getPhoneNumber()).thenReturn(null);
        when(record.getUserMetadata()).thenReturn(null);
        when(record.getPhotoUrl()).thenReturn(url);
        when(firebaseAuth.getUser(eq(uid))).thenReturn(record);
        when(firebaseAuth.updateUser(any())).thenReturn(record);

        mvc.perform(put("/api/v1/me/avatar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectPath\":\"" + objectPath + "\",\"downloadUrl\":\"" + url + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.photoUrl").value(url));

        // The Admin SDK was asked to set exactly one user's photoURL.
        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));
    }

    @Test
    void putAvatarRejectsAnObjectPathOutsideTheCallersFolderWith400() throws Exception {
        String token = "avatar-token-owner";
        String uid = "uid-avatar-owner";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "owner@avatar.example.com"));

        // Path targets a DIFFERENT user's folder — the ownership guard rejects it (400) and Firebase
        // is never touched.
        String foreign = "users/someone-else/avatar/1.png";
        mvc.perform(put("/api/v1/me/avatar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectPath\":\"" + foreign + "\",\"downloadUrl\":\""
                                + downloadUrl("someone-else", "1.png") + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Bad Request"));

        verify(firebaseAuth, never()).updateUser(any());
    }

    @Test
    void putAvatarRejectsANonImageObjectWith400() throws Exception {
        String token = "avatar-token-type";
        String uid = "uid-avatar-type";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "type@avatar.example.com"));

        String objectPath = "users/" + uid + "/avatar/notanimage.txt";
        mvc.perform(put("/api/v1/me/avatar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectPath\":\"" + objectPath + "\",\"downloadUrl\":\""
                                + downloadUrl(uid, "notanimage.txt") + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(firebaseAuth, never()).updateUser(any());
    }

    @Test
    void putAvatarRejectsADownloadUrlOnAnUntrustedHostWith400() throws Exception {
        String token = "avatar-token-url";
        String uid = "uid-avatar-url";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "url@avatar.example.com"));

        String objectPath = "users/" + uid + "/avatar/1.png";
        mvc.perform(put("/api/v1/me/avatar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectPath\":\"" + objectPath
                                + "\",\"downloadUrl\":\"https://evil.example.com/steal\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verify(firebaseAuth, never()).updateUser(any());
    }

    @Test
    void putAvatarRejectsABlankBodyFieldWith400() throws Exception {
        String token = "avatar-token-blank";
        String uid = "uid-avatar-blank";
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "blank@avatar.example.com"));

        // @NotBlank on objectPath → bean-validation 400 (MethodArgumentNotValidException) with the
        // field named in the errors array (the shape web/profile.js renders inline).
        mvc.perform(put("/api/v1/me/avatar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectPath\":\"\",\"downloadUrl\":\"" + downloadUrl(uid, "1.png") + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("objectPath")));

        verify(firebaseAuth, never()).updateUser(any());
    }

    @Test
    void putAvatarDegradesTo503WhenTheFirebaseCallFails() throws Exception {
        String token = "avatar-token-503";
        String uid = "uid-avatar-503";
        String objectPath = "users/" + uid + "/avatar/1.png";
        String url = downloadUrl(uid, "1.png");
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "down@avatar.example.com"));
        // The Admin write fails → degrade to a 503 with a Retry-After hint (never a leaked 500).
        when(firebaseAuth.updateUser(any())).thenThrow(new RuntimeException("firebase down"));

        mvc.perform(put("/api/v1/me/avatar")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectPath\":\"" + objectPath + "\",\"downloadUrl\":\"" + url + "\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.title").value("Service Unavailable"));
    }

    @Test
    void putAvatarRequiresAuthentication() throws Exception {
        // No Authorization header → the auth filter short-circuits with a 401 before the handler runs.
        mvc.perform(put("/api/v1/me/avatar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"objectPath\":\"users/x/avatar/1.png\",\"downloadUrl\":\""
                                + downloadUrl("x", "1.png") + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMeStillSurfacesTheFirebaseOwnedPhotoUrl() throws Exception {
        // Sanity: /me continues to read photoUrl LIVE from Firebase (OSK-73) — the field the avatar
        // upload sets — so no schema change was needed to surface the avatar.
        String token = "avatar-token-get";
        String uid = "uid-avatar-get";
        String url = downloadUrl(uid, "existing.png");
        when(tokenVerifier.verify(eq(token))).thenReturn(new VerifiedToken(uid, "get@avatar.example.com"));

        UserRecord record = org.mockito.Mockito.mock(UserRecord.class);
        when(record.isEmailVerified()).thenReturn(true);
        when(record.getPhoneNumber()).thenReturn(null);
        when(record.getUserMetadata()).thenReturn(null);
        when(record.getPhotoUrl()).thenReturn(url);
        when(firebaseAuth.getUser(eq(uid))).thenReturn(record);

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUrl").value(url));
    }
}
