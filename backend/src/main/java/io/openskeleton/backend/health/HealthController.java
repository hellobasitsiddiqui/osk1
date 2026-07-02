package io.openskeleton.backend.health;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal liveness probe: {@code GET /health} → {@code 200 {"status":"UP"}}.
 *
 * <p>This is the endpoint the deploy pipeline curls to prove the app is serving.
 * It is intentionally trivial and dependency-free (no DB/auth checks) so it stays
 * green as a pure "the process is up and routing works" signal. A richer readiness
 * check with real dependency probes is Actuator's job in a later ticket (1.6.5).
 *
 * <p><b>Why {@code produces = APPLICATION_JSON_VALUE} is pinned (TM-126):</b> a
 * previous build served this endpoint as <i>XML</i> to browsers. Spring content
 * negotiation honours the request {@code Accept} header, and browsers send
 * {@code Accept: …,application/xml;q=0.9,*&#47;*;q=0.8}; when a Jackson XML
 * converter is on the classpath, Spring picks XML for that header. {@code curl}
 * (which sends {@code Accept: *&#47;*}) got JSON, so it slipped through. Pinning
 * the produced type to JSON forces every caller to get JSON regardless of a later
 * ticket adding an XML converter. The API-wide "never negotiate to XML" rule is
 * owned separately (TM-72); this pin is the local guarantee for {@code /health}.
 */
@RestController
public class HealthController {

    /** Health payload. A record keeps the JSON shape ({@code {"status":"UP"}}) obvious and immutable. */
    public record Health(String status) {}

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Health health() {
        return new Health("UP");
    }
}
