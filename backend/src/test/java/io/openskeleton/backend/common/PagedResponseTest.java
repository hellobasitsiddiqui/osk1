package io.openskeleton.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Unit test for the {@link PagedResponse} wire envelope (OSK-87).
 *
 * <p>Covers the {@code of(Page)} factory that projects a Spring Data {@link PageImpl}
 * onto the response contract, plus the record's value semantics, without any web or
 * persistence layer.
 */
class PagedResponseTest {

    @Test
    void ofMapsEveryFieldFromAPopulatedPage() {
        // A page 1 of size 2, drawn from a total of 5 matching rows => 3 total pages.
        var pageable = PageRequest.of(1, 2, Sort.by("id"));
        var page = new PageImpl<>(List.of("c", "d"), pageable, 5);

        var response = PagedResponse.of(page);

        assertThat(response.items()).containsExactly("c", "d");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    void ofHandlesAnEmptyPage() {
        // No matching rows: empty items, zero totals — the "empty list" contract.
        var page = new PageImpl<String>(List.of(), PageRequest.of(0, 20), 0);

        var response = PagedResponse.of(page);

        assertThat(response.items()).isEmpty();
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalElements()).isZero();
        assertThat(response.totalPages()).isZero();
    }

    @Test
    void recordExposesValueSemantics() {
        // Exercise the generated accessors, equals/hashCode and toString so the
        // envelope's value contract is asserted, not just the factory.
        var a = new PagedResponse<>(List.of("x"), 0, 10, 1L, 1);
        var b = new PagedResponse<>(List.of("x"), 0, 10, 1L, 1);
        var different = new PagedResponse<>(List.of("y"), 0, 10, 1L, 1);

        assertThat(a.items()).containsExactly("x");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(different);
        assertThat(a.toString()).contains("items", "page", "totalElements");
    }
}
