package io.openskeleton.backend.user;

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
 * JPA entity mapped to the {@code users} table created by
 * {@code V2__create_users.sql} (OSK-60) — the Epic-2 data-layer root.
 *
 * <p>This is deliberately a <b>plain data mapping only</b>: there is no business
 * logic here (JIT provisioning is OSK-76, RBAC is OSK-71). Its job is to mirror the
 * Flyway-built schema exactly so that Hibernate — which runs in {@code validate}
 * mode (see {@code application.yml}) — asserts the mapping matches the real Postgres
 * schema on startup and fails fast on any drift.
 *
 * <p><b>Mapping notes (each pairs with a column in the migration):</b>
 * <ul>
 *   <li>{@code id} — a UUID surrogate key. {@link GenerationType#UUID} makes Hibernate
 *       assign the value application-side before insert; the column's
 *       {@code DEFAULT gen_random_uuid()} remains only as an out-of-band safety net.</li>
 *   <li>{@code firebaseUid} — the unique, non-null Firebase identity the app looks users
 *       up by ({@link UserRepository#findByFirebaseUid(String)}).</li>
 *   <li>{@code role} — persisted as its enum <i>name</i> ({@link EnumType#STRING}) so the
 *       stored value is stable and human-readable, matching the {@code TEXT} column.</li>
 *   <li>{@code createdAt}/{@code updatedAt} — set by the {@link #onCreate()} /
 *       {@link #onUpdate()} lifecycle callbacks so the app never relies on the DB
 *       default (which would require omitting the column from the INSERT).</li>
 * </ul>
 *
 * <p>Plain Java (explicit constructors/getters/setters) is used rather than Lombok:
 * Lombok is not a project dependency and this root ticket deliberately introduces no
 * new one. A class (not a record) is required because JPA needs a no-arg constructor
 * and mutable, proxyable fields.
 */
@Entity
@Table(name = "users")
public class User {

    /**
     * Coarse-grained authorization role. Kept intentionally tiny for now — the two
     * values the platform needs at the data layer — with real RBAC enforcement to
     * come in OSK-71. Persisted by name, so adding values later is backwards-safe.
     */
    public enum Role {
        USER,
        ADMIN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "firebase_uid", nullable = false, unique = true)
    private String firebaseUid;

    @Column(name = "email")
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Role role = Role.USER;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** No-arg constructor required by JPA/Hibernate. */
    public User() {}

    /**
     * Convenience constructor for the common case of provisioning a user from a
     * verified Firebase token. {@code role} defaults to {@link Role#USER} and
     * {@code enabled} to {@code true}; the timestamps are populated on persist.
     */
    public User(String firebaseUid, String email, String displayName) {
        this.firebaseUid = firebaseUid;
        this.email = email;
        this.displayName = displayName;
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

    /** Advance {@code updatedAt} on every UPDATE. */
    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getFirebaseUid() {
        return firebaseUid;
    }

    public void setFirebaseUid(String firebaseUid) {
        this.firebaseUid = firebaseUid;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
