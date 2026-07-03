package io.openskeleton.backend.device;

import io.openskeleton.backend.device.DeviceToken.Platform;
import io.openskeleton.backend.user.User;
import io.openskeleton.backend.user.UserRepository;
import io.openskeleton.backend.web.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service for device-token registration and lifecycle (OSK-154).
 *
 * <p>It owns the three operations the push stack needs, behind one seam so the
 * controller (and the later send-push service) do not touch the repository directly:
 *
 * <ul>
 *   <li><b>{@link #register(String, String, Platform) register}</b> — upsert-by-token for
 *       the caller. Idempotent per token: registering the SAME token again updates the
 *       existing row (platform + owner + {@code updatedAt}) instead of creating a
 *       duplicate, which the {@code token} UNIQUE constraint also enforces at the DB.</li>
 *   <li><b>{@link #deregister(String, String) deregister}</b> — the sign-out path. Removes
 *       the token ONLY if the caller owns it, and is idempotent (removing an absent or
 *       not-owned token is a no-op returning {@code false}).</li>
 *   <li><b>{@link #prune(String) prune}</b> — drop a token regardless of owner, for the
 *       send-push service to call when a provider reports the token as stale/invalid.</li>
 * </ul>
 *
 * <p><b>Identity comes only from the verified token.</b> Callers pass the caller's
 * Firebase UID (which the controller reads from the auth filter's request attribute,
 * never from the body); this service resolves it to the persisted {@link User} to obtain
 * the {@code user_id} foreign key. A missing user is a {@link NotFoundException} (404) —
 * though in practice the controller provisions the {@code users} row first (JIT, OSK-76),
 * so an authenticated caller always resolves.
 */
@Service
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;

    public DeviceTokenService(DeviceTokenRepository deviceTokenRepository, UserRepository userRepository) {
        this.deviceTokenRepository = deviceTokenRepository;
        this.userRepository = userRepository;
    }

    /**
     * Register (upsert) a push token for the caller and return the persisted binding.
     *
     * <p><b>Idempotent per token (AC-1):</b> if the token is already registered the
     * existing row is updated in place — its {@code platform} refreshed and its owner
     * rebound to the current caller (a device handed to a new signed-in user re-points to
     * them), with {@code updatedAt} advanced — so re-registration never inserts a
     * duplicate. Otherwise a fresh binding is inserted. Runs in one transaction so the
     * read-then-write is atomic.
     *
     * @param firebaseUid the caller's verified Firebase UID (from the token, never the body)
     * @param token the push registration token to upsert (never {@code null}/blank)
     * @param platform the platform the token is for
     * @return the persisted device token (created or updated)
     * @throws NotFoundException if no user exists for {@code firebaseUid}
     */
    @Transactional
    public DeviceToken register(String firebaseUid, String token, Platform platform) {
        UUID userId = resolveUserId(firebaseUid);
        return deviceTokenRepository
                .findByToken(token)
                .map(existing -> {
                    // Re-registration of a known token: update in place (idempotent),
                    // rebinding the owner and refreshing the platform. @PreUpdate advances
                    // updatedAt on the save.
                    existing.setUserId(userId);
                    existing.setPlatform(platform);
                    return deviceTokenRepository.save(existing);
                })
                .orElseGet(() -> deviceTokenRepository.save(new DeviceToken(userId, token, platform)));
    }

    /**
     * Deregister a token on sign-out/invalidation (AC-2), scoped to the caller.
     *
     * <p>Only removes the token if the caller owns it, so a sign-out on one account can
     * never delete another account's device binding. Idempotent: deleting a token that is
     * already gone (or is owned by a different user) is a no-op that returns {@code false}
     * rather than raising — repeated sign-outs are safe.
     *
     * @param firebaseUid the caller's verified Firebase UID
     * @param token the token to remove
     * @return {@code true} if a token owned by the caller was removed, {@code false} if
     *     there was nothing to remove
     * @throws NotFoundException if no user exists for {@code firebaseUid}
     */
    @Transactional
    public boolean deregister(String firebaseUid, String token) {
        UUID userId = resolveUserId(firebaseUid);
        return deviceTokenRepository.deleteByTokenAndUserId(token, userId) > 0;
    }

    /**
     * Prune a stale/invalid token regardless of owner (AC-4) — the delete-by-token seam
     * the send-push service calls when a push provider rejects a token. Idempotent:
     * returns {@code false} when the token was already absent.
     *
     * @param token the token to drop
     * @return {@code true} if a token was removed, {@code false} if none existed
     */
    @Transactional
    public boolean prune(String token) {
        return deviceTokenRepository.deleteByToken(token) > 0;
    }

    /** Resolve the caller's persisted user id from their Firebase UID, or 404 if absent. */
    private UUID resolveUserId(String firebaseUid) {
        User user = userRepository
                .findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new NotFoundException("No active user with firebaseUid " + firebaseUid));
        return user.getId();
    }
}
