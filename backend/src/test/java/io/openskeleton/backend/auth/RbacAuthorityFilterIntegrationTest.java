package io.openskeleton.backend.auth;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openskeleton.backend.auth.TokenVerifier.VerifiedToken;
import io.openskeleton.backend.user.User.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end check that the RBAC role → authority mapping (OSK-79) flows through the
 * real MVC stack and is published on the request by {@link FirebaseAuthenticationFilter}.
 *
 * <p>Like {@code FirebaseAuthIntegrationTest}, only the {@link TokenVerifier} seam is
 * mocked (verifying a genuine Firebase token needs live credentials). Here the mock
 * returns a verified principal carrying a specific {@link Role}, and we assert the filter
 * exposes the matching {@code ROLE_*} authority under
 * {@link FirebaseAuthenticationFilter#AUTHORITIES_ATTRIBUTE} — i.e. that a real request
 * ends up carrying {@code ROLE_ADMIN} for an admin and {@code ROLE_USER} by default.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RbacAuthorityFilterIntegrationTest {

    private static final String TOKEN = "token";

    @Autowired
    private MockMvc mvc;

    /** Mock the verification seam so no live Firebase project/credentials are needed. */
    @MockBean
    private TokenVerifier tokenVerifier;

    @Test
    void adminPrincipalCausesRoleAdminAuthorityToBePublishedOnTheRequest() throws Exception {
        when(tokenVerifier.verify(eq(TOKEN)))
                .thenReturn(new VerifiedToken("uid-admin", "admin@rbac.example.com", Role.ADMIN));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(request()
                        .attribute(
                                FirebaseAuthenticationFilter.AUTHORITIES_ATTRIBUTE,
                                hasItem(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @Test
    void principalWithoutAnElevatedRoleGetsRoleUserByDefault() throws Exception {
        // The two-arg VerifiedToken defaults to Role.USER — the same default the mapper
        // applies when the Firebase role claim is absent.
        when(tokenVerifier.verify(eq(TOKEN))).thenReturn(new VerifiedToken("uid-user", "user@rbac.example.com"));

        mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(request()
                        .attribute(
                                FirebaseAuthenticationFilter.AUTHORITIES_ATTRIBUTE,
                                hasItem(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
