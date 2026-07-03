package io.openskeleton.backend.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LastActiveService} — the throttled last-active heartbeat (OSK-73). The
 * {@link UserRepository} is a Mockito mock so we can assert exactly when the (throttled) UPDATE is
 * and isn't issued, covering the in-process throttle branches.
 */
class LastActiveServiceTest {

    private static final String UID = "uid-123";

    @Test
    void nullUidIsANoOp() {
        UserRepository repo = mock(UserRepository.class);

        new LastActiveService(repo).touch(null);

        verifyNoInteractions(repo);
    }

    @Test
    void firstTouchIssuesTheThrottledUpdate() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.touchLastActive(eq(UID), any(Instant.class), any(Instant.class)))
                .thenReturn(1);

        new LastActiveService(repo).touch(UID);

        verify(repo).touchLastActive(eq(UID), any(Instant.class), any(Instant.class));
    }

    @Test
    void secondTouchWithinWindowSkipsTheDatabase() {
        UserRepository repo = mock(UserRepository.class);
        when(repo.touchLastActive(eq(UID), any(Instant.class), any(Instant.class)))
                .thenReturn(1);
        LastActiveService service = new LastActiveService(repo);

        service.touch(UID); // stamps + caches
        service.touch(UID); // within the throttle window → in-process skip

        // Only the first call reached the database.
        verify(repo, times(1)).touchLastActive(eq(UID), any(Instant.class), any(Instant.class));
    }

    @Test
    void doesNotCacheAMissSoTheNextTouchRetries() {
        UserRepository repo = mock(UserRepository.class);
        // 0 rows = the row was absent (e.g. not provisioned yet) or already fresh in another instance;
        // this must NOT be cached, so the next request tries again rather than being suppressed.
        when(repo.touchLastActive(eq(UID), any(Instant.class), any(Instant.class)))
                .thenReturn(0);
        LastActiveService service = new LastActiveService(repo);

        service.touch(UID);
        service.touch(UID);

        verify(repo, times(2)).touchLastActive(eq(UID), any(Instant.class), any(Instant.class));
    }

    @Test
    void neverStampsWhenThrottledInMemory() {
        // Guards the "skip" branch is a true no-op on the repo, not just fewer writes.
        UserRepository repo = mock(UserRepository.class);
        when(repo.touchLastActive(eq(UID), any(Instant.class), any(Instant.class)))
                .thenReturn(1);
        LastActiveService service = new LastActiveService(repo);

        service.touch(UID);
        service.touch(UID);
        service.touch(UID);

        verify(repo, times(1)).touchLastActive(eq(UID), any(Instant.class), any(Instant.class));
        verify(repo, never()).save(any());
    }
}
