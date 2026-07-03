package io.openskeleton.backend.login;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import io.openskeleton.backend.notification.EmailSender;
import io.openskeleton.backend.user.UserService;
import io.openskeleton.backend.web.ratelimit.TokenBucket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link EmailLoginCodeService} — the OSK-129 orchestration — with every
 * collaborator mocked (store, rate limiter, email sender, the Firebase Admin client behind an
 * {@link ObjectProvider}, and {@code UserService}) and a fixed {@link Clock}. This covers the
 * branches the full-stack integration test can't easily reach deterministically: the degraded
 * "no Admin client" path, the "create a Firebase user for a new email" path, and the rate-limit
 * / invalid-code mappings.
 */
class EmailLoginCodeServiceTest {

    private static final String EMAIL = "alice@example.com";
    private static final Instant NOW = Instant.parse("2026-07-03T10:00:00Z");
    private static final Pattern SIX_DIGITS = Pattern.compile("\\b\\d{6}\\b");

    private final EmailLoginCodeStore store = mock(EmailLoginCodeStore.class);
    private final EmailLoginRateLimiter rateLimiter = mock(EmailLoginRateLimiter.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final UserService userService = mock(UserService.class);
    private final EmailLoginProperties properties = new EmailLoginProperties();

    @SuppressWarnings("unchecked")
    private EmailLoginCodeService serviceWith(FirebaseAuth firebaseAuthOrNull) {
        ObjectProvider<FirebaseAuth> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(firebaseAuthOrNull);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new EmailLoginCodeService(store, rateLimiter, properties, emailSender, provider, userService, clock);
    }

    private void allowRateLimit() {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(new TokenBucket.Decision(true, 0L));
    }

    // ---- start ------------------------------------------------------------------------

    @Test
    void startIssuesAHashedCodeAndSendsIt() {
        allowRateLimit();
        EmailLoginCodeService service = serviceWith(mock(FirebaseAuth.class));

        service.start(EMAIL);

        // A code row was issued with a 10-minute TTL from the fixed clock.
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(store).issue(eq(EMAIL), hash.capture(), eq(NOW.plus(properties.getTtl())));
        assertThat(hash.getValue()).hasSize(64); // SHA-256 hex, never a plaintext code

        // The email carried a 6-digit code, and that code hashes to exactly what was stored.
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(EMAIL), anyString(), body.capture());
        Matcher m = SIX_DIGITS.matcher(body.getValue());
        assertThat(m.find()).as("email body should contain a 6-digit code").isTrue();
        assertThat(EmailLoginCodeHasher.hash(EMAIL, m.group())).isEqualTo(hash.getValue());
    }

    @Test
    void startNormalisesTheEmailBeforeIssuing() {
        allowRateLimit();
        serviceWith(mock(FirebaseAuth.class)).start("  ALICE@Example.COM ");
        // Stored + sent against the trimmed, lower-cased address.
        verify(store).issue(eq(EMAIL), anyString(), any());
        verify(emailSender).send(eq(EMAIL), anyString(), anyString());
    }

    @Test
    void startIsRateLimited() {
        when(rateLimiter.tryAcquire("start:" + EMAIL)).thenReturn(new TokenBucket.Decision(false, 42L));
        EmailLoginCodeService service = serviceWith(mock(FirebaseAuth.class));

        assertThatThrownBy(() -> service.start(EMAIL))
                .isInstanceOf(EmailLoginRateLimitedException.class)
                .satisfies(e -> assertThat(((EmailLoginRateLimitedException) e).getRetryAfterSeconds())
                        .isEqualTo(42L));

        verifyNoInteractions(store, emailSender);
    }

    // ---- verify ------------------------------------------------------------------------

    @Test
    void verifyDegradesWhenNoAdminClientIsConfigured() {
        allowRateLimit();
        EmailLoginCodeService service = serviceWith(null);

        assertThatThrownBy(() -> service.verify(EMAIL, "123456")).isInstanceOf(EmailLoginUnavailableException.class);
        // The code is NOT consumed on an infra outage — the caller keeps their valid code.
        verifyNoInteractions(store);
    }

