package io.openskeleton.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openskeleton.backend.audit.AuditAction;
import io.openskeleton.backend.audit.AuditService;
import io.openskeleton.backend.user.User.Role;
import io.openskeleton.backend.web.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Fast, deterministic unit tests for the OSK-76 provisioning + profile logic in
 * {@link UserService}, with the repository mocked so every branch — including the
 * unique-constraint race — is exercised without timing dependence.
 *
 * <p>The mock is what makes the race branch <i>deterministic</i>: a live concurrent test
 * (see {@code MeProvisioningIntegrationTest}) proves no duplicate row is ever created,
 * but cannot guarantee the losing insert path is taken on any given run. Here we simulate
 * the loser directly — {@code findByFirebaseUid} empty, then {@code saveAndFlush} throws
 * {@link DataIntegrityViolationException}, then the recovery re-read finds the winner — so
 * the catch/recover branch is always covered.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceProvisioningTest {

    @Mock
    private UserRepository userRepository;

    // The OSK-99 change-recording seam. Mocked so we can assert the history event is
    // appended exactly on a real change (and never on a no-op) without a database.
    @Mock
    private AuditService auditService;

    @InjectMocks
    private UserService userService;

    @Test
    void provisionCreatesNewUserWithLeastPrivilegeDefaultsWhenNoneExists() {
        when(userRepository.findByFirebaseUid("uid-new")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.provisionFromToken("uid-new", "new@example.com");

        // Inserted with the token identity and the safe defaults; displayName starts null.
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User inserted = captor.getValue();
        assertThat(inserted.getFirebaseUid()).isEqualTo("uid-new");
        assertThat(inserted.getEmail()).isEqualTo("new@example.com");
        assertThat(inserted.getDisplayName()).isNull();
        assertThat(inserted.getRole()).isEqualTo(Role.USER);
        assertThat(inserted.isEnabled()).isTrue();
        // OSK-168 AC-4: a freshly provisioned account is REAL by default (no auto-classify).
        assertThat(inserted.getAccountType()).isEqualTo(AccountType.REAL);
        assertThat(result).isSameAs(inserted);
    }

    @Test
    void provisionReturnsExistingUserWithoutInserting() {
        User existing = new User("uid-existing", "e@example.com", "Existing");
        when(userRepository.findByFirebaseUid("uid-existing")).thenReturn(Optional.of(existing));

        User result = userService.provisionFromToken("uid-existing", "e@example.com");

        assertThat(result).isSameAs(existing);
        // Steady-state path never writes.
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void provisionRaceOnInsertReReadsAndReturnsTheWinningRow() {
        User winner = new User("uid-race", "race@example.com", null);
        // Both racers see "not found"; our insert loses and trips the UNIQUE constraint;
        // the recovery re-read then finds the row the winner committed.
        when(userRepository.findByFirebaseUid("uid-race"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        User result = userService.provisionFromToken("uid-race", "race@example.com");

        // Resolved to "found": the loser returns the winner's row — no duplicate, no 500.
        assertThat(result).isSameAs(winner);
    }

    @Test
    void provisionRethrowsWhenIntegrityViolationIsNotTheUidRace() {
        // A DataIntegrityViolationException after which the row is STILL absent is not the
        // uid race (some other constraint): we must not fabricate a result — rethrow.
        when(userRepository.findByFirebaseUid("uid-broken"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        DataIntegrityViolationException boom = new DataIntegrityViolationException("some other constraint");
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(boom);

        assertThatThrownBy(() -> userService.provisionFromToken("uid-broken", null))
                .isSameAs(boom);
    }

    @Test
    void provisionReconcilesByEmailAndAdoptsTheNewUidOntoTheExistingAccount() {
        // OSK-163 linking policy: a brand-new uid presents an email that already belongs to an
        // account. Provisioning must resolve to that SAME row (no insert) and adopt the new uid
        // onto it, so every downstream firebase_uid lookup keeps working for the current session.
        User existing = new User("uid-old", "shared@example.com", "Shared");
        existing.setId(UUID.randomUUID());
        when(userRepository.findByFirebaseUid("uid-new")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("shared@example.com")).thenReturn(Optional.of(existing));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.provisionFromToken("uid-new", "shared@example.com");

        // Same row, new uid adopted — and the ONLY write is the relink save of that existing row
        // (no fresh User was inserted).
        assertThat(result).isSameAs(existing);
        assertThat(result.getFirebaseUid()).isEqualTo("uid-new");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
    }

    @Test
    void provisionReconcilesByPhoneWhenEmailIsAbsent() {
        // Phone-only sign-in (no email on the token): reconcile by the phone identity instead,
        // adopting the new uid onto the account that already owns that phone.
        User existing = new User("uid-old-p", null, null);
        existing.setId(UUID.randomUUID());
        existing.setPhone("+15551230000");
        when(userRepository.findByFirebaseUid("uid-new-p")).thenReturn(Optional.empty());
        when(userRepository.findByPhone("+15551230000")).thenReturn(Optional.of(existing));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.provisionFromToken("uid-new-p", null, "+15551230000");

        assertThat(result).isSameAs(existing);
        assertThat(result.getFirebaseUid()).isEqualTo("uid-new-p");
        verify(userRepository).saveAndFlush(existing);
        // Email was null, so the email lookup is never attempted (phone is the only identity).
        verify(userRepository, never()).findByEmailIgnoreCase(any());
    }

    @Test
    void provisionThreeArgInsertsNewUserCapturingPhoneWhenIdentityUnknown() {
        // Neither the uid nor the email/phone identity is known → genuine new user: insert,
        // capturing the token's phone so a LATER phone sign-in can reconcile back to this row.
        when(userRepository.findByFirebaseUid("uid-3")).thenReturn(Optional.empty());
        when(userRepository.findByEmailIgnoreCase("three@example.com")).thenReturn(Optional.empty());
        when(userRepository.findByPhone("+15559999999")).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.provisionFromToken("uid-3", "three@example.com", "+15559999999");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        User inserted = captor.getValue();
        assertThat(inserted.getFirebaseUid()).isEqualTo("uid-3");
        assertThat(inserted.getEmail()).isEqualTo("three@example.com");
        assertThat(inserted.getPhone()).isEqualTo("+15559999999");
        assertThat(result).isSameAs(inserted);
    }

    @Test
    void provisionRecoversFromIdentityRaceByRelinkingTheConcurrentlyCreatedRow() {
        // The identity race: we see neither the uid nor the identity and attempt an INSERT, but a
        // concurrent request has just created the identity under a DIFFERENT uid, so our insert
        // trips the email unique index. Recovery: re-read by uid (still empty), then by identity
        // (now visible) → relink the winner's row onto our uid. No duplicate, no surfaced 500.
        User winner = new User("uid-winner", "raced@example.com", null);
        winner.setId(UUID.randomUUID());
        when(userRepository.findByFirebaseUid("uid-loser"))
                .thenReturn(Optional.empty()) // step 1: not found by uid
                .thenReturn(Optional.empty()); // recovery: still not found by uid (our uid never landed)
        when(userRepository.findByEmailIgnoreCase("raced@example.com"))
                .thenReturn(Optional.empty()) // step 2: not yet visible (winner uncommitted)
                .thenReturn(Optional.of(winner)); // recovery: winner now committed and visible
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate email")) // our insert loses
                .thenAnswer(inv -> inv.getArgument(0)); // the relink save succeeds

        User result = userService.provisionFromToken("uid-loser", "raced@example.com");

        // Resolved to the single winning row, with our uid adopted onto it (linking policy).
        assertThat(result).isSameAs(winner);
        assertThat(result.getFirebaseUid()).isEqualTo("uid-loser");
    }

    @Test
    void updateProfileSetsDisplayNamePersistsAndRecordsExactlyOneHistoryEvent() {
        User user = new User("uid-p", "p@example.com", null);
        UUID userId = UUID.randomUUID();
        user.setId(userId); // the audit target_id is the persisted user's id
        when(userRepository.findByFirebaseUid("uid-p")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        // First-ever set: null → "New Name" (exercises the null old-value path too).
        User result = userService.updateProfile("uid-p", ProfileUpdate.ofDisplayName("New Name"));

        assertThat(result.getDisplayName()).isEqualTo("New Name");
        verify(userRepository).save(user);

        // AC-1: exactly one PROFILE_UPDATED event, actor = uid, target = the user id, with
        // {field, old, new} metadata carrying the true before/after (old is null here).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditService)
                .record(
                        eq("uid-p"),
                        eq(AuditAction.PROFILE_UPDATED),
                        eq(ProfileChange.TARGET_TYPE),
                        eq(userId.toString()),
                        metadata.capture());
        assertThat(metadata.getValue())
                .containsEntry("field", ProfileChange.FIELD_DISPLAY_NAME)
                .containsEntry("old", null)
                .containsEntry("new", "New Name");
    }

    @Test
    void updateProfileWithUnchangedValueIsANoOpAndRecordsNothing() {
        // Idempotent PATCH: the display name already equals the requested value, so there is
        // no write and no history event — history captures genuine edits, not every request.
        User user = new User("uid-same", "same@example.com", "Stable Name");
        when(userRepository.findByFirebaseUid("uid-same")).thenReturn(Optional.of(user));

        User result = userService.updateProfile("uid-same", ProfileUpdate.ofDisplayName("Stable Name"));

        assertThat(result).isSameAs(user);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void updateProfileThrowsNotFoundWhenUserMissing() {
        when(userRepository.findByFirebaseUid("uid-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateProfile("uid-missing", ProfileUpdate.ofDisplayName("x")))
                .isInstanceOf(NotFoundException.class);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void updateProfileAppliesEveryNewFieldAndRecordsOneHistoryEventPerChange() {
        // OSK-67: a single PATCH that sets displayName plus all eight new fields from their
        // defaults must apply each and record exactly ONE PROFILE_UPDATED event per field,
        // with correctly stringified before/after (age Integer, notificationPreference enum).
        User user = new User("uid-full", "full@example.com", null);
        UUID userId = UUID.randomUUID();
        user.setId(userId);
        when(userRepository.findByFirebaseUid("uid-full")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        ProfileUpdate update = new ProfileUpdate(
                "Display Name",
                "Ada",
                "Lovelace",
                "London",
                30,
                "+15551234567",
                NotificationPreference.PUSH,
                "Europe/London",
                "en-GB");

        User result = userService.updateProfile("uid-full", update);

        // Every field landed on the entity (a single save persisted them all).
        assertThat(result.getDisplayName()).isEqualTo("Display Name");
        assertThat(result.getFirstName()).isEqualTo("Ada");
        assertThat(result.getLastName()).isEqualTo("Lovelace");
        assertThat(result.getCity()).isEqualTo("London");
        assertThat(result.getAge()).isEqualTo(30);
        assertThat(result.getPhone()).isEqualTo("+15551234567");
        assertThat(result.getNotificationPreference()).isEqualTo(NotificationPreference.PUSH);
        assertThat(result.getTimezone()).isEqualTo("Europe/London");
        assertThat(result.getLocale()).isEqualTo("en-GB");
        verify(userRepository).save(user);

        // Nine genuine changes → nine history events, all for this actor + target.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);
        verify(auditService, times(9))
                .record(
                        eq("uid-full"),
                        eq(AuditAction.PROFILE_UPDATED),
                        eq(ProfileChange.TARGET_TYPE),
                        eq(userId.toString()),
                        metadata.capture());
        List<Map<String, Object>> recorded = metadata.getAllValues();
        // The non-String fields must be stringified in metadata: age -> "30", enum -> its name.
        assertThat(recorded)
                .anySatisfy(m -> assertThat(m)
                        .containsEntry("field", ProfileChange.FIELD_AGE)
                        .containsEntry("old", null)
                        .containsEntry("new", "30"))
                .anySatisfy(m -> assertThat(m)
                        .containsEntry("field", ProfileChange.FIELD_NOTIFICATION_PREFERENCE)
                        .containsEntry("old", "EMAIL")
                        .containsEntry("new", "PUSH"))
                .anySatisfy(m -> assertThat(m)
                        .containsEntry("field", ProfileChange.FIELD_LOCALE)
                        .containsEntry("new", "en-GB"));
    }

    @Test
    void updateProfileWithValuesEqualToCurrentIsANoOpAcrossAllFields() {
        // Every requested value already equals the entity's current value → no genuine change
        // on any field, so nothing is written and no history is recorded (the changed-vs-
        // unchanged branch, exercised for the new fields too).
        User user = new User("uid-noop", "noop@example.com", "Same");
        user.setFirstName("Ada");
        user.setLastName("Lovelace");
        user.setCity("London");
        user.setAge(30);
        user.setPhone("+15551234567");
        user.setNotificationPreference(NotificationPreference.EMAIL);
        user.setTimezone("Europe/London");
        user.setLocale("en-GB");
        when(userRepository.findByFirebaseUid("uid-noop")).thenReturn(Optional.of(user));

        User result = userService.updateProfile(
                "uid-noop",
                new ProfileUpdate(
                        "Same",
                        "Ada",
                        "Lovelace",
                        "London",
                        30,
                        "+15551234567",
                        NotificationPreference.EMAIL,
                        "Europe/London",
                        "en-GB"));

        assertThat(result).isSameAs(user);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void updateProfileWithAllNullComponentsIsANoOp() {
        // A ProfileUpdate whose components are all null is the "change nothing" case (the
        // requested == null branch for every field): no write, no history.
        User user = new User("uid-empty", "empty@example.com", "Keep");
        when(userRepository.findByFirebaseUid("uid-empty")).thenReturn(Optional.of(user));

        User result = userService.updateProfile(
                "uid-empty", new ProfileUpdate(null, null, null, null, null, null, null, null, null));

        assertThat(result).isSameAs(user);
        assertThat(result.getDisplayName()).isEqualTo("Keep"); // untouched
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void markAccountTypeUpdatesClassificationAndPersists() {
        // OSK-168 AC-3/AC-4: the setter seam flips REAL -> TEST and saves the change.
        User user = new User("uid-mark", "mark@example.com", null);
        assertThat(user.getAccountType()).isEqualTo(AccountType.REAL); // default before the call
        when(userRepository.findByFirebaseUid("uid-mark")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.markAccountType("uid-mark", AccountType.TEST);

        assertThat(result.getAccountType()).isEqualTo(AccountType.TEST);
        verify(userRepository).save(user);
    }

    @Test
    void markAccountTypeThrowsNotFoundWhenUserMissing() {
        // The caller is expected to already exist; a missing/soft-deleted row 404s and never
        // writes — the setter must not silently create a row.
        when(userRepository.findByFirebaseUid("uid-absent")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.markAccountType("uid-absent", AccountType.TEST))
                .isInstanceOf(NotFoundException.class);
        verify(userRepository, never()).save(any());
    }
}
