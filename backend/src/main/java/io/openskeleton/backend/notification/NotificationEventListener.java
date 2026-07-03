package io.openskeleton.backend.notification;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridges a published {@link NotificationRequestedEvent} to
 * {@link NotificationService#notifyUser} (OSK-89).
 *
 * <p>Having the listener as its own small bean (rather than annotating the service) keeps
 * {@link NotificationService} free of Spring event coupling and makes the "publish an event
 * → a user is notified" wiring explicit and independently testable — mirroring the push
 * event bridge (OSK-155).
 *
 * <p>The handler runs <b>synchronously</b> in the publisher's thread — deliberately simple
 * for the MVP. Because {@code notifyUser} is best-effort and never throws (see its docs), a
 * delivery failure cannot break the publisher's flow. Making delivery asynchronous (so the
 * publisher does not wait on the send) is a later refinement and is intentionally out of
 * scope here.
 */
@Component
public class NotificationEventListener {

    private final NotificationService notificationService;

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * Forward the requested notification to the routing service, unpacking the event's
     * fields one-to-one.
     *
     * @param event the requested notification (recipient + channel-agnostic payload)
     */
    @EventListener
    public void onNotificationRequested(NotificationRequestedEvent event) {
        notificationService.notifyUser(event.userId(), event.notification());
    }
}
