package io.openskeleton.backend.notification;

import java.util.UUID;

/**
 * Application event asking that a user be notified over their preferred channel (OSK-89).
 *
 * <p>This is the <b>decoupled</b> trigger seam: any future feature that wants to notify a
 * user on a domain change can publish this event via {@code ApplicationEventPublisher}
 * instead of injecting {@link NotificationService} directly, so the producer of a
 * notification-worthy change need not depend on the delivery machinery.
 * {@link NotificationEventListener} receives it and forwards to the service, which then
 * routes by the user's {@link io.openskeleton.backend.user.NotificationPreference}. (Callers
 * that already hold the service may still call {@link NotificationService#notifyUser} directly
 * — both entry points exist; this event is the loose-coupling option.)
 *
 * <p>Deliberately no live producers are wired in this ticket: exposing the seam is the
 * deliverable, not spraying notifications from existing flows.
 *
 * @param userId the recipient user
 * @param notification the channel-agnostic payload (title/subject + body + optional data)
 */
public record NotificationRequestedEvent(UUID userId, Notification notification) {}
