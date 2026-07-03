package io.openskeleton.backend.login;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link EmailLoginCode} (OSK-129).
 *
 * <p>The whole feature only ever addresses a code by its owning email (there is at most one
 * active code per email — enforced by the {@code ux_email_login_codes_email} UNIQUE index), so
 * a single derived lookup covers it: {@link #findByEmail(String)}. Issuing a new code
 * <i>updates that row in place</i> when one already exists (see {@link EmailLoginCodeStore}),
 * so there is no delete-then-insert and thus no risk of Hibernate's flush ordering tripping
 * the UNIQUE index.
 *
 * <p>Callers pass the email already trimmed + lower-cased (see {@link EmailLoginCodeService});
 * the column stores that normalised form, so this exact-match derived query lines up with how
 * the code was persisted.
 */
public interface EmailLoginCodeRepository extends JpaRepository<EmailLoginCode, UUID> {

    /** Find the (single) active code row for a normalised email, if one exists. */
    Optional<EmailLoginCode> findByEmail(String email);
}
