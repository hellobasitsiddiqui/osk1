package io.openskeleton.backend.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import io.openskeleton.backend.user.User.Role;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin seam that assigns a caller's authorization role by writing the Firebase
 * {@code role} custom claim via the Admin SDK (OSK-79).
 *
 * <p><b>What it is:</b> the single, guarded place that calls
 * {@link FirebaseAuth#setCustomUserClaims(String, Map)} to stamp {@code {"role": "..."}}
 * onto a Firebase user. Because the read side ({@link RoleClaimMapper}) and this write
 * side share the {@link RoleClaimMapper#ROLE_CLAIM} constant and the enum {@code name()},
 * a role written here is exactly what the auth filter later maps back to a
 * {@code ROLE_*} authority — the claim name and values can never drift.
 *
 * <p><b>What it is NOT (yet):</b> deliberately unwired. This is a <i>seam</i> for the
 * admin RBAC endpoint (OSK-71); OSK-79 only lands the mechanism, so nothing here is
 * exposed over HTTP and this class is intentionally not a component-scanned bean. That
 * also keeps the application context free of a hard dependency on a live
 * {@link FirebaseAuth} instance (which requires Application Default Credentials that most
 * tests and bare local runs do not have). OSK-71 will construct it with a real
 * {@link FirebaseAuth} when it wires the endpoint; unit tests construct it with a mock.
 *
 * <p><b>Guarded:</b> arguments are validated up front (a blank uid or {@code null} role
 * is a programming error, surfaced as {@link IllegalArgumentException}), and the SDK's
 * checked failure is translated into an unchecked {@link RoleAssignmentException} so the
 * seam presents a clean contract to its future caller.
 */
public class RoleService {

    private static final Logger log = LoggerFactory.getLogger(RoleService.class);

    private final FirebaseAuth firebaseAuth;

    /**
     * @param firebaseAuth the Admin SDK auth client used to write custom claims; must be
     *     non-{@code null} (OSK-71 supplies the real one, tests supply a mock)
     */
    public RoleService(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = Objects.requireNonNull(firebaseAuth, "firebaseAuth");
    }

    /**
     * Sets the {@code role} custom claim on the given Firebase user to {@code role}.
     *
     * <p>The claim is written as {@code {"role": "<ROLE_NAME>"}} using the enum's
     * {@code name()} (e.g. {@code "ADMIN"}), which is exactly the value
     * {@link RoleClaimMapper#roleFrom(Object)} recognises on the next token. The change
     * takes effect on the user's <i>next</i> ID token (existing tokens are unaffected
     * until refreshed) — standard Firebase custom-claim behaviour.
     *
     * @param firebaseUid the target user's Firebase uid; must be non-blank
     * @param role        the role to grant; must be non-{@code null}
     * @throws IllegalArgumentException if {@code firebaseUid} is blank or {@code role} is null
     * @throws RoleAssignmentException  if the Admin SDK rejects the write
     */
    public void setRole(String firebaseUid, Role role) {
        if (firebaseUid == null || firebaseUid.isBlank()) {
            throw new IllegalArgumentException("firebaseUid must not be blank");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        try {
            firebaseAuth.setCustomUserClaims(firebaseUid, Map.of(RoleClaimMapper.ROLE_CLAIM, role.name()));
            log.info("Set role '{}' on Firebase uid '{}'.", role, firebaseUid);
        } catch (FirebaseAuthException e) {
            throw new RoleAssignmentException(
                    "Failed to set role '" + role + "' on Firebase uid '" + firebaseUid + "'.", e);
        }
    }
}
