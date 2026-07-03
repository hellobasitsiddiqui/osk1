package io.openskeleton.backend.push;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import io.openskeleton.backend.device.DeviceToken;
import io.openskeleton.backend.device.DeviceTokenRepository;
import io.openskeleton.backend.device.DeviceTokenService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Sends a push notification to every device a user has registered, via Firebase Cloud
 * Messaging (OSK-155).
 *
 * <p>This is the single send-push seam other code triggers (directly, or via a
 * {@link PushNotificationRequestedEvent}). It deliberately owns <b>only</b> "deliver this
 * message to this user's devices" — <i>whether</i> a given event should notify a user
 * (per-user preference routing) is a separate concern handled later (OSK-89); callers here
 * decide to send.
 *
 * <p><b>What a send does:</b>
 *
 * <ol>
 *   <li>Look up the user's device tokens ({@link DeviceTokenRepository#findByUserId}).</li>
 *   <li>Build ONE {@link MulticastMessage}: a {@link Notification} (title/body) plus the
 *       caller's {@code data} map passed through unchanged — including the {@code route}
 *       key used for client-side deep-linking (OSK-166), which this service does not
 *       interpret.</li>
 *   <li>Send it with {@link FirebaseMessaging#sendEachForMulticast(MulticastMessage)} — the
 *       current multicast API in firebase-admin 9.9.0 (it fans out to one request per token
 *       and returns a per-token {@link BatchResponse}, index-aligned with the input
 *       tokens).</li>
 *   <li>Prune tokens FCM reports as permanently dead — {@link MessagingErrorCode#UNREGISTERED}
 *       (app uninstalled / token rotated) and {@link MessagingErrorCode#INVALID_ARGUMENT}
 *       (malformed token) — via {@link DeviceTokenService#prune(String)} so we stop sending
 *       to them. Transient failures (e.g. {@code UNAVAILABLE}, {@code INTERNAL},
 *       {@code QUOTA_EXCEEDED}) are logged but the token is kept for the next attempt.</li>
 * </ol>
 *
 * <p><b>Graceful degradation — never throws on a delivery problem.</b> Push is a
 * best-effort side channel, so nothing here propagates an exception to the caller:
 *
 * <ul>
 *   <li>If the {@link FirebaseMessaging} client is {@code null} (FCM not enabled — the human
 *       OSK-165 step — or no ADC in this environment/test) the send is a logged no-op.</li>
 *   <li>If the user has no registered devices, it is a logged no-op.</li>
 *   <li>If the whole multicast request fails ({@link FirebaseMessagingException}), it is
 *       logged and swallowed.</li>
 * </ul>
 *
 * The messaging client is injected (constructor) so tests supply a Mockito mock, or
 * {@code null}, without touching real Firebase.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    /**
     * The FCM client, or {@code null} when push is disabled. {@code @Nullable} on the
     * constructor parameter lets Spring inject {@code null} when {@link PushConfig} could
     * not build a client (see that class); direct callers (tests) pass a mock or
     * {@code null}.
     */
    @Nullable private final FirebaseMessaging firebaseMessaging;

    /** Read side: lists a user's registered device tokens (the multicast recipient set). */
    private final DeviceTokenRepository deviceTokenRepository;

    /**
     * Write side for the prune path: the transactional delete-by-token seam OSK-154 built
     * specifically for "a push provider rejected this token, drop it".
     */
    private final DeviceTokenService deviceTokenService;

    public PushNotificationService(
            @Nullable FirebaseMessaging firebaseMessaging,
            DeviceTokenRepository deviceTokenRepository,
            DeviceTokenService deviceTokenService) {
        this.firebaseMessaging = firebaseMessaging;
        this.deviceTokenRepository = deviceTokenRepository;
        this.deviceTokenService = deviceTokenService;
    }

    /**
     * Send a push notification to all of {@code userId}'s registered devices. Best-effort:
     * any delivery problem is logged, never thrown (see class docs).
     *
     * @param userId the recipient user whose devices should be notified
     * @param title the notification title shown on the device
     * @param body the notification body shown on the device
     * @param data optional key/value payload delivered alongside the notification; passed
     *     to FCM unchanged (supports the {@code route} deep-link key — OSK-166). May be
     *     {@code null} or empty, in which case no data payload is attached.
     */
    public void sendToUser(UUID userId, String title, String body, @Nullable Map<String, String> data) {
        // Degradation path #1: FCM not enabled / no credentials — nothing to send through.
        if (firebaseMessaging == null) {
            log.debug("Push disabled (no FirebaseMessaging client); skipping notification to user {}.", userId);
            return;
        }

        // Degradation path #2: the user has registered no devices — nothing to send to.
        List<DeviceToken> devices = deviceTokenRepository.findByUserId(userId);
        if (devices.isEmpty()) {
            log.debug("User {} has no registered device tokens; nothing to send.", userId);
            return;
        }

        // Order matters: the BatchResponse below is index-aligned with this token list, so
        // a failed response at index i maps back to tokens.get(i) for pruning.
        List<String> tokens = devices.stream().map(DeviceToken::getToken).toList();

        MulticastMessage message = buildMessage(tokens, title, body, data);

        BatchResponse response;
        try {
            response = firebaseMessaging.sendEachForMulticast(message);
        } catch (FirebaseMessagingException e) {
            // Whole-request failure (e.g. auth/transport): best-effort, so log and stop.
            log.warn("Multicast push to user {} failed entirely: {}", userId, e.getMessage());
            return;
        }

        log.debug(
                "Push to user {}: {} delivered, {} failed (of {} device(s)).",
                userId,
                response.getSuccessCount(),
                response.getFailureCount(),
                tokens.size());
        pruneDeadTokens(tokens, response);
    }

    /**
     * Assemble the multicast message. The {@code data} map is attached only when non-empty
     * and is passed through verbatim (including any {@code route} deep-link key).
     */
    private static MulticastMessage buildMessage(
            List<String> tokens, String title, String body, @Nullable Map<String, String> data) {
        MulticastMessage.Builder builder = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(
                        Notification.builder().setTitle(title).setBody(body).build());
        if (data != null && !data.isEmpty()) {
            builder.putAllData(data);
        }
        return builder.build();
    }

    /**
     * Walk the per-token responses and delete every token FCM reports as permanently dead,
     * so future sends skip them. The response list is index-aligned with {@code tokens}.
     */
    private void pruneDeadTokens(List<String> tokens, BatchResponse response) {
        List<SendResponse> responses = response.getResponses();
        for (int i = 0; i < responses.size(); i++) {
            SendResponse sendResponse = responses.get(i);
            if (sendResponse.isSuccessful()) {
                continue;
            }
            String token = tokens.get(i);
            FirebaseMessagingException exception = sendResponse.getException();
            if (isDeadToken(exception)) {
                deviceTokenService.prune(token);
                log.info(
                        "Pruned dead FCM token (error {}) so it is no longer sent to.",
                        exception.getMessagingErrorCode());
            } else {
                // Transient/other failure: keep the token for a later retry.
                log.warn(
                        "Transient push failure for a token (error {}); keeping it registered.",
                        exception == null ? "unknown" : exception.getMessagingErrorCode());
            }
        }
    }

    /**
     * A token is permanently dead — and must be pruned — when FCM reports it as
     * {@link MessagingErrorCode#UNREGISTERED} (the app was uninstalled or the token rotated)
     * or {@link MessagingErrorCode#INVALID_ARGUMENT} (the token is malformed). All other
     * error codes (and a missing exception) are treated as transient and the token is kept.
     */
    private static boolean isDeadToken(@Nullable FirebaseMessagingException exception) {
        if (exception == null) {
            return false;
        }
        MessagingErrorCode code = exception.getMessagingErrorCode();
        return code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT;
    }
}
