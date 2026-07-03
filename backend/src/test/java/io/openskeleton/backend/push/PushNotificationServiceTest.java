package io.openskeleton.backend.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import io.openskeleton.backend.device.DeviceToken;
import io.openskeleton.backend.device.DeviceToken.Platform;
import io.openskeleton.backend.device.DeviceTokenRepository;
import io.openskeleton.backend.device.DeviceTokenService;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pure-Mockito unit test for {@link PushNotificationService} (OSK-155) — the coverage-
 * critical send/prune logic, exercised with a mocked {@link FirebaseMessaging} so it needs
 * no live Firebase, no credentials, and no database.
 *
 * <p>The Firebase Admin {@link MulticastMessage} / {@link Notification} types expose no
 * public getters (their fields are package-private, for serialization only), so the
 * "message built correctly" assertions read those fields by reflection via
 * {@link #readField}. That is brittle-by-nature but the SDK's message model is stable within
 * a major version, and it is the only way to prove the tokens/notification/data payload were
 * assembled correctly without a live send.
 */
class PushNotificationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private final FirebaseMessaging firebaseMessaging = mock(FirebaseMessaging.class);
    private final DeviceTokenRepository deviceTokenRepository = mock(DeviceTokenRepository.class);
    private final DeviceTokenService deviceTokenService = mock(DeviceTokenService.class);
    private final PushNotificationService service =
            new PushNotificationService(firebaseMessaging, deviceTokenRepository, deviceTokenService);

    // --- message construction --------------------------------------------------------

    @Test
    void buildsMulticastMessageFromTokensAndDataPayload() throws Exception {
        when(deviceTokenRepository.findByUserId(USER_ID)).thenReturn(List.of(device("token-a"), device("token-b")));
        // Build the BatchResponse (and its nested mocks) on its own line: constructing it
        // inline inside the when(...).thenReturn(...) below trips Mockito's UnfinishedStubbing
        // check, because the nested stubbing runs mid-way through the outer stubbing call.
        BatchResponse sendResult = batch(success(), success());
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(sendResult);

        Map<String, String> data = Map.of("route", "/messages/42", "badge", "1");
        service.sendToUser(USER_ID, "New message", "You have a reply", data);

        // Capture the exact MulticastMessage handed to FCM and verify its contents.
        ArgumentCaptor<MulticastMessage> captor = ArgumentCaptor.forClass(MulticastMessage.class);
        verify(firebaseMessaging).sendEachForMulticast(captor.capture());
        MulticastMessage message = captor.getValue();

        assertThat(tokensOf(message)).containsExactly("token-a", "token-b");
        assertThat(dataOf(message)).containsEntry("route", "/messages/42").containsEntry("badge", "1");
        Notification notification = (Notification) readField(message, "notification");
        assertThat(readField(notification, "title")).isEqualTo("New message");
        assertThat(readField(notification, "body")).isEqualTo("You have a reply");

        // All tokens delivered → nothing pruned.
        verify(deviceTokenService, never()).prune(any());
    }

    @Test
    void nullDataAttachesNoPayload() throws Exception {
        when(deviceTokenRepository.findByUserId(USER_ID)).thenReturn(List.of(device("token-a")));
        BatchResponse sendResult = batch(success());
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(sendResult);

        service.sendToUser(USER_ID, "t", "b", null);

        MulticastMessage message = captureSentMessage();
        assertThat(dataOf(message)).isEmpty();
    }

    @Test
    void emptyDataAttachesNoPayload() throws Exception {
        when(deviceTokenRepository.findByUserId(USER_ID)).thenReturn(List.of(device("token-a")));
        BatchResponse sendResult = batch(success());
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(sendResult);

        service.sendToUser(USER_ID, "t", "b", Map.of());

        MulticastMessage message = captureSentMessage();
        assertThat(dataOf(message)).isEmpty();
    }

    // --- dead-token pruning ----------------------------------------------------------

    @Test
    void prunesUnregisteredAndInvalidArgumentTokens() throws Exception {
        when(deviceTokenRepository.findByUserId(USER_ID))
                .thenReturn(List.of(device("good"), device("unregistered"), device("malformed")));
        BatchResponse sendResult = batch(
                success(), failure(MessagingErrorCode.UNREGISTERED), failure(MessagingErrorCode.INVALID_ARGUMENT));
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(sendResult);

        service.sendToUser(USER_ID, "t", "b", null);

        // The two dead tokens are pruned by their exact token string; the good one is kept.
        verify(deviceTokenService).prune("unregistered");
        verify(deviceTokenService).prune("malformed");
        verify(deviceTokenService, never()).prune("good");
    }

    @Test
    void keepsTokenOnTransientFailure() throws Exception {
        when(deviceTokenRepository.findByUserId(USER_ID)).thenReturn(List.of(device("flaky")));
        BatchResponse sendResult = batch(failure(MessagingErrorCode.UNAVAILABLE));
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(sendResult);

        service.sendToUser(USER_ID, "t", "b", null);

        // A transient error is not a dead token — do not prune.
        verify(deviceTokenService, never()).prune(any());
    }

    @Test
    void keepsTokenWhenFailureHasNoErrorCode() throws Exception {
        when(deviceTokenRepository.findByUserId(USER_ID)).thenReturn(List.of(device("odd")));
        SendResponse failedWithoutException = mock(SendResponse.class);
        when(failedWithoutException.isSuccessful()).thenReturn(false);
        when(failedWithoutException.getException()).thenReturn(null);
        BatchResponse sendResult = batch(failedWithoutException);
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(sendResult);

        service.sendToUser(USER_ID, "t", "b", null);

        verify(deviceTokenService, never()).prune(any());
    }

    // --- graceful degradation --------------------------------------------------------

    @Test
    void nullMessagingClientIsANoOp() {
        PushNotificationService disabled = new PushNotificationService(null, deviceTokenRepository, deviceTokenService);

        assertThatCode(() -> disabled.sendToUser(USER_ID, "t", "b", Map.of("route", "/x")))
                .doesNotThrowAnyException();

        // Push disabled: we must not even look up devices, and nothing is pruned.
        verifyNoInteractions(deviceTokenRepository, deviceTokenService);
    }

    @Test
    void userWithNoDevicesIsANoOp() throws Exception {
        when(deviceTokenRepository.findByUserId(USER_ID)).thenReturn(List.of());

        service.sendToUser(USER_ID, "t", "b", null);

        // No devices → never touch FCM, never prune.
        verifyNoInteractions(firebaseMessaging);
        verify(deviceTokenService, never()).prune(any());
    }

    @Test
    void wholeRequestFailureIsSwallowed() throws Exception {
        when(deviceTokenRepository.findByUserId(USER_ID)).thenReturn(List.of(device("token-a")));
        when(firebaseMessaging.sendEachForMulticast(any())).thenThrow(mock(FirebaseMessagingException.class));

        assertThatCode(() -> service.sendToUser(USER_ID, "t", "b", null)).doesNotThrowAnyException();

        // The send blew up entirely: no per-token pruning is attempted.
        verify(deviceTokenService, never()).prune(any());
    }

    // --- test helpers ----------------------------------------------------------------

    private static DeviceToken device(String token) {
        return new DeviceToken(USER_ID, token, Platform.ANDROID);
    }

    private static SendResponse success() {
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(true);
        return response;
    }

    private static SendResponse failure(MessagingErrorCode code) {
        FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
        when(exception.getMessagingErrorCode()).thenReturn(code);
        SendResponse response = mock(SendResponse.class);
        when(response.isSuccessful()).thenReturn(false);
        when(response.getException()).thenReturn(exception);
        return response;
    }

    private static BatchResponse batch(SendResponse... responses) {
        BatchResponse batchResponse = mock(BatchResponse.class);
        when(batchResponse.getResponses()).thenReturn(List.of(responses));
        return batchResponse;
    }

    private MulticastMessage captureSentMessage() throws FirebaseMessagingException {
        ArgumentCaptor<MulticastMessage> captor = ArgumentCaptor.forClass(MulticastMessage.class);
        verify(firebaseMessaging).sendEachForMulticast(captor.capture());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> tokensOf(MulticastMessage message) throws Exception {
        return (List<String>) readField(message, "tokens");
    }

    /** The message's data field is {@code null} when empty; normalise to an empty map. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> dataOf(MulticastMessage message) throws Exception {
        Object data = readField(message, "data");
        return data == null ? Map.of() : (Map<String, String>) data;
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
