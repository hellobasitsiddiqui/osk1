package io.openskeleton.backend.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.openskeleton.backend.device.DeviceToken.Platform;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.UserService;
import io.openskeleton.backend.web.NotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Service-level acceptance test for OSK-154 covering the paths not exercised over HTTP by
 * {@code DeviceControllerTest}: the prune seam (AC-4), owner-scoped deregistration, the
 * per-user fan-out lookup, and the no-user-resolved error branch. Runs as a full
 * {@code @SpringBootTest} against the Testcontainers Postgres so the real UNIQUE
 * constraint and foreign key are in force.
 *
 * <p>Users are provisioned through {@link UserService#provisionFromToken(String, String)}
 * (the same JIT path production uses) so the {@code user_id} foreign key always resolves
 * to a real {@code users} row.
 */
@SpringBootTest
class DeviceTokenServiceTest {

    @Autowired
    private DeviceTokenService deviceTokenService;

    @Autowired
    private DeviceTokenRepository deviceTokenRepository;

    @Autowired
    private UserService userService;

    @Test
    void pruneRemovesTokenRegardlessOfOwnerAndIsIdempotent() {
        userService.provisionFromToken("uid-prune", "prune@example.com");
        String token = "fcm-token-prune";
        deviceTokenService.register("uid-prune", token, Platform.ANDROID);
        assertThat(deviceTokenRepository.findByToken(token)).isPresent();

        // AC-4: prune drops the token (the send-push seam for a stale token) → true.
        assertThat(deviceTokenService.prune(token)).isTrue();
        assertThat(deviceTokenRepository.findByToken(token)).isEmpty();

        // Idempotent: pruning an already-absent token is a no-op returning false.
        assertThat(deviceTokenService.prune(token)).isFalse();
    }

    @Test
    void deregisterIsScopedToTheOwningCaller() {
        User owner = userService.provisionFromToken("uid-owner", "owner@example.com");
        userService.provisionFromToken("uid-other", "other@example.com");
        String token = "fcm-token-owned";
        deviceTokenService.register("uid-owner", token, Platform.IOS);

        // A different signed-in user cannot remove someone else's device: no-op → false,
        // and the token is still present, still owned by the original user.
        assertThat(deviceTokenService.deregister("uid-other", token)).isFalse();
        var stillThere = deviceTokenRepository.findByToken(token).orElseThrow();
        assertThat(stillThere.getUserId()).isEqualTo(owner.getId());

        // The owner can remove it → true, and it is gone.
        assertThat(deviceTokenService.deregister("uid-owner", token)).isTrue();
        assertThat(deviceTokenRepository.findByToken(token)).isEmpty();
    }

    @Test
    void findByUserIdListsAllOfAUsersDevices() {
        User user = userService.provisionFromToken("uid-multi", "multi@example.com");
        deviceTokenService.register("uid-multi", "fcm-multi-a", Platform.ANDROID);
        deviceTokenService.register("uid-multi", "fcm-multi-b", Platform.WEB);

        // The fan-out lookup the send-push service uses returns every device for the user.
        assertThat(deviceTokenRepository.findByUserId(user.getId()))
                .extracting(DeviceToken::getToken)
                .containsExactlyInAnyOrder("fcm-multi-a", "fcm-multi-b");
    }

    @Test
    void registerForUnknownUserThrowsNotFound() {
        // Defensive branch: if no users row resolves for the uid, registration is a 404
        // rather than a foreign-key violation. (In production the controller provisions
        // first, so this is not reached for an authenticated caller.)
        assertThatThrownBy(() -> deviceTokenService.register(
                        "uid-nonexistent-" + UUID.randomUUID(), "orphan-token", Platform.ANDROID))
                .isInstanceOf(NotFoundException.class);
    }
}
