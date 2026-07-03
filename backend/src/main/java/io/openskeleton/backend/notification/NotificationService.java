package io.openskeleton.backend.notification;

import io.openskeleton.backend.push.PushNotificationService;
import io.openskeleton.backend.user.NotificationPreference;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.UserRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Routes a {@link Notification} to a user over the channel that user has chosen
 * ({@link NotificationPreference}), the "actually deliver it per preference" piece of
 * OSK-89.
 *
 * <p><b>What it owns vs. what it delegates.</b> This service owns exactly one decision:
 * <i>given a user, which channel (if any) should this notification go out on?</i> It reads
 * the user's persisted preference and then hands the actual bytes-on-the-wire to the
 * channel-specific seams — {@link EmailSender} for EMAIL, {@link PushNotificationService}
 * for PUSH — neither of which it re-implements. NONE is an opt-out and sends nothing.
 *
 * <pre>
 *   preference  ->  action
 *   EMAIL       ->  emailSender.send(user.email, subject, body)   (skipped if no email)
 *   PUSH        ->  pushNotificationService.sendToUser(id, title, body, data)
 *   NONE        ->  no-op (the user opted out)
 * </pre>
 *
 * <p><b>Graceful degradation — never throws on a delivery problem (AC-3).</b>
 * Notifications are a best-effort side channel, so nothing here propagates to the caller:
 *
 * <ul>
 *   <li><b>Unknown / soft-deleted user</b> — {@code findById} respects the soft-delete
 *       restriction, so a missing or deleted user is a logged skip, not a 404 thrown at a
 *       background caller.</li>
 *   <li><b>No viable channel</b> — an EMAIL-preference user with no email on file has
 *       nothing to send to, so it is a logged skip.</li>
 *   <li><b>Channel not live</b> — when SMTP is not wired ({@link LoggingEmailSender}, OSK-139)
 *       or FCM is disabled ({@link PushNotificationService} degrades internally, OSK-165),
 *       the send is already a safe no-op at the seam.</li>
 *   <li><b>A seam that throws</b> — a future SMTP sender could fail mid-send; the EMAIL path
 *       is wrapped so such a failure is logged and swallowed rather than surfaced.</li>
 * </ul>
 *
 * Both senders are injected so tests supply Mockito mocks and assert routing without any
 * live FCM/SMTP.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** Read side: loads the recipient (their preference + email). Excludes soft-deleted rows. */
    private final UserRepository userRepository;

    /** EMAIL-channel delivery seam (logging no-op until SMTP is wired — OSK-139). */
    private final EmailSender emailSender;

    /** PUSH-channel delivery seam (degrades to a no-op when FCM is disabled — OSK-165). */
    private final PushNotificationService pushNotificationService;

    public NotificationService(
            UserRepository userRepository, EmailSender emailSender, PushNotificationService pushNotificationService) {
        this.userRepository = userRepository;
        this.emailSender = emailSender;
        this.pushNotificationService = pushNotificationService;
    }

    /**
     * Deliver {@code notification} to {@code userId} over that user's preferred channel.
     * Best-effort: any delivery problem is logged, never thrown (see class docs).
     *
     * @param userId the recipient user (an active {@code users} row is looked up by this id)
     * @param notification the channel-agnostic payload (title/subject + body + optional data)
     */
    public void notifyUser(UUID userId, Notification notification) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(notification, "notification");

        Optional<User> maybeUser = userRepository.findById(userId);
        if (maybeUser.isEmpty()) {
            // Unknown or soft-deleted: nothing to route to. Skip rather than throw — a
            // background notification for a vanished user is not an error worth propagating.
            log.debug("No active user {} to notify; skipping notification.", userId);
            return;
        }

        User user = maybeUser.get();
        NotificationPreference preference = user.getNotificationPreference();
        switch (preference) {
            case EMAIL -> sendEmail(user, notification);
            case PUSH ->
                pushNotificationService.sendToUser(
                        userId, notification.subject(), notification.body(), notification.data());
            case NONE -> log.debug("User {} has opted out (preference NONE); sending nothing.", userId);
        }
    }

    /**
     * EMAIL-channel delivery. Skips when the user has no address on file (no viable channel),
     * and wraps the send so a throwing SMTP implementation cannot break the caller — the
     * notification is best-effort.
     */
    private void sendEmail(User user, Notification notification) {
        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            // EMAIL preference but no address to reach them at: no viable channel, so skip.
            log.warn("User {} prefers EMAIL but has no email address on file; skipping.", user.getId());
            return;
        }
        try {
            emailSender.send(email, notification.subject(), notification.body());
        } catch (RuntimeException e) {
            // Best-effort side channel: a delivery failure is logged, never surfaced.
            log.warn("Email notification to user {} failed: {}", user.getId(), e.getMessage());
        }
    }
}
