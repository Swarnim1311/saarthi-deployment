package com.saarthi.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Route regression: /policy-alerts serves the portal shell and the existing
 * /cropatlas route keeps working. No backend beans are needed for these
 * static view mappings.
 */
@WebMvcTest(WebViewController.class)
class PolicyRouteTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void policyAlertsServesPortal() throws Exception {
        mvc.perform(get("/policy-alerts"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @Test
    void cropAtlasStillServesPortal() throws Exception {
        mvc.perform(get("/cropatlas"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }
}
