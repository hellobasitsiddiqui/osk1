package io.openskeleton.backend.version;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link VersionController} (OSK-100). Uses {@code @WebMvcTest}
 * for a fast check of the HTTP contract, mirroring {@code HealthControllerTest}.
 *
 * <p>The {@code build.git-sha}/{@code build.revision} properties are set here so the
 * test proves the {@code @Value} → JSON wiring actually flows through the HTTP layer
 * (not just the unit-level resolution). Note that in a web slice no
 * {@code BuildProperties} bean is loaded, so {@code buildTime} is {@code null} — the
 * value-source fallbacks themselves are covered by {@link VersionControllerTest}.
 */
@WebMvcTest(VersionController.class)
@TestPropertySource(properties = {"build.git-sha=deadbee", "build.revision=backend-00042-abc"})
class VersionControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    /** The basic contract: 200, JSON body, fields sourced from the injected properties. */
    @Test
    void versionReturnsBuildStampAsJson() throws Exception {
        mockMvc.perform(get("/version"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sha").value("deadbee"))
                .andExpect(jsonPath("$.revision").value("backend-00042-abc"));
    }

    /**
     * Same TM-126 regression guard as {@code /health}: a browser Accept header that
     * lists XML must still get JSON, so a later XML converter can't flip this to XML.
     */
    @Test
    void versionReturnsJsonEvenWhenBrowserAcceptsXml() throws Exception {
        mockMvc.perform(get("/version")
                        .accept(
                                MediaType.TEXT_HTML,
                                MediaType.APPLICATION_XHTML_XML,
                                MediaType.APPLICATION_XML,
                                MediaType.ALL))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
