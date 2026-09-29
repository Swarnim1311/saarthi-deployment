package com.saarthi.policy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public refresh needs no admin token (it accepts no caller content —
 * only re-reads official feeds), and always answers 200 with an explicit
 * status the UI can render honestly.
 */
@WebMvcTest(PolicyRefreshController.class)
class PolicyRefreshControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private PolicyRefreshService refreshService;

    @Test
    void refreshIsPublicAndReportsShape() throws Exception {
        when(refreshService.refresh()).thenReturn(Map.of(
                "status", "success", "saved", 2, "total", 5,
                "source", "PIB", "message", "Retrieved 2 new official policies."));
        mvc.perform(post("/api/policy-alerts/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.saved").value(2))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.source").value("PIB"));
    }

    @Test
    void unavailableSourceIsExplicit() throws Exception {
        when(refreshService.refresh()).thenReturn(Map.of(
                "status", "unavailable", "saved", 0, "total", 0,
                "source", "PIB",
                "message", "The official policy source is currently unreachable."));
        mvc.perform(post("/api/policy-alerts/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unavailable"));
    }
}
