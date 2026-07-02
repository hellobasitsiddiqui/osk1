package io.openskeleton.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import io.openskeleton.backend.user.User.Role;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link VerifiedToken} principal (OSK-79): it must always expose a
 * concrete, least-privilege role so downstream authorization never has to null-check.
 */
class VerifiedTokenTest {

    @Test
    void twoArgConstructorDefaultsRoleToUser() {
        VerifiedToken token = new VerifiedToken("uid", "user@example.com");

        assertThat(token.role()).isEqualTo(Role.USER);
    }

    @Test
    void explicitAdminRoleIsPreserved() {
        VerifiedToken token = new VerifiedToken("uid", "admin@example.com", Role.ADMIN);

        assertThat(token.role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void nullRoleIsNormalisedToUser() {
        VerifiedToken token = new VerifiedToken("uid", "user@example.com", null);

        assertThat(token.role()).isEqualTo(Role.USER);
    }
}
