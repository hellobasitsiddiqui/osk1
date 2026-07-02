package io.openskeleton.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import io.openskeleton.backend.user.User.Role;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RoleService} admin seam (OSK-79). The Admin SDK
 * ({@link FirebaseAuth}) is mocked, so no live Firebase project/credentials are needed:
 * we assert the seam writes the {@code role} custom claim with the exact key/value the
 * read side ({@link RoleClaimMapper}) recognises, guards its inputs, and wraps SDK
 * failures cleanly.
 */
class RoleServiceTest {

    @Test
    void setRoleWritesAdminClaimViaAdminSdk() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        doNothing().when(firebaseAuth).setCustomUserClaims(anyString(), anyMap());
        RoleService service = new RoleService(firebaseAuth);

        service.setRole("uid-admin", Role.ADMIN);

        // The claim is written under the shared ROLE_CLAIM key as the enum name, which
        // is precisely what RoleClaimMapper maps back to ROLE_ADMIN on the next token.
        verify(firebaseAuth).setCustomUserClaims("uid-admin", Map.of(RoleClaimMapper.ROLE_CLAIM, "ADMIN"));
    }

    @Test
    void setRoleWritesUserClaimViaAdminSdk() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        doNothing().when(firebaseAuth).setCustomUserClaims(anyString(), anyMap());
        RoleService service = new RoleService(firebaseAuth);

        service.setRole("uid-user", Role.USER);

        verify(firebaseAuth).setCustomUserClaims("uid-user", Map.of(RoleClaimMapper.ROLE_CLAIM, "USER"));
    }

    @Test
    void blankUidIsRejectedWithoutTouchingTheSdk() {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        RoleService service = new RoleService(firebaseAuth);

        assertThatThrownBy(() -> service.setRole("  ", Role.ADMIN)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(firebaseAuth);
    }

    @Test
    void nullUidIsRejectedWithoutTouchingTheSdk() {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        RoleService service = new RoleService(firebaseAuth);

        assertThatThrownBy(() -> service.setRole(null, Role.ADMIN)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(firebaseAuth);
    }

    @Test
    void nullRoleIsRejectedWithoutTouchingTheSdk() {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        RoleService service = new RoleService(firebaseAuth);

        assertThatThrownBy(() -> service.setRole("uid-1", null)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(firebaseAuth);
    }

    @Test
    void adminSdkFailureIsWrappedInRoleAssignmentException() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        FirebaseAuthException sdkFailure = mock(FirebaseAuthException.class);
        doThrow(sdkFailure).when(firebaseAuth).setCustomUserClaims(anyString(), anyMap());
        RoleService service = new RoleService(firebaseAuth);

        assertThatThrownBy(() -> service.setRole("uid-9", Role.ADMIN))
                .isInstanceOf(RoleAssignmentException.class)
                .hasCause(sdkFailure);
    }

    @Test
    void nullFirebaseAuthIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new RoleService(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void setRoleReturnsNormallyOnSuccess() throws Exception {
        FirebaseAuth firebaseAuth = mock(FirebaseAuth.class);
        doNothing().when(firebaseAuth).setCustomUserClaims(anyString(), anyMap());
        RoleService service = new RoleService(firebaseAuth);

        // No exception == success; nothing else to assert beyond the interaction above.
        service.setRole("uid-ok", Role.ADMIN);

        assertThat(service).isNotNull();
    }
}
