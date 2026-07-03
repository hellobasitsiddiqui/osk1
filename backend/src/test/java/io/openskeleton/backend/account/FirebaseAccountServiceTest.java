package io.openskeleton.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link FirebaseAccountService} — the live, fail-soft Firebase account-state lookup
 * (OSK-73). The {@link FirebaseAuth} seam is resolved through a mocked {@link ObjectProvider} (or
 * yields {@code null}), so no live Firebase project or credentials are needed to cover all three
 * paths: happy lookup, no-client degradation, and lookup-failure degradation.
 */
class FirebaseAccountServiceTest {

    private static final String UID = "uid-123";

    /** An {@link ObjectProvider} whose {@code getIfAvailable()} yields {@code auth} (may be null). */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<FirebaseAuth> providerOf(FirebaseAuth auth) {
        ObjectProvider<FirebaseAuth> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(auth);
        return provider;
    }

    @Test
    void returnsMappedStateWhenLookupSucceeds() throws Exception {
        UserRecord record = mock(UserRecord.class);
        when(record.isEmailVerified()).thenReturn(true);
        when(record.getPhoneNumber()).thenReturn(null);
        when(record.getUserMetadata()).thenReturn(null);

        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        when(firebaseAuth.getUser(eq(UID))).thenReturn(record);

        FirebaseAccountState state = new FirebaseAccountService(providerOf(firebaseAuth)).stateFor(UID);

        assertThat(state.emailVerified()).isTrue();
        assertThat(state.phoneVerified()).isFalse();
    }

    @Test
    void degradesToEmptyWhenNoFirebaseClient() {
        // No Admin SDK client on this instance (no ADC / not configured) — must not crash.
        FirebaseAccountState state = new FirebaseAccountService(providerOf(null)).stateFor(UID);

        assertThat(state).isEqualTo(FirebaseAccountState.empty());
    }

    @Test
    void degradesToEmptyWhenLookupThrows() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        // e.g. the uid has no Firebase record (anonymous sign-in) or the SDK/network misbehaves.
        when(firebaseAuth.getUser(eq(UID))).thenThrow(new RuntimeException("boom"));

        FirebaseAccountState state = new FirebaseAccountService(providerOf(firebaseAuth)).stateFor(UID);

        assertThat(state).isEqualTo(FirebaseAccountState.empty());
    }
}
