package io.openskeleton.backend.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Strongly-typed, env-overridable knobs for the shared list/pagination convention
 * (OSK-87), bound from the {@code app.pagination.*} namespace and discovered by
 * {@code @ConfigurationPropertiesScan} on {@link io.openskeleton.backend.BackendApplication}
 * (the same mechanism that binds {@code AppProperties} and {@code RateLimitProperties}).
 *
 * <p>These values feed {@link PageableConfig}, which applies them to Spring Data's
 * {@code PageableHandlerMethodArgumentResolver} so that <b>every</b> controller that
 * accepts a {@code Pageable} gets the same behaviour for free:
 *
 * <ul>
 *   <li>{@link #maxPageSize} — the hard ceiling on {@code size}. A caller asking for
 *       {@code ?size=5000} is clamped down to this value, so no single request can
 *       force an unbounded result set (a DoS / memory-pressure guard). Default 100.</li>
 *   <li>{@link #defaultPageSize} — the {@code size} used when the caller omits it.
 *       Default 20.</li>
 *   <li>{@link #defaultSortProperty} / {@link #defaultSortDirection} — a
 *       <b>deterministic</b> fallback sort. Pagination over an unsorted query is
 *       unstable (the same row can appear on two pages, or none), so the convention
 *       always applies a total order; {@code id ASC} is the safe default because a
 *       primary key is unique and present on every entity. Set the property to blank
 *       to fall back to unsorted (not recommended for real list endpoints).</li>
 * </ul>
 *
 * <p>Field defaults are set here so pagination is safe with zero config; the base
 * {@code application.yml} additionally exposes each value as an env-var placeholder
 * so operators can tune limits without a rebuild.
 */
@ConfigurationProperties(prefix = "app.pagination")
public class PaginationProperties {

    /** Hard upper bound on {@code size}; any larger requested size is clamped to this. */
    private int maxPageSize = 100;

    /** Page {@code size} applied when the caller does not supply one. */
    private int defaultPageSize = 20;

    /**
     * Entity property used for the deterministic fallback sort. Blank means "no
     * default sort" (unsorted), which is discouraged because it makes paging unstable.
     */
    private String defaultSortProperty = "id";

    /** Direction of the fallback sort applied to {@link #defaultSortProperty}. */
    private Sort.Direction defaultSortDirection = Sort.Direction.ASC;

    /**
     * The deterministic default {@link Sort}: {@code defaultSortDirection} on
     * {@code defaultSortProperty}, or {@link Sort#unsorted()} when the property is
     * blank. Used to seed the resolver's fallback pageable so a request with no
     * {@code sort} param still returns rows in a stable, repeatable order.
     *
     * @return the configured default sort, or unsorted when no property is set
     */
    public Sort defaultSort() {
        if (defaultSortProperty == null || defaultSortProperty.isBlank()) {
            return Sort.unsorted();
        }
        return Sort.by(defaultSortDirection, defaultSortProperty);
    }

    /**
     * The {@link Pageable} used when a request carries no pagination params at all:
     * first page ({@code 0}), {@link #defaultPageSize} rows, ordered by
     * {@link #defaultSort()}. Wired into the resolver via
     * {@code setFallbackPageable} by {@link PageableConfig}.
     *
     * @return the fallback pageable for param-less list requests
     */
    public Pageable fallbackPageable() {
        return PageRequest.of(0, defaultPageSize, defaultSort());
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public String getDefaultSortProperty() {
        return defaultSortProperty;
    }

    public void setDefaultSortProperty(String defaultSortProperty) {
        this.defaultSortProperty = defaultSortProperty;
    }

    public Sort.Direction getDefaultSortDirection() {
        return defaultSortDirection;
    }

    public void setDefaultSortDirection(Sort.Direction defaultSortDirection) {
        this.defaultSortDirection = defaultSortDirection;
    }
}
