package io.openskeleton.backend.audit;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link AuditEvent} (OSK-80).
 *
 * <p>The id type is {@link UUID} to match the entity's UUID primary key. Although
 * {@link JpaRepository} technically exposes {@code delete*}/{@code save}-as-update
 * operations, the audit log is <b>append-only</b>: this repository is only ever used
 * to INSERT a new event ({@code save} on a transient entity) and to read events back.
 * No caller updates or deletes audit rows, and the {@link AuditEvent} entity has no
 * setters, so there is no mutation path in practice. Keeping the standard
 * {@code JpaRepository} (rather than a hand-rolled narrower interface) matches the
 * {@code UserRepository} convention; the append-only discipline is a usage contract
 * enforced by {@code AuditService} and the entity's immutability, not by hiding methods.
 */
public interface AuditRepository extends JpaRepository<AuditEvent, UUID> {}
