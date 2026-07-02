package io.openskeleton.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * OpenAPI drift guard (OSK-88): fails the build if the live springdoc spec
 * (/v3/api-docs) differs from the committed {@code backend/openapi.json}. This
 * makes every REST API change intentional + reviewable in the diff.
 *
 * <p>Server-free (MockMvc) and deterministic — both sides are normalised through
 * Jackson with map keys sorted + pretty-printed, so the comparison isn't sensitive
 * to key ordering. It runs inside {@code mvn verify} (so on the existing PR gate),
 * no extra CI job required.
 *
 * <p><b>Regenerate after an API change:</b>
 * {@code ./mvnw -q test -Dtest=ApiSpecDriftTest -Dopenapi.generate=true} then commit
 * {@code backend/openapi.json} — otherwise this test (and CI) fails.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiSpecDriftTest {

    /** Committed canonical spec; path is relative to the backend module dir. */
    private static final Path SPEC = Paths.get("openapi.json");

    @Autowired
    private MockMvc mvc;

    @Test
    void committedOpenApiSpecMatchesTheCode() throws Exception {
        String live = normalize(
                mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());

        // Regeneration mode: rewrite the committed file instead of asserting.
        if (Boolean.getBoolean("openapi.generate")) {
            Files.writeString(SPEC, live, StandardCharsets.UTF_8);
            return;
        }

        assertThat(Files.exists(SPEC))
                .as("committed backend/openapi.json must exist — generate it with -Dopenapi.generate=true")
                .isTrue();
        String committed = normalize(Files.readString(SPEC, StandardCharsets.UTF_8));
        assertThat(live)
                .as("OpenAPI drift: the API changed but backend/openapi.json wasn't regenerated. "
                        + "Run: ./mvnw test -Dtest=ApiSpecDriftTest -Dopenapi.generate=true, then commit openapi.json")
                .isEqualTo(committed);
    }

    /** Parse then re-serialise with sorted keys + indentation for a stable, order-insensitive diff. */
    private static String normalize(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .enable(SerializationFeature.INDENT_OUTPUT);
        Object tree = mapper.readValue(json, Object.class);
        return mapper.writeValueAsString(tree) + "\n";
    }
}
