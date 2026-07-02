package io.openskeleton.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

/**
 * Unit test for {@link PaginationProperties} (OSK-87): the bound {@code app.pagination.*}
 * defaults, the deterministic default-sort derivation, and the fallback pageable.
 */
class PaginationPropertiesTest {

    @Test
    void suppliesSafeDefaultsWithZeroConfig() {
        var properties = new PaginationProperties();

        assertThat(properties.getMaxPageSize()).isEqualTo(100);
        assertThat(properties.getDefaultPageSize()).isEqualTo(20);
        assertThat(properties.getDefaultSortProperty()).isEqualTo("id");
        assertThat(properties.getDefaultSortDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void defaultSortIsDeterministicOnTheConfiguredProperty() {
        var properties = new PaginationProperties();
        properties.setDefaultSortProperty("email");
        properties.setDefaultSortDirection(Sort.Direction.DESC);

        assertThat(properties.defaultSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "email"));
    }

    @Test
    void defaultSortFallsBackToUnsortedWhenPropertyBlank() {
        var properties = new PaginationProperties();
        properties.setDefaultSortProperty("  ");

        assertThat(properties.defaultSort().isUnsorted()).isTrue();

        // A null property is treated the same way (no NPE, unsorted).
        properties.setDefaultSortProperty(null);
        assertThat(properties.defaultSort().isUnsorted()).isTrue();
    }

    @Test
    void fallbackPageableUsesDefaultSizePageZeroAndDefaultSort() {
        var properties = new PaginationProperties();
        properties.setDefaultPageSize(25);

        var fallback = properties.fallbackPageable();

        assertThat(fallback.getPageNumber()).isZero();
        assertThat(fallback.getPageSize()).isEqualTo(25);
        assertThat(fallback.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
    }

    @Test
    void settersRoundTripEveryField() {
        var properties = new PaginationProperties();

        properties.setMaxPageSize(200);
        properties.setDefaultPageSize(50);
        properties.setDefaultSortProperty("createdAt");
        properties.setDefaultSortDirection(Sort.Direction.DESC);

        assertThat(properties.getMaxPageSize()).isEqualTo(200);
        assertThat(properties.getDefaultPageSize()).isEqualTo(50);
        assertThat(properties.getDefaultSortProperty()).isEqualTo("createdAt");
        assertThat(properties.getDefaultSortDirection()).isEqualTo(Sort.Direction.DESC);
    }
}
