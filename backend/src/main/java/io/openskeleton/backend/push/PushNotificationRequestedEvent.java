package io.openskeleton.backend.push;

import java.util.Map;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * Application event asking that a push notification be sent to a user (OSK-155).
 *
 * <p>This is the <b>decoupled</b> trigger seam: any code that wants to notify a user can
 * publish this event via {@code ApplicationEventPublisher} instead of injecting
 * {@link PushNotificationService} directly, so the producer of a notification-worthy domain
 * change need not depend on the push machinery. {@link PushNotificationEventListener}
 * receives it and forwards to the service. (Callers on the hot path may still inject the
 * service directly — both entry points exist; this event is the loose-coupling option.)
 *
 * <p>The fields mirror {@link PushNotificationService#sendToUser} exactly: {@code data} is
 * an optional pass-through payload (supporting the {@code route} deep-link key — OSK-166)
 * and may be {@code null}.
 *
 * @param userId the recipient user
 * @param title the notification title
 * @param body the notification body
 * @param data optional key/value payload passed through to FCM unchanged; may be
 *     {@code null}
 */
public record PushNotificationRequestedEvent(
        UUID userId, String title, String body, @Nullable Map<String, String> data) {}
