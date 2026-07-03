package io.openskeleton.backend.notification;

import java.util.Map;
import org.springframework.lang.Nullable;

/**
 * A channel-agnostic notification payload (OSK-89): the "what to say", decoupled from the
 * "how to deliver it". {@link NotificationService} routes one of these to whichever channel
 * the recipient prefers, mapping the same fields onto each transport:
 *
 * <ul>
 *   <li><b>EMAIL</b> — {@code subject} becomes the email subject line, {@code body} the
 *       email body. ({@code data} is not used by the email channel.)</li>
 *   <li><b>PUSH</b> — {@code subject} becomes the push <i>title</i>, {@code body} the push
 *       body, and {@code data} is passed through to FCM unchanged (including the
 *       {@code route} deep-link key — OSK-166).</li>
 * </ul>
 *
 * <p>Kept a plain immutable record with no behaviour so producers can construct one without
 * depending on any channel machinery; the routing/preference policy lives entirely in
 * {@link NotificationService}.
 *
 * @param subject the title/subject line shown to the user (push title or email subject);
 *     must be non-null/non-blank — a notification with nothing to say is a programming error
 * @param body the main body text shown to the user; must be non-null (may be empty)
 * @param data optional key/value payload delivered alongside a PUSH notification (supports
 *     the {@code route} deep-link key — OSK-166); passed through to FCM verbatim and ignored
 *     by the email channel. May be {@code null}, meaning "no data payload".
 */
public record Notification(String subject, String body, @Nullable Map<String, String> data) {

    /**
     * Compact canonical constructor validating the always-required fields. Guarding here (a
     * single construction point) means every downstream channel can assume a well-formed
     * title/body without re-checking.
     */
    public Notification {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("notification subject must not be null/blank");
        }
        if (body == null) {
            throw new IllegalArgumentException("notification body must not be null");
        }
    }

    /**
     * Convenience factory for the common case of a notification with no {@code data} payload
     * (e.g. an email-only or push-without-deep-link message).
     *
     * @param subject the title/subject line
     * @param body the body text
     * @return a {@link Notification} with a {@code null} data payload
     */
    public static Notification of(String subject, String body) {
        return new Notification(subject, body, null);
    }

    /**
     * Convenience factory for a notification carrying a client-side deep-link {@code route}
     * (OSK-166). The route is placed into the {@code data} payload under the {@code "route"}
     * key so a PUSH recipient can deep-link; it is inert on the email channel.
     *
     * @param subject the title/subject line
     * @param body the body text
     * @param route the deep-link route to attach under the {@code "route"} data key
     * @return a {@link Notification} whose {@code data} carries {@code {"route": route}}
     */
    public static Notification withRoute(String subject, String body, String route) {
        return new Notification(subject, body, Map.of("route", route));
    }
}
