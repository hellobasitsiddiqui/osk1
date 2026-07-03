package io.openskeleton.backend.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.firebase.auth.FirebaseAuth;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link EmailVerificationService} that exercise branches which the
 * full-stack {@code EmailVerificationControllerTest} cannot reach deterministically —
 * chiefly the "no Firebase Admin client configured" degraded path.
 *
 * <p><b>Why not do this in the MockMvc test:</b> that test {@code @MockBean}s
 * {@link FirebaseAuth} to a non-null instance (so it never sees an absent client), and in
 * an environment where Application Default Credentials happen to be present the real
 * {@link FirebaseAdminConfig} bean is also non-null. Constructing the service here with an
 * {@link ObjectProvider} that reports the bean as absent lets us assert the degraded
 * behaviour without depending on the ambient credential state.
 */
class EmailVerificationServiceTest {

    private final EmailVerificationSender sender = mock(EmailVerificationSender.class);

    private EmailVerificationService serviceWith(FirebaseAuth firebaseAuthOrNull) {
        @SuppressWarnings("unchecked")
        ObjectProvider<FirebaseAuth> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(firebaseAuthOrNull);

        EmailVerificationProperties properties = new EmailVerificationProperties();
        properties.setResendCooldown(Duration.ofSeconds(60));
        return new EmailVerificationService(provider, sender, properties);
    }

    @Test
    void resendDegradesWhenNoAdminClientIsConfigured() {
        EmailVerificationService service = serviceWith(null);

        assertThatThrownBy(() -> service.resend("uid-x")).isInstanceOf(EmailVerificationUnavailableException.class);
        verifyNoInteractions(sender);
    }

    @Test
    void resendMintsAndDispatchesForAnUnverifiedCaller() throws Exception {
        FirebaseAuth auth = mock(FirebaseAuth.class);
        com.google.firebase.auth.UserRecord record = mock(com.google.firebase.auth.UserRecord.class);
        when(record.getEmail()).thenReturn("carol@example.com");
        when(record.isEmailVerified()).thenReturn(false);
        when(auth.getUser(eq("uid-carol"))).thenReturn(record);
        when(auth.generateEmailVerificationLink(eq("carol@example.com"))).thenReturn("https://verify.example/oob");

        EmailVerificationService.ResendResult result = serviceWith(auth).resend("uid-carol");

        assertThat(result.email()).isEqualTo("carol@example.com");
        assertThat(result.emailVerified()).isFalse();
        assertThat(result.verificationSent()).isTrue();
    }
}
