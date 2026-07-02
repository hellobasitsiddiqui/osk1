package io.openskeleton.backend.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Unit test for {@link PageableConfig} (OSK-87). It applies the config's customizer
 * to a real {@link PageableHandlerMethodArgumentResolver} — the exact type Spring
 * Boot auto-configures for MVC — and then resolves {@code Pageable} arguments from
 * mock requests. This proves the convention <b>behaviourally</b>, i.e. what a
 * controller declaring a {@code Pageable} parameter would actually receive:
 *
 * <ul>
 *   <li>an over-max {@code size} is clamped down to the cap;</li>
 *   <li>a param-less request gets the deterministic fallback (page 0, default size,
 *       default sort);</li>
 *   <li>an explicit {@code page}/{@code size}/{@code sort} is honoured.</li>
 * </ul>
 */
class PageableConfigTest {

    /**
     * Dummy handler method whose single {@link Pageable} parameter the resolver
     * binds against — the resolver needs a {@link MethodParameter}, so we point it
     * at this method's first argument.
     */
    @SuppressWarnings("unused")
    void listing(Pageable pageable) {
        // intentionally empty — only its signature is used to build a MethodParameter
    }

    /** A resolver with the production customizer applied, using default properties. */
    private PageableHandlerMethodArgumentResolver customizedResolver() {
        var resolver = new PageableHandlerMethodArgumentResolver();
        PageableHandlerMethodArgumentResolverCustomizer customizer =
                new PageableConfig().pageableCustomizer(new PaginationProperties());
        customizer.customize(resolver);
        return resolver;
    }

    /** Resolve the {@code Pageable} the resolver would hand a controller for {@code request}. */
    private Pageable resolve(MockHttpServletRequest request) throws Exception {
        var methodParameter = new MethodParameter(getClass().getDeclaredMethod("listing", Pageable.class), 0);
        return customizedResolver().resolveArgument(methodParameter, null, new ServletWebRequest(request), null);
    }

    @Test
    void capsSizeRequestedAboveTheMaximum() throws Exception {
        var request = new MockHttpServletRequest();
        request.addParameter("page", "0");
        request.addParameter("size", "500"); // well above the default cap of 100

        var pageable = resolve(request);

        assertThat(pageable.getPageSize()).isEqualTo(100); // clamped
        assertThat(pageable.getPageNumber()).isZero();
    }

    @Test
    void honoursSizeAtOrBelowTheMaximum() throws Exception {
        var request = new MockHttpServletRequest();
        request.addParameter("page", "2");
        request.addParameter("size", "30"); // under the cap => passed through untouched

        var pageable = resolve(request);

        assertThat(pageable.getPageSize()).isEqualTo(30);
        assertThat(pageable.getPageNumber()).isEqualTo(2);
    }

    @Test
    void appliesFallbackWithDeterministicSortWhenNoParamsSupplied() throws Exception {
        var pageable = resolve(new MockHttpServletRequest());

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(20); // default page size
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "id")); // default sort
    }

    @Test
    void honoursAnExplicitSortParameter() throws Exception {
        var request = new MockHttpServletRequest();
        request.addParameter("page", "1");
        request.addParameter("size", "10");
        request.addParameter("sort", "email,desc");

        var pageable = resolve(request);

        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "email"));
    }
}
