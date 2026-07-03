package io.openskeleton.backend.notification;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openskeleton.backend.push.PushNotificationService;
import io.openskeleton.backend.user.NotificationPreference;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.UserRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-Mockito unit test for {@link NotificationService} (OSK-89): proves that a
 * notification is routed to the channel the user prefers and that every degradation path is
 * a safe no-op, all with the email + push seams mocked (no live SMTP/FCM, no database).
 *
 * <p>The routing matrix under test:
 *
 * <pre>
 *   EMAIL (with address)   -> emailSender.send, push untouched
 *   EMAIL (no address)     -> neither (no viable channel)
 *   PUSH                   -> pushNotificationService.sendToUser, email untouched
 *   NONE                   -> neither (opted out)
 *   unknown/deleted user   -> neither (nothing to route to)
 *   email seam throws      -> swallowed (best-effort)
 * </pre>
 */
class NotificationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private final UserRepository userRepository = mock(UserRepository.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final PushNotificationService pushNotificationService = mock(PushNotificationService.class);
    private final NotificationService service =
            new NotificationService(userRepository, emailSender, pushNotificationService);

    // --- routing per preference ------------------------------------------------------

    @Test
    void emailPreferenceSendsViaEmailSenderAndNotPush() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(NotificationPreference.EMAIL, "alice@example.com")));

        service.notifyUser(USER_ID, Notification.of("Subject", "Body"));

        verify(emailSender).send("alice@example.com", "Subject", "Body");
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void pushPreferenceSendsViaPushServiceAndNotEmail() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(NotificationPreference.PUSH, "bob@example.com")));
        Map<String, String> data = Map.of("route", "/messages/7");

        service.notifyUser(USER_ID, new Notification("Title", "Body", data));

        verify(pushNotificationService).sendToUser(USER_ID, "Title", "Body", data);
        verifyNoInteractions(emailSender);
    }

    @Test
    void nonePreferenceSendsNothingOnEitherChannel() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(NotificationPreference.NONE, "carol@example.com")));

        service.notifyUser(USER_ID, Notification.of("Subject", "Body"));

        verifyNoInteractions(emailSender);
        verifyNoInteractions(pushNotificationService);
    }

    // --- degradation paths (all safe no-ops) -----------------------------------------

    @Test
    void emailPreferenceWithNoAddressIsASafeSkip() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(NotificationPreference.EMAIL, null)));

        service.notifyUser(USER_ID, Notification.of("Subject", "Body"));

        // No viable channel: neither seam is touched, and nothing throws.
        verifyNoInteractions(emailSender);
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void emailPreferenceWithBlankAddressIsASafeSkip() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(NotificationPreference.EMAIL, "   ")));

        service.notifyUser(USER_ID, Notification.of("Subject", "Body"));

        verifyNoInteractions(emailSender);
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void unknownOrSoftDeletedUserIsASafeSkip() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        service.notifyUser(USER_ID, Notification.of("Subject", "Body"));

        verifyNoInteractions(emailSender);
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void throwingEmailSenderIsSwallowedNotPropagated() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(user(NotificationPreference.EMAIL, "alice@example.com")));
        doThrow(new RuntimeException("SMTP down")).when(emailSender).send(anyString(), anyString(), anyString());

        // Best-effort: a seam that throws must not surface to the caller.
        assertThatCode(() -> service.notifyUser(USER_ID, Notification.of("Subject", "Body")))
                .doesNotThrowAnyException();
        verify(emailSender).send(eq("alice@example.com"), eq("Subject"), eq("Body"));
    }

    // --- argument guards -------------------------------------------------------------

    @Test
    void nullUserIdIsRejected() {
        assertThatThrownBy(() -> service.notifyUser(null, Notification.of("s", "b")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nullNotificationIsRejected() {
        assertThatThrownBy(() -> service.notifyUser(USER_ID, null)).isInstanceOf(NullPointerException.class);
        verifyNoInteractions(userRepository, emailSender, pushNotificationService);
    }

    // --- helpers ---------------------------------------------------------------------

    /** Build a {@link User} with the given preference + email, and a stable id for asserts. */
    private static User user(NotificationPreference preference, String email) {
        User user = new User("firebase-uid", email, "Display Name");
        user.setId(USER_ID);
        user.setNotificationPreference(preference);
        return user;
    }
}
