package io.openskeleton.backend.push;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link PushNotificationEventListener} (OSK-155): a published
 * {@link PushNotificationRequestedEvent} is forwarded to
 * {@link PushNotificationService#sendToUser} with its fields unpacked one-to-one.
 */
class PushNotificationEventListenerTest {

    private final PushNotificationService pushNotificationService = mock(PushNotificationService.class);
    private final PushNotificationEventListener listener = new PushNotificationEventListener(pushNotificationService);

    @Test
    void forwardsEventFieldsToTheSendPushService() {
        UUID userId = UUID.randomUUID();
        Map<String, String> data = Map.of("route", "/messages/7");
        PushNotificationRequestedEvent event = new PushNotificationRequestedEvent(userId, "Title", "Body", data);

        listener.onPushRequested(event);

        verify(pushNotificationService).sendToUser(userId, "Title", "Body", data);
    }
}
