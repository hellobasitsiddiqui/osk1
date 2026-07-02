package io.openskeleton.backend.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link HealthController}. Uses {@code @WebMvcTest} (MVC layer
 * only, no full context) for a fast, focused check of the HTTP contract.
 */
@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** The basic contract: 200, JSON body, {@code status == "UP"}. */
    @Test
    void healthReturnsUpAsJson() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * TM-126 regression: a real browser sends an {@code Accept} header that lists
     * {@code application/xml} (plus {@code *&#47;*}). Before the {@code produces}
     * pin this returned XML to browsers while curl got JSON. Assert JSON is served
     * for a browser-style Accept so the bug can never come back.
     */
    @Test
    void healthReturnsJsonEvenWhenBrowserAcceptsXml() throws Exception {
        mockMvc.perform(get("/health")
                        .accept(MediaType.TEXT_HTML,
                                MediaType.APPLICATION_XHTML_XML,
                                MediaType.APPLICATION_XML,
                                MediaType.ALL))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
