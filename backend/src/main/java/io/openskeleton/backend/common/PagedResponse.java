package io.openskeleton.backend.common;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Immutable JSON envelope for a single page of a list endpoint's results (OSK-87).
 *
 * <p>This is the <b>one canonical shape</b> every paged/list endpoint returns, so
 * clients learn the contract once and reuse it everywhere. Rather than serialising
 * Spring Data's {@link Page} directly — whose JSON is verbose, unstable across
 * Spring versions, and leaks internal structure — controllers wrap their results in
 * this record. It exposes exactly the fields a client needs to render a list and a
 * pager:
 *
 * <ul>
 *   <li>{@code items} — the rows on <i>this</i> page (already mapped to the response
 *       DTO type {@code T}; never the JPA entity).</li>
 *   <li>{@code page} — zero-based index of the current page (page 0 is the first).</li>
 *   <li>{@code size} — the page size actually applied (after the max-size cap — see
 *       {@link PageableConfig}); i.e. the maximum number of items this page could
 *       hold, not necessarily {@code items.size()} on the last page.</li>
 *   <li>{@code totalElements} — total matching rows across all pages.</li>
 *   <li>{@code totalPages} — total number of pages at the current {@code size}.</li>
 * </ul>
 *
 * <p>A {@code record} is used (plain Java, no Lombok) because the envelope is an
 * immutable value: the compiler-generated accessors, {@code equals}/{@code hashCode}
 * and {@code toString} are exactly what we want, and Jackson serialises a record's
 * components as JSON fields out of the box.
 *
 * <p>See ARCHITECTURE.md → "List conventions (pagination / sorting / filtering)" for
 * the full page/size/sort convention this envelope is part of.
 *
 * @param <T> the response item type (a DTO), not the persistence entity
 */
public record PagedResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    /**
     * Build an envelope from a Spring Data {@link Page}. This is the intended
     * construction path: a repository/service returns a {@code Page<T>} and the
     * controller calls {@code PagedResponse.of(page)} to project it onto the wire
     * contract, so the mapping from {@code Page} to envelope lives in exactly one
     * place instead of being re-derived per endpoint.
     *
     * @param page the source page (its {@link Page#getContent() content} becomes
     *     {@code items}); must not be {@code null}
     * @param <T> the item type
     * @return an envelope mirroring the page's contents and metadata
     */
    public static <T> PagedResponse<T> of(Page<T> page) {
        return new PagedResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
