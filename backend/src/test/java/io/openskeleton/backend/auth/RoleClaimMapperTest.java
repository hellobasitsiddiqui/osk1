package io.openskeleton.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.openskeleton.backend.user.User.Role;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Unit tests for the {@link RoleClaimMapper} — the claim → authority mapping that is the
 * heart of OSK-79. This is the AC #3 coverage: it asserts the two required branches (an
 * {@code ADMIN} claim becomes {@code ROLE_ADMIN}; an absent claim defaults to
 * {@code ROLE_USER}) plus the fail-safe edge cases (blank / unknown / non-string /
 * differently-cased values), all without a live Firebase token or a servlet request.
 */
class RoleClaimMapperTest {

    private static final GrantedAuthority ROLE_ADMIN = new SimpleGrantedAuthority("ROLE_ADMIN");
    private static final GrantedAuthority ROLE_USER = new SimpleGrantedAuthority("ROLE_USER");

    // --- AC branch 1: role claim present as ADMIN → ROLE_ADMIN -----------------------

    @Test
    void adminClaimMapsToAdminRoleAndRoleAdminAuthority() {
        Map<String, Object> claims = Map.of(RoleClaimMapper.ROLE_CLAIM, "ADMIN");

        Role role = RoleClaimMapper.roleFromClaims(claims);

        assertThat(role).isEqualTo(Role.ADMIN);
        assertThat(RoleClaimMapper.authorityFor(role)).isEqualTo(ROLE_ADMIN);
        assertThat(RoleClaimMapper.authoritiesFrom(role)).containsExactly(ROLE_ADMIN);
    }

    // --- AC branch 2: role claim absent → default USER → ROLE_USER -------------------

    @Test
    void absentRoleClaimDefaultsToUserRoleAndRoleUserAuthority() {
        // A claims map that carries no "role" key at all.
        Map<String, Object> claims = Map.of("email", "alice@example.com");

        Role role = RoleClaimMapper.roleFromClaims(claims);

        assertThat(role).isEqualTo(Role.USER);
        assertThat(RoleClaimMapper.authorityFor(role)).isEqualTo(ROLE_USER);
        assertThat(RoleClaimMapper.authoritiesFrom(role)).containsExactly(ROLE_USER);
    }

    @Test
    void nullClaimsMapDefaultsToUser() {
        assertThat(RoleClaimMapper.roleFromClaims(null)).isEqualTo(Role.USER);
    }

    @Test
    void explicitUserClaimMapsToUser() {
        Map<String, Object> claims = Map.of(RoleClaimMapper.ROLE_CLAIM, "USER");

        assertThat(RoleClaimMapper.roleFromClaims(claims)).isEqualTo(Role.USER);
    }

    // --- Fail-safe / robustness edge cases ------------------------------------------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "superuser", "root", "Administrator"})
    void missingBlankOrUnknownClaimValuesFallBackToUser(String rawClaim) {
        assertThat(RoleClaimMapper.roleFrom(rawClaim)).isEqualTo(Role.USER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "admin", "Admin", "  admin  "})
    void adminClaimMatchingIsCaseInsensitiveAndTrimmed(String rawClaim) {
        assertThat(RoleClaimMapper.roleFrom(rawClaim)).isEqualTo(Role.ADMIN);
    }

    @Test
    void nonStringClaimValueIsCoercedThenMatched() {
        // Firebase stores custom claims as arbitrary JSON, so a value could arrive as a
        // non-String; the mapper coerces via toString() before matching.
        Map<String, Object> claims = new HashMap<>();
        claims.put(RoleClaimMapper.ROLE_CLAIM, new StringBuilder("ADMIN"));

        assertThat(RoleClaimMapper.roleFromClaims(claims)).isEqualTo(Role.ADMIN);
    }

    @Test
    void nullRoleProducesRoleUserAuthorityNeverNull() {
        assertThat(RoleClaimMapper.authorityFor(null)).isEqualTo(ROLE_USER);
    }
}
