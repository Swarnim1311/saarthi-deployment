package com.saarthi.farmer;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Farmer endpoint contract: list, single-record lookup with 404 for unknown
 * ids, and validated create/update. No Spring context needed.
 */
class FarmerControllerTest {

    private static MockMvc mvc() {
        FarmerService service = new FarmerService(new FarmerRepository());
        return MockMvcBuilders.standaloneSetup(new FarmerController(service)).build();
    }

    @Test
    void listReturnsSeededFarmers() throws Exception {
        mvc().perform(get("/api/farmers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.farmers").isArray())
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void knownIdReturnsOnlyThatRecord() throws Exception {
        mvc().perform(get("/api/farmers/F001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.farmer.farmerId").value("F001"))
                .andExpect(jsonPath("$.farmer.crop").value("Rice"))
                .andExpect(jsonPath("$.*", hasSize(1)));
    }

    @Test
    void lookupIsCaseInsensitive() throws Exception {
        mvc().perform(get("/api/farmers/f001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.farmer.farmerId").value("F001"));
    }

    @Test
    void unknownIdIs404() throws Exception {
        mvc().perform(get("/api/farmers/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_farmer"));
    }

    @Test
    void blankIdIs404() throws Exception {
        mvc().perform(get("/api/farmers/%20"))
                .andExpect(status().isNotFound());
    }

    @Test
    void missingRequiredFieldsAre400() throws Exception {
        mvc().perform(post("/api/farmers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"farmerId\":\"F009\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    @Test
    void validProfileIsCreatedAndRetrievable() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(post("/api/farmers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"farmerId\":\"F010\",\"farmerName\":\"Test Farmer\","
                                + "\"crop\":\"Maize\",\"cropLocation\":\"Ludhiana\","
                                + "\"district\":\"Ludhiana\",\"state\":\"Punjab\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.farmer.farmerId").value("F010"));
        mvc.perform(get("/api/farmers/F010"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.farmer.crop").value("Maize"));
    }
}
