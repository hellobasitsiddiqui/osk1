package io.openskeleton.backend.avatar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link AvatarService} — the "confirm + set photoURL" step (OSK-83). The Admin
 * {@link FirebaseAuth} seam is resolved through a mocked {@link ObjectProvider} (or yields
 * {@code null}), so no live Firebase project or credentials are needed to cover the happy path plus
 * both degradation paths (no client → 503, live failure → 503) and the validation-first guard.
 */
class AvatarServiceTest {

    private static final String UID = "uid-123";
    private static final String BUCKET = "openskeleton-one.firebasestorage.app";
    private static final String OBJECT_PATH = "users/uid-123/avatar/1.png";
    private static final String DOWNLOAD_URL = "https://firebasestorage.googleapis.com/v0/b/" + BUCKET
            + "/o/users%2Fuid-123%2Favatar%2F1.png?alt=media&token=abc";

    /** An {@link ObjectProvider} whose {@code getIfAvailable()} yields {@code auth} (may be null). */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<FirebaseAuth> providerOf(FirebaseAuth auth) {
        ObjectProvider<FirebaseAuth> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auth);
        return provider;
    }

    @Test
    void setsThePhotoUrlViaTheAdminSdkOnAValidReference() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        when(firebaseAuth.updateUser(any())).thenReturn(mock(UserRecord.class));

        AvatarService service = new AvatarService(providerOf(firebaseAuth), BUCKET);
        String applied = service.updateAvatar(UID, OBJECT_PATH, DOWNLOAD_URL);

        assertThat(applied).isEqualTo(DOWNLOAD_URL);
        // The Admin SDK was asked to update exactly one user's photoURL.
        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));
    }

    @Test
    void rejectsAnInvalidReferenceBeforeTouchingFirebase() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        AvatarService service = new AvatarService(providerOf(firebaseAuth), BUCKET);

        // Object path belongs to a different user → 400, and Firebase is never called.
        assertThatThrownBy(() -> service.updateAvatar(UID, "users/other/avatar/1.png", DOWNLOAD_URL))
                .isInstanceOf(InvalidAvatarException.class);
        verify(firebaseAuth, never()).updateUser(any());
    }

    @Test
    void degradesTo503WhenNoAdminClientConfigured() {
        // No ADC / Storage not enabled yet (OSK-95) — a write must fail loudly, not pretend success.
        AvatarService service = new AvatarService(providerOf(null), BUCKET);

        assertThatThrownBy(() -> service.updateAvatar(UID, OBJECT_PATH, DOWNLOAD_URL))
                .isInstanceOf(AvatarUnavailableException.class);
    }

    @Test
    void degradesTo503WhenTheFirebaseCallFails() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        when(firebaseAuth.updateUser(any())).thenThrow(new RuntimeException("firebase down"));

        AvatarService service = new AvatarService(providerOf(firebaseAuth), BUCKET);

        assertThatThrownBy(() -> service.updateAvatar(UID, OBJECT_PATH, DOWNLOAD_URL))
                .isInstanceOf(AvatarUnavailableException.class);
    }
}
