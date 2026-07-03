package io.openskeleton.backend.device;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link DeviceToken} (OSK-154).
 *
 * <p>The id type is {@link UUID} to match the entity's UUID primary key. Beyond the
 * standard CRUD, the domain needs three lookups keyed by the natural identity of a
 * token binding:
 *
 * <ul>
 *   <li>{@link #findByToken(String)} — the by-token lookup the upsert uses to decide
 *       "insert new vs update existing" (the {@code token} UNIQUE column backs it).</li>
 *   <li>{@link #deleteByTokenAndUserId(String, UUID)} — the caller-scoped deregister:
 *       only the owning user can remove their own token, so sign-out on one account
 *       cannot delete another account's device. Returns the number of rows removed so
 *       the caller can tell "did anything match" without a prior read.</li>
 *   <li>{@link #deleteByToken(String)} — the unscoped prune used by the (later)
 *       send-push service to drop a token a provider has reported as stale/invalid,
 *       regardless of which user owns it. Also returns the deleted count.</li>
 * </ul>
 *
 * <p>The derived {@code deleteBy...} methods are implicitly modifying queries, so their
 * callers ({@link DeviceTokenService}) run them inside a transaction. {@code findByUserId}
 * is provided for the send-push fan-out that lists a user's devices.
 */
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    /**
     * Find the single token binding for a push token, if one exists. Spring Data derives
     * the query against the {@code token} UNIQUE column, so it returns at most one row.
     */
    Optional<DeviceToken> findByToken(String token);

    /** All tokens registered by a given user — the send-push fan-out list for that user. */
    List<DeviceToken> findByUserId(UUID userId);

    /**
     * Delete the token binding for {@code token} only if it is owned by {@code userId}
     * (the caller-scoped deregister). Returns the number of rows deleted: {@code 1} when
     * the caller owned the token and it was removed, {@code 0} when no such token exists
     * for that caller (already gone, or owned by someone else) — letting the service
     * treat a repeated sign-out as an idempotent no-op rather than an error.
     */
    long deleteByTokenAndUserId(String token, UUID userId);

    /**
     * Delete the token binding for {@code token} regardless of owner — the prune path for
     * a stale/invalid token reported by the push provider. Returns the number of rows
     * deleted ({@code 1} if the token existed, {@code 0} otherwise).
     */
    long deleteByToken(String token);
}
