package io.openskeleton.backend.notification;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link NotificationEventListener} (OSK-89): a published
 * {@link NotificationRequestedEvent} is forwarded to {@link NotificationService#notifyUser}
 * with its fields unpacked one-to-one — proving the decoupled trigger seam invokes the
 * service.
 */
class NotificationEventListenerTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final NotificationEventListener listener = new NotificationEventListener(notificationService);

    @Test
    void forwardsEventToTheNotificationService() {
        UUID userId = UUID.randomUUID();
        Notification notification = new Notification("Title", "Body", Map.of("route", "/messages/7"));
        NotificationRequestedEvent event = new NotificationRequestedEvent(userId, notification);

        listener.onNotificationRequested(event);

        verify(notificationService).notifyUser(userId, notification);
    }
}
