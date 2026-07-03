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
import java.time.Instant;
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

    // ---------------------------------------------------------------------------------
    // Account-lifecycle fields (OSK-69). State a caller records on themselves through the
    // /me surface (mark onboarding complete, accept terms, mark age-verified). The two
    // booleans are NOT NULL (every row has a well-defined state, defaulting false); the
    // terms pair is NULLABLE (unset until the first acceptance). Each column pairs with V8;
    // Hibernate runs in `validate` mode, so the types/nullability MUST match the migration.
    // ---------------------------------------------------------------------------------

    /**
     * Whether the caller has completed the first-run onboarding flow. Defaults to
     * {@code false} so a freshly JIT-provisioned user (OSK-76) starts "not onboarded"
     * until the client marks it complete via {@code PATCH /api/v1/me/lifecycle}. NOT NULL,
     * mirroring the {@code enabled} boolean.
     */
    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted = false;

    /**
     * The version string of the terms/policy the user accepted (free text, e.g. {@code v1.0}),
     * or {@code null} if they have never accepted any terms. Set together with
     * {@link #termsAcceptedAt} whenever terms are accepted; the version is the meaningful,
     * queryable record of <i>what</i> was accepted.
     */
    @Column(name = "terms_accepted_version")
    private String termsAcceptedVersion;

    /**
     * When the terms acceptance was recorded, as a <b>server</b> timestamp (never
     * client-supplied), or {@code null} until the first acceptance. Stored as
     * {@code timestamptz} to match this {@link Instant} mapping, like the {@code BaseEntity}
     * timestamps.
     */
    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    /**
     * Whether the caller's age has been verified. Defaults to {@code false} and is NOT NULL,
     * same shape as {@link #onboardingCompleted}; set via {@code PATCH /api/v1/me/lifecycle}.
     */
    @Column(name = "age_verified", nullable = false)
    private boolean ageVerified = false;

    /**
     * The backend's own "last seen active" heartbeat (OSK-73), stamped (throttled) on every
     * authenticated {@code /api/v1/**} request by {@link LastActiveService}. NULLABLE: a freshly
     * JIT-provisioned user (OSK-76) and every pre-V9 row start {@code null} ("not yet observed
     * active") until the first stamp lands. Distinct from Firebase's {@code lastLoginAt} (which
     * only advances on a fresh sign-in) — this tracks activity, not authentication. Pairs with the
     * {@code last_active_at TIMESTAMPTZ NULL} column added by {@code V9}; Hibernate runs in
     * {@code validate} mode, so the type/nullability MUST match the migration exactly.
     */
    @Column(name = "last_active_at")
    private Instant lastActiveAt;

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

    public boolean isOnboardingCompleted() {
        return onboardingCompleted;
    }

    public void setOnboardingCompleted(boolean onboardingCompleted) {
        this.onboardingCompleted = onboardingCompleted;
    }

    public String getTermsAcceptedVersion() {
        return termsAcceptedVersion;
    }

    public void setTermsAcceptedVersion(String termsAcceptedVersion) {
        this.termsAcceptedVersion = termsAcceptedVersion;
    }

    public Instant getTermsAcceptedAt() {
        return termsAcceptedAt;
    }

    public void setTermsAcceptedAt(Instant termsAcceptedAt) {
        this.termsAcceptedAt = termsAcceptedAt;
    }

    public boolean isAgeVerified() {
        return ageVerified;
    }

    public void setAgeVerified(boolean ageVerified) {
        this.ageVerified = ageVerified;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Instant lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }
}
