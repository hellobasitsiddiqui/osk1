package io.openskeleton.backend.auth;

import io.openskeleton.backend.user.User.Role;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Maps the Firebase {@code role} custom claim on a verified ID token to the platform's
 * {@link Role} and to Spring Security {@link GrantedAuthority} values (OSK-79).
 *
 * <p><b>Why a dedicated, side-effect-free helper (and not inline logic in the filter or
 * the Firebase adapter):</b> the interesting, branchy part of RBAC is precisely "what
 * does the {@code role} claim become?" — an {@code ADMIN} claim must yield
 * {@code ROLE_ADMIN}, while an absent/blank/unknown claim must default to {@code USER}
 * ({@code ROLE_USER}). Pulling that decision into one small pure class means it can be
 * unit-tested exhaustively (both AC branches, plus the fail-safe cases) without a live
 * Firebase token or a servlet request, and lets the {@link FirebaseTokenVerifier}
 * adapter — which is excluded from the JaCoCo gate because it needs live credentials —
 * stay a trivial pass-through that carries no untested logic.
 *
 * <p><b>Fail-safe / least-privilege default:</b> every path that is not an explicit,
 * recognised elevated role resolves to {@link Role#USER}. A missing claim, an empty
 * string, or an unrecognised value can therefore never accidentally grant
 * {@code ROLE_ADMIN}; the only way to become an admin is to carry a claim that matches a
 * known elevated {@link Role} name. This mirrors the auth filter's default-deny posture.
 *
 * <p><b>Why reuse {@link Role} from the {@code user} package (rather than a new enum):</b>
 * the platform must have exactly one notion of "role". {@code User.Role} already models
 * the persisted role at the data layer (OSK-60); RBAC at the edge maps onto the same two
 * values, so the token authority and the stored role can never drift apart.
 */
public final class RoleClaimMapper {

    /**
     * The Firebase custom-claim key that carries the caller's role. This is the same
     * string {@link RoleService} writes via the Admin SDK, so the write side and the
     * read side share one constant and cannot disagree on the claim name.
     */
    public static final String ROLE_CLAIM = "role";

    /**
     * Spring Security's conventional authority prefix. Spring's role-based checks
     * (e.g. {@code hasRole("ADMIN")}) look for an authority named {@code ROLE_ADMIN},
     * so authorities are always emitted as {@code ROLE_} + the enum name.
     */
    private static final String AUTHORITY_PREFIX = "ROLE_";

    private RoleClaimMapper() {
        // Utility class: no instances.
    }

    /**
     * Resolves the caller's {@link Role} from the full claims map of a decoded token —
     * the shape {@code FirebaseToken.getClaims()} returns.
     *
     * @param claims all custom claims on the verified token, or {@code null}
     * @return the mapped role; {@link Role#USER} when {@code claims} is {@code null} or
     *     carries no usable {@code role} claim
     */
    public static Role roleFromClaims(Map<String, Object> claims) {
        Object raw = (claims == null) ? null : claims.get(ROLE_CLAIM);
        return roleFrom(raw);
    }

    /**
     * Resolves a single raw {@code role} claim value to a {@link Role}.
     *
     * <p>Matching is case-insensitive and whitespace-tolerant so a claim written as
     * {@code "admin"} or {@code " ADMIN "} still maps to {@link Role#ADMIN}. Anything
     * that is not a recognised role name — {@code null}, blank, or unknown — falls back
     * to {@link Role#USER} (least privilege).
     *
     * @param rawClaim the {@code role} claim value straight off the token (Firebase
     *     stores custom claims as arbitrary JSON, hence {@link Object}), or {@code null}
     * @return the matched {@link Role}, or {@link Role#USER} as the safe default
     */
    public static Role roleFrom(Object rawClaim) {
        if (rawClaim == null) {
            return Role.USER; // claim absent → default USER (AC #2)
        }
        String value = rawClaim.toString().trim();
        if (value.isEmpty()) {
            return Role.USER; // present-but-blank is treated as absent
        }
        for (Role role : Role.values()) {
            if (role.name().equalsIgnoreCase(value)) {
                return role; // recognised role name (e.g. "ADMIN" → ROLE_ADMIN)
            }
        }
        return Role.USER; // unrecognised value → fail safe to USER
    }

    /**
     * Wraps a {@link Role} as the corresponding Spring {@link GrantedAuthority}
     * ({@code ROLE_USER} / {@code ROLE_ADMIN}). A {@code null} role is treated as
     * {@link Role#USER} so callers can never produce a {@code null} authority.
     */
    public static GrantedAuthority authorityFor(Role role) {
        Role effective = (role == null) ? Role.USER : role;
        return new SimpleGrantedAuthority(AUTHORITY_PREFIX + effective.name());
    }

    /**
     * The authorities to attach to the request principal for a caller with the given
     * role. Returned as an immutable single-element list (one role per caller today)
     * so the auth filter can publish it directly and downstream code can iterate it like
     * any other {@code Collection<GrantedAuthority>}.
     */
    public static List<GrantedAuthority> authoritiesFrom(Role role) {
        return List.of(authorityFor(role));
    }
}
