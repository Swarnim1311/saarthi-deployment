package com.saarthi.policy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PolicyAlertController.class, PolicyIngestionController.class})
class PolicyControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private PolicyAlertService alertService;

    @MockBean
    private PolicyIngestionService ingestionService;

    @MockBean
    private PolicyAlertRepository repository;

    private PolicyAlertResponse approved() {
        PolicyAlertResponse r = new PolicyAlertResponse();
        r.setId(1L);
        r.setTitle("PM KISAN update");
        r.setSource("PIB");
        r.setSourceUrl("https://pib.gov.in/press-release/1");
        r.setPublishedDate(LocalDate.of(2026, 9, 20));
        r.setVerificationStatus("PENDING");
        return r;
    }

    @Test
    void approvedFeedIsPublic() throws Exception {
        when(alertService.getLatestPolicies()).thenReturn(List.of(approved()));
        mvc.perform(get("/api/policy-alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("PM KISAN update"));
    }

    @Test
    void allFeedRequiresToken() throws Exception {
        when(alertService.getAllPolicies()).thenReturn(List.of(approved()));
        mvc.perform(get("/api/policy-alerts/all"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ingestionDisabledByDefault() throws Exception {
        mvc.perform(post("/api/policy-ingestion/run"))
                .andExpect(status().isForbidden());
    }
}
