package io.openskeleton.backend.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sample application endpoint that establishes the {@code /api/v1} versioning
 * convention (OSK-18).
 *
 * <p><b>What:</b> exposes {@code GET /api/v1/info} returning a small JSON object
 * describing the service and the API version. Note the mapping below is just
 * {@code /info} — the {@code /api/v1} prefix is applied automatically because this
 * controller lives in the {@code io.openskeleton.backend.api} package (see
 * {@link ApiVersioningConfig}).
 *
 * <p><b>Why it exists:</b> there are no real feature endpoints yet, so this is the
 * canonical worked example every future API controller copies: put the controller
 * in the {@code api} package, declare a version-agnostic mapping, and let the
 * config supply the version. It also gives the versioning test something concrete
 * to assert against.
 */
@RestController
public class ApiInfoController {

    /**
     * Response shape for {@code /api/v1/info}. A record keeps the JSON contract
     * ({@code {"name": ..., "apiVersion": ...}}) obvious and immutable.
     *
     * @param name       human-readable service name
     * @param apiVersion the API version this surface is served under
     */
    public record ApiInfo(String name, String apiVersion) {}

    /**
     * Served at {@code /api/v1/info} (prefix added by {@link ApiVersioningConfig}).
     * Static values are fine here — this endpoint documents the convention rather
     * than reporting live state; richer build/version metadata is Actuator's job.
     */
    @GetMapping("/info")
    public ApiInfo info() {
        return new ApiInfo("OpenSkeleton Backend", "v1");
    }
}
