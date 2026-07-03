package io.openskeleton.backend.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openskeleton.backend.audit.AuditAction;
import io.openskeleton.backend.audit.AuditService;
import io.openskeleton.backend.user.User.Role;
import io.openskeleton.backend.web.NotFoundException;
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
    void updateProfileSetsDisplayNamePersistsAndRecordsExactlyOneHistoryEvent() {
        User user = new User("uid-p", "p@example.com", null);
        UUID userId = UUID.randomUUID();
        user.setId(userId); // the audit target_id is the persisted user's id
        when(userRepository.findByFirebaseUid("uid-p")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        // First-ever set: null → "New Name" (exercises the null old-value path too).
        User result = userService.updateProfile("uid-p", "New Name");

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

        User result = userService.updateProfile("uid-same", "Stable Name");

        assertThat(result).isSameAs(user);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void updateProfileThrowsNotFoundWhenUserMissing() {
        when(userRepository.findByFirebaseUid("uid-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateProfile("uid-missing", "x")).isInstanceOf(NotFoundException.class);
        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService);
    }
}
