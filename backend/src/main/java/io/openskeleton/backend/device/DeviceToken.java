package io.openskeleton.backend.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA entity mapped to the {@code device_tokens} table created by
 * {@code V5__create_device_tokens.sql} (OSK-154) — one row per push registration token
 * a user's device has registered with the backend.
 *
 * <p><b>Why a plain entity and NOT {@code common.BaseEntity}:</b> {@code BaseEntity}
 * folds in {@code deleted_at} (soft delete) and a {@code @Version} column, which the
 * {@code device_tokens} table deliberately does not have — a stale push token is
 * physically pruned, not soft-deleted, and there is no optimistic-concurrency need on a
 * token binding. Because Hibernate runs in {@code validate} mode (see
 * {@code application.yml}), extending {@code BaseEntity} here would demand
 * {@code deleted_at}/{@code version} columns that the migration intentionally omits and
 * would fail context startup. So this entity carries only its own columns and manages
 * the two audit timestamps itself via the lifecycle callbacks below — the same pattern
 * {@code AuditEvent} uses for its single timestamp.
 *
 * <p><b>Mapping notes (each pairs with a column in the migration):</b>
 * <ul>
 *   <li>{@code id} — a UUID surrogate key. {@link GenerationType#UUID} makes Hibernate
 *       assign the value application-side before insert; the column's
 *       {@code DEFAULT gen_random_uuid()} remains only as an out-of-band safety net.</li>
 *   <li>{@code userId} — the owning user's id (FK to {@code users.id}). Stored as a plain
 *       {@code UUID} rather than a {@code @ManyToOne} association: the service only ever
 *       needs to set/read the owner id (it resolves the {@code User} separately by
 *       Firebase UID), so a value column keeps the mapping simple and sidesteps the
 *       {@code @SQLRestriction} on {@code User}. It is mutable so an upsert can rebind a
 *       re-registered token to whichever caller now owns the device.</li>
 *   <li>{@code token} — the unique push token the row is keyed by; the UNIQUE constraint
 *       is what makes registration idempotent (re-registering updates in place).</li>
 *   <li>{@code platform} — persisted as the {@link Platform} enum <i>name</i>
 *       ({@link EnumType#STRING}) so the stored value is stable/readable, matching the
 *       {@code TEXT} column (mirrors {@code User.Role}).</li>
 *   <li>{@code createdAt}/{@code updatedAt} — stamped by {@link #onCreate()} /
 *       {@link #onUpdate()} so the app supplies the values rather than relying on the
 *       column {@code DEFAULT now()}; {@code createdAt} is write-once
 *       ({@code updatable = false}) and {@code updatedAt} advances on every upsert.</li>
 * </ul>
 *
 * <p>Plain Java (explicit constructor/getters/setters) is used rather than Lombok,
 * matching {@code User}/{@code AuditEvent}: Lombok is not a project dependency. A class
 * (not a record) is required because JPA needs a no-arg constructor and mutable fields.
 */
@Entity
@Table(name = "device_tokens")
public class DeviceToken {

    /**
     * The device platform a token belongs to. Kept to the three client surfaces the
     * platform targets; persisted by name ({@link EnumType#STRING}) so adding values
     * later is backwards-safe and the send-push service can pick a transport per value.
     */
    public enum Platform {
        ANDROID,
        IOS,
        WEB
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token", nullable = false, unique = true)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false)
    private Platform platform;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** No-arg constructor required by JPA/Hibernate. */
    protected DeviceToken() {}

    /**
     * Create a new device-token binding to be inserted. The {@code id} is generated on
     * persist ({@link GenerationType#UUID}) and the timestamps are stamped by
     * {@link #onCreate()}, so callers supply only the owner, token and platform.
     *
     * @param userId the owning user's id (never {@code null})
     * @param token the push registration token (never {@code null}/blank)
     * @param platform the platform the token is for (never {@code null})
     */
    public DeviceToken(UUID userId, String token, Platform platform) {
        this.userId = userId;
        this.token = token;
        this.platform = platform;
    }

    /**
     * Stamp both audit timestamps immediately before the first INSERT, so the app
     * supplies non-null values rather than depending on the column defaults.
     */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Advance {@code updatedAt} on every UPDATE (i.e. when a token is re-registered). */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public Platform getPlatform() {
        return platform;
    }

    public void setPlatform(Platform platform) {
        this.platform = platform;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
