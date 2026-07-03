package io.openskeleton.backend.user;

import io.openskeleton.backend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.SQLRestriction;

/**
 * JPA entity mapped to the {@code users} table created by
 * {@code V2__create_users.sql} (OSK-60) and extended with soft-delete + optimistic
 * concurrency by {@code V3__add_soft_delete_and_version_to_users.sql} (OSK-84).
 *
 * <p>This is deliberately a <b>plain data mapping only</b>: there is no business
 * logic here (JIT provisioning is OSK-76, RBAC is OSK-71) — the reusable behaviour it
 * needs (audit timestamps, soft-delete marker, {@code @Version}) is inherited from
 * {@link BaseEntity}, so {@code User} only declares its own fields. Its job is to
 * mirror the Flyway-built schema exactly so that Hibernate — which runs in
 * {@code validate} mode (see {@code application.yml}) — asserts the mapping matches the
 * real Postgres schema on startup and fails fast on any drift.
 *
 * <p><b>Default soft-delete exclusion:</b> the class-level
 * {@link SQLRestriction @SQLRestriction("deleted_at is null")} appends
 * {@code deleted_at is null} to every SELECT Hibernate generates for this entity
 * (finder queries, {@code findById}, {@code findAll}, associations), so soft-deleted
 * users are hidden from normal reads without each query having to remember the filter.
 * The restore path deliberately bypasses it with a native query
 * ({@link UserRepository#findByIdIncludingDeleted(UUID)}) so a deleted row can be
 * located and reactivated.
 *
 * <p><b>Mapping notes (each pairs with a column in the migration):</b>
 * <ul>
 *   <li>{@code id} — a UUID surrogate key. {@link GenerationType#UUID} makes Hibernate
 *       assign the value application-side before insert; the column's
 *       {@code DEFAULT gen_random_uuid()} remains only as an out-of-band safety net.
 *       Declared here (not in {@link BaseEntity}) because id type/generation is
 *       entity-specific.</li>
 *   <li>{@code firebaseUid} — the unique, non-null Firebase identity the app looks users
 *       up by ({@link UserRepository#findByFirebaseUid(String)}).</li>
 *   <li>{@code role} — persisted as its enum <i>name</i> ({@link EnumType#STRING}) so the
 *       stored value is stable and human-readable, matching the {@code TEXT} column.</li>
 *   <li>{@code accountType} — REAL vs TEST classification (OSK-168), persisted by name like
 *       {@code role}; defaults to {@link AccountType#REAL} (see {@code V6}).</li>
 * </ul>
 *
 * <p>Plain Java (explicit constructors/getters/setters) is used rather than Lombok:
 * Lombok is not a project dependency and this root ticket deliberately introduces no
 * new one. A class (not a record) is required because JPA needs a no-arg constructor
 * and mutable, proxyable fields.
 */
@Entity
@Table(name = "users")
@SQLRestriction("deleted_at is null")
public class User extends BaseEntity {

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

    /**
     * Whether this is a genuine end-user account or a test/synthetic one (OSK-168).
     * Persisted by name, matching {@code role}. Defaults to {@link AccountType#REAL} so a
     * freshly provisioned user is a real account unless a caller explicitly marks it
     * {@link AccountType#TEST} via {@link UserService#markAccountType(String, AccountType)};
     * no email-based auto-classification happens here.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType = AccountType.REAL;

    // ---------------------------------------------------------------------------------
    // Self-service profile fields (OSK-67). All OPTIONAL decoration a caller sets on
    // itself via PATCH /api/v1/me, so every one is NULLABLE and a freshly JIT-provisioned
    // user (OSK-76) leaves them null until filled in — the constructor below never sets
    // them, keeping first-login upsert working. Each column pairs with V7; Hibernate runs
    // in `validate` mode, so the types/nullability MUST match the migration exactly.
    // ---------------------------------------------------------------------------------

    /** The caller's given name (independent of {@code displayName}). */
    @Column(name = "first_name")
    private String firstName;

    /** The caller's family name (independent of {@code displayName}). */
    @Column(name = "last_name")
    private String lastName;

    /** Free-text city. */
    @Column(name = "city")
    private String city;

    /**
     * Integer age. A boxed {@link Integer} (not primitive) because the column is nullable —
     * {@code null} means "not provided". The sane-range check (13..120) is enforced in the
     * API layer (bean validation), not at this mapping, since the range is product policy.
     */
    @Column(name = "age")
    private Integer age;

    /** Free-text phone number; validated leniently (non-empty) at the API boundary. */
    @Column(name = "phone")
    private String phone;

    /**
     * The channel the platform should use to reach this user. Persisted by name like
     * {@code role}/{@code accountType}; defaults to {@link NotificationPreference#EMAIL} so
     * every user (existing rows and freshly provisioned ones) has a well-defined channel —
     * matching the {@code NOT NULL DEFAULT 'EMAIL'} column in V7.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "notification_preference", nullable = false)
    private NotificationPreference notificationPreference = NotificationPreference.EMAIL;

    /** IANA time-zone id (e.g. {@code Europe/London}); validated at the API boundary. */
    @Column(name = "timezone")
    private String timezone;

    /** BCP-47 language tag (e.g. {@code en-GB}); validated at the API boundary. */
    @Column(name = "locale")
    private String locale;

    /** No-arg constructor required by JPA/Hibernate. */
    public User() {}

    /**
     * Convenience constructor for the common case of provisioning a user from a
     * verified Firebase token. {@code role} defaults to {@link Role#USER} and
     * {@code enabled} to {@code true}; the timestamps are populated on persist by
     * {@link BaseEntity}.
     */
    public User(String firebaseUid, String email, String displayName) {
        this.firebaseUid = firebaseUid;
        this.email = email;
        this.displayName = displayName;
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

    public AccountType getAccountType() {
        return accountType;
    }

    public void setAccountType(AccountType accountType) {
        this.accountType = accountType;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public Integer getAge() {
        return age;
    }

    public void setAge(Integer age) {
        this.age = age;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public NotificationPreference getNotificationPreference() {
        return notificationPreference;
    }

    public void setNotificationPreference(NotificationPreference notificationPreference) {
        this.notificationPreference = notificationPreference;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }
}
