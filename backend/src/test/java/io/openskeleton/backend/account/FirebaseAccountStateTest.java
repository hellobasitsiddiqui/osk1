package io.openskeleton.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.firebase.auth.UserMetadata;
import com.google.firebase.auth.UserRecord;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FirebaseAccountState} — the pure projection of a Firebase {@link UserRecord}
 * onto the read-only account fields /me exposes (OSK-73). Uses Mockito stand-ins for the SDK types
 * (both are non-final) so no live Firebase is needed.
 */
class FirebaseAccountStateTest {

    @Test
    void mapsAllFieldsFromAVerifiedUserRecord() {
        long lastSignInMillis = 1_700_000_000_000L; // fixed instant for a deterministic assertion
        UserMetadata metadata = mock(UserMetadata.class);
        when(metadata.getLastSignInTimestamp()).thenReturn(lastSignInMillis);

        UserRecord record = mock(UserRecord.class);
        when(record.isEmailVerified()).thenReturn(true);
        when(record.getPhoneNumber()).thenReturn("+15551234567");
        when(record.getPhotoUrl()).thenReturn("https://example.com/photo.png");
        when(record.getUserMetadata()).thenReturn(metadata);

        FirebaseAccountState state = FirebaseAccountState.from(record);

        assertThat(state.emailVerified()).isTrue();
        // A present phone number means Firebase verified it → phoneVerified true, number surfaced.
        assertThat(state.phoneVerified()).isTrue();
        assertThat(state.phoneNumber()).isEqualTo("+15551234567");
        assertThat(state.photoUrl()).isEqualTo("https://example.com/photo.png");
        assertThat(state.lastLoginAt()).isEqualTo(Instant.ofEpochMilli(lastSignInMillis));
        // MFA is not readable on firebase-admin 9.9.0 — always null (documented in the contract).
        assertThat(state.mfaEnabled()).isNull();
    }

    @Test
    void treatsAbsentPhoneAsNotVerified() {
        UserRecord record = mock(UserRecord.class);
        when(record.isEmailVerified()).thenReturn(false);
        when(record.getPhoneNumber()).thenReturn(null);
        when(record.getUserMetadata()).thenReturn(null);

        FirebaseAccountState state = FirebaseAccountState.from(record);

        assertThat(state.emailVerified()).isFalse();
        assertThat(state.phoneVerified()).isFalse();
        assertThat(state.phoneNumber()).isNull();
        // No metadata → never signed in → null last-login.
        assertThat(state.lastLoginAt()).isNull();
    }

    @Test
    void treatsBlankPhoneAsNotVerified() {
        UserRecord record = mock(UserRecord.class);
        when(record.getPhoneNumber()).thenReturn("   ");
        when(record.getUserMetadata()).thenReturn(null);

        FirebaseAccountState state = FirebaseAccountState.from(record);

        assertThat(state.phoneVerified()).isFalse();
    }

    @Test
    void treatsNonPositiveLastSignInAsNull() {
        UserMetadata metadata = mock(UserMetadata.class);
        when(metadata.getLastSignInTimestamp()).thenReturn(0L); // never signed in

        UserRecord record = mock(UserRecord.class);
        when(record.getUserMetadata()).thenReturn(metadata);

        FirebaseAccountState state = FirebaseAccountState.from(record);

        assertThat(state.lastLoginAt()).isNull();
    }

    @Test
    void emptyIsAllNull() {
        FirebaseAccountState empty = FirebaseAccountState.empty();

        assertThat(empty.emailVerified()).isNull();
        assertThat(empty.mfaEnabled()).isNull();
        assertThat(empty.phoneVerified()).isNull();
        assertThat(empty.phoneNumber()).isNull();
        assertThat(empty.photoUrl()).isNull();
        assertThat(empty.lastLoginAt()).isNull();
    }
}
