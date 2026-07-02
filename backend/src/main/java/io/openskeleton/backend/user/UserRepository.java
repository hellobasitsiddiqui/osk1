package io.openskeleton.backend.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link User} (OSK-60).
 *
 * <p>The id type is {@link UUID} to match the entity's UUID primary key. Beyond the
 * standard CRUD that {@link JpaRepository} supplies, the one domain lookup the
 * platform needs is by Firebase UID — the natural identity every authenticated
 * caller carries — implemented as a derived query below. No custom business logic
 * lives here (that arrives with JIT provisioning in OSK-76 and RBAC in OSK-71); this
 * is the data-access seam later tickets build on.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Find a user by their unique Firebase UID. Returns {@link Optional#empty()} when
     * no user has been provisioned for that UID yet. Spring Data derives the query
     * from the method name against the {@code firebase_uid} unique column.
     */
    Optional<User> findByFirebaseUid(String firebaseUid);
}
