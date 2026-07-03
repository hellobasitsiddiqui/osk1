package io.openskeleton.backend.push;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridges a published {@link PushNotificationRequestedEvent} to
 * {@link PushNotificationService#sendToUser} (OSK-155).
 *
 * <p>Having the listener as its own small bean (rather than annotating the service) keeps
 * {@link PushNotificationService} free of Spring event coupling and makes the "publish an
 * event → a push is sent" wiring explicit and independently testable.
 *
 * <p>The handler runs <b>synchronously</b> in the publisher's thread — deliberately simple
 * for the MVP. Because {@code sendToUser} is best-effort and never throws (see its docs), a
 * push failure cannot break the publisher's flow. Making delivery asynchronous (so the
 * publisher does not wait on the FCM round-trip) is a later refinement and is intentionally
 * out of scope here.
 */
@Component
public class PushNotificationEventListener {

    private final PushNotificationService pushNotificationService;

    public PushNotificationEventListener(PushNotificationService pushNotificationService) {
        this.pushNotificationService = pushNotificationService;
    }

    /**
     * Forward the requested notification to the send-push service, unpacking the event's
     * fields one-to-one.
     *
     * @param event the requested push (recipient + title/body + optional data payload)
     */
    @EventListener
    public void onPushRequested(PushNotificationRequestedEvent event) {
        pushNotificationService.sendToUser(event.userId(), event.title(), event.body(), event.data());
    }
}
