package io.openskeleton.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test for {@link BaseEntity}'s soft-delete helpers and accessors (OSK-84).
 *
 * <p>The lifecycle callbacks ({@code @PrePersist}/{@code @PreUpdate}) and the
 * {@code @Version} increment are exercised end-to-end against a real Postgres in
 * {@code UserSoftDeleteIntegrationTest}; here we cover the plain in-memory behaviour
 * (marker transitions, {@code isDeleted}, getters/setters) without a database.
 */
class BaseEntityTest {

    /** A minimal concrete subclass so the abstract {@link BaseEntity} can be instantiated. */
    private static final class Sample extends BaseEntity {}

    @Test
    void markDeletedThenRestoreTogglesTheMarker() {
        var entity = new Sample();

        // A brand-new entity is active (no delete marker).
        assertThat(entity.isDeleted()).isFalse();
        assertThat(entity.getDeletedAt()).isNull();

        // markDeleted stamps the marker...
        entity.markDeleted();
        assertThat(entity.isDeleted()).isTrue();
        assertThat(entity.getDeletedAt()).isNotNull();

        // ...and restore clears it, making the entity active again.
        entity.restore();
        assertThat(entity.isDeleted()).isFalse();
        assertThat(entity.getDeletedAt()).isNull();
    }

    @Test
    void accessorsRoundTripEveryField() {
        var entity = new Sample();
        var created = Instant.parse("2024-01-01T00:00:00Z");
        var updated = Instant.parse("2024-02-01T00:00:00Z");
        var deleted = Instant.parse("2024-03-01T00:00:00Z");

        entity.setCreatedAt(created);
        entity.setUpdatedAt(updated);
        entity.setDeletedAt(deleted);
        entity.setVersion(7L);

        assertThat(entity.getCreatedAt()).isEqualTo(created);
        assertThat(entity.getUpdatedAt()).isEqualTo(updated);
        assertThat(entity.getDeletedAt()).isEqualTo(deleted);
        assertThat(entity.isDeleted()).isTrue();
        assertThat(entity.getVersion()).isEqualTo(7L);
    }
}