    @Test
    void verifyIsRateLimited() {
        when(rateLimiter.tryAcquire("verify:" + EMAIL)).thenReturn(new TokenBucket.Decision(false, 7L));
        EmailLoginCodeService service = serviceWith(mock(FirebaseAuth.class));

        assertThatThrownBy(() -> service.verify(EMAIL, "123456")).isInstanceOf(EmailLoginRateLimitedException.class);
        verifyNoInteractions(store);
    }

    @Test
    void verifyRejectsAnInvalidCode() {
        allowRateLimit();
        when(store.consume(eq(EMAIL), anyString(), eq(NOW), anyInt()))
                .thenReturn(Optional.of(InvalidLoginCodeException.Reason.MISMATCH));
        EmailLoginCodeService service = serviceWith(mock(FirebaseAuth.class));

        assertThatThrownBy(() -> service.verify(EMAIL, "000000")).isInstanceOf(InvalidLoginCodeException.class);
        // No identity is resolved / provisioned for a bad code.
        verifyNoInteractions(userService);
    }

    @Test
    void verifyMintsACustomTokenForAnExistingFirebaseUser() throws Exception {
        allowRateLimit();
        when(store.consume(eq(EMAIL), anyString(), eq(NOW), anyInt())).thenReturn(Optional.empty());

        FirebaseAuth auth = mock(FirebaseAuth.class);
        UserRecord record = mock(UserRecord.class);
        when(record.getUid()).thenReturn("uid-alice");
        when(auth.getUserByEmail(EMAIL)).thenReturn(record);
        when(auth.createCustomToken("uid-alice")).thenReturn("custom-token-abc");

        String token = serviceWith(auth).verify(EMAIL, "123456");

        assertThat(token).isEqualTo("custom-token-abc");
        // The persisted account is reconciled by identity (OSK-163) using the resolved uid+email.
        verify(userService).provisionFromToken("uid-alice", EMAIL);
        // No new Firebase user is created when one already exists.
        verify(auth, never()).createUser(any());
    }

    @Test
    void verifyCreatesAFirebaseUserForANewEmailThenMints() throws Exception {
        allowRateLimit();
        when(store.consume(eq(EMAIL), anyString(), eq(NOW), anyInt())).thenReturn(Optional.empty());

        FirebaseAuth auth = mock(FirebaseAuth.class);
        FirebaseAuthException notFound = mock(FirebaseAuthException.class);
        when(notFound.getAuthErrorCode()).thenReturn(AuthErrorCode.USER_NOT_FOUND);
        when(auth.getUserByEmail(EMAIL)).thenThrow(notFound);

        UserRecord created = mock(UserRecord.class);
        when(created.getUid()).thenReturn("uid-new");
        when(auth.createUser(any(UserRecord.CreateRequest.class))).thenReturn(created);
        when(auth.createCustomToken("uid-new")).thenReturn("custom-token-new");

        String token = serviceWith(auth).verify(EMAIL, "123456");

        assertThat(token).isEqualTo("custom-token-new");
        verify(auth).createUser(any(UserRecord.CreateRequest.class));
        verify(userService).provisionFromToken("uid-new", EMAIL);
    }

    @Test
    void verifyDegradesWhenTokenMintFails() throws Exception {
        allowRateLimit();
        when(store.consume(eq(EMAIL), anyString(), eq(NOW), anyInt())).thenReturn(Optional.empty());

        FirebaseAuth auth = mock(FirebaseAuth.class);
        UserRecord record = mock(UserRecord.class);
        when(record.getUid()).thenReturn("uid-alice");
        when(auth.getUserByEmail(EMAIL)).thenReturn(record);
        when(auth.createCustomToken("uid-alice")).thenThrow(mock(FirebaseAuthException.class));

        assertThatThrownBy(() -> serviceWith(auth).verify(EMAIL, "123456"))
                .isInstanceOf(EmailLoginUnavailableException.class);
    }
}
