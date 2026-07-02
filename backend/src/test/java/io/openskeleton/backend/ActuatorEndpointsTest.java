package io.openskeleton.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the Actuator ops surface (OSK-50): health is public and detail-free to
 * anonymous callers, info/metrics are exposed. Auth enforcement on info/metrics is
 * delivered by the security config (OSK-28); this test covers the exposure + the
 * "no internals leaked anonymously" contract that OSK-50 owns.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorEndpointsTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthIsPublicAndUpWithoutLeakingComponentDetail() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // show-details=when-authorized => anonymous callers see no component breakdown
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void infoIsExposedAndCarriesAppMetadata() throws Exception {
        mvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.name").value("backend"));
    }

    @Test
    void metricsIsExposed() throws Exception {
        mvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
    }
}
