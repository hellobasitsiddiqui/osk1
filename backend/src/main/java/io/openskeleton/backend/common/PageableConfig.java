package io.openskeleton.backend.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;

/**
 * Wires the app-wide pagination convention into Spring MVC (OSK-87) so controllers
 * opt in <b>uniformly</b> just by declaring a {@code Pageable} parameter — no
 * per-endpoint clamping, defaulting or sort boilerplate.
 *
 * <p><b>How it works:</b> Spring Data resolves a controller's {@code Pageable}
 * argument from the {@code page}/{@code size}/{@code sort} query params via a
 * {@code PageableHandlerMethodArgumentResolver}. Spring Boot's
 * {@code SpringDataWebAutoConfiguration} applies any
 * {@link PageableHandlerMethodArgumentResolverCustomizer} bean to that resolver, so
 * the single bean below is the one place the convention is configured:
 *
 * <ul>
 *   <li>{@code setMaxPageSize(maxPageSize)} — enforces the size cap. A request for
 *       {@code ?size=5000} is clamped to {@link PaginationProperties#getMaxPageSize()}
 *       (default 100) before the query runs, so no caller can force an unbounded
 *       result set. This is the enforcement point for acceptance criterion "size
 *       capped to a max".</li>
 *   <li>{@code setFallbackPageable(fallbackPageable)} — supplies the page/size/sort
 *       used when a request carries no pagination params, giving every list endpoint
 *       a safe default page size and a <b>deterministic</b> default sort (unstable
 *       paging otherwise duplicates or drops rows). See
 *       {@link PaginationProperties#fallbackPageable()}.</li>
 * </ul>
 *
 * <p><b>Why a customizer bean rather than a {@code WebMvcConfigurer}:</b> registering
 * the resolver by hand would replace Boot's auto-configured one and lose the
 * defaulting/sort-parsing it already provides. A customizer tweaks the existing
 * resolver in place, so we adjust only the two knobs we care about and inherit the
 * rest. This also keeps the convention working for any future controller with zero
 * extra wiring.
 *
 * <p>Controllers that need a different <i>default</i> (e.g. sort newest-first) can
 * still annotate their {@code Pageable} param with {@code @PageableDefault} /
 * {@code @SortDefault}; the max-size cap here always applies on top. See
 * ARCHITECTURE.md → "List conventions (pagination / sorting / filtering)".
 */
@Configuration
public class PageableConfig {

    /**
     * The customizer Spring Boot applies to the app's
     * {@code PageableHandlerMethodArgumentResolver}: it caps {@code size} to the
     * configured maximum and installs the deterministic fallback pageable.
     *
     * @param properties the bound {@code app.pagination.*} settings
     * @return a customizer that enforces the size cap and default sort/page-size
     */
    @Bean
    PageableHandlerMethodArgumentResolverCustomizer pageableCustomizer(PaginationProperties properties) {
        return resolver -> {
            resolver.setMaxPageSize(properties.getMaxPageSize());
            resolver.setFallbackPageable(properties.fallbackPageable());
        };
    }
}
