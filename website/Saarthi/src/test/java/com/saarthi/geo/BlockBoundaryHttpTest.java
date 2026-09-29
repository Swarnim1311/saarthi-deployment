package com.saarthi.geo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack HTTP check for the selected-block boundary endpoint:
 * real routing, real artifact, real JSON serialization. Guards the
 * Risk Map's per-block polygon fetch (one block per request).
 */
@SpringBootTest
@AutoConfigureMockMvc
class BlockBoundaryHttpTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void rajpurBoundaryServedAsGeoJsonFeature() throws Exception {
        mockMvc.perform(get("/api/geography/block-boundary")
                        .param("state", "22")
                        .param("district", "649")
                        .param("block", "3728"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("Feature"))
                .andExpect(jsonPath("$.geometry.type").value("Polygon"))
                .andExpect(jsonPath("$.geometry.coordinates").isArray())
                .andExpect(jsonPath("$.properties.block_code").value("3728"));
    }

    @Test
    void unknownTripleIs404BoundaryUnavailable() throws Exception {
        mockMvc.perform(get("/api/geography/block-boundary")
                        .param("state", "22")
                        .param("district", "649")
                        .param("block", "000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("boundary_unavailable"));
    }

    @Test
    void missingParamsIs400() throws Exception {
        mockMvc.perform(get("/api/geography/block-boundary").param("state", "22"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void servedPortalHasNoPanchayatUiOrSangrurCheckbox() throws Exception {
        String html = mockMvc.perform(get("/farmer"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("form-panchayat"),
                "Panchayat UI must be gone from the Farmer Portal");
        org.junit.jupiter.api.Assertions.assertFalse(html.contains("map-show-sangrur"),
                "Sangrur reference-polygon checkbox must be gone");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("map-show-boundary"),
                "Block-boundary toggle must be present");
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("/static/assets/logo.png"),
                "Header must use the real logo");
    }

    @Test
    void logoAssetIsServed() throws Exception {
        mockMvc.perform(get("/static/assets/logo.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("image/")));
    }
}
