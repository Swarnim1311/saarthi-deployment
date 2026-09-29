package com.saarthi.geo;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Geography endpoints: cascading dropdowns, 404 on unknown codes,
 * 400 on missing params. Controller called directly (no MockMvc).
 */
class GeographyControllerTest {

    private static GeographyController controller() {
        return new GeographyController(new GeographyService(GeographyService.RESOURCE));
    }

    @Test
    void statesReturnsNationalCoverage() {
        var resp = controller().states();
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue((int) resp.getBody().get("count") >= 25);
    }

    @Test
    void districtsFiltersByState() {
        var resp = controller().districts("9");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("9", resp.getBody().get("state_code"));
        assertTrue((int) resp.getBody().get("count") >= 50, "UP districts");
    }

    @Test
    void districtsUnknownStateIs404() {
        var ex = assertThrows(GeographyController.UnknownGeographyException.class,
                () -> controller().districts("999"));
        assertEquals("unknown_state", ex.getError());
        var resp = controller().handleUnknown(ex);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        assertEquals("unknown_state", resp.getBody().get("error"));
    }

    @Test
    void districtsMissingParamIs400() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> controller().districts(" "));
        var resp = controller().handleBadRequest(ex);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void blocksListsDistrictBlocks() {
        var resp = controller().blocks("63");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue((int) resp.getBody().get("count") >= 5, "Hisar blocks");
    }

    @Test
    void blocksUnknownDistrictIs404() {
        var ex = assertThrows(GeographyController.UnknownGeographyException.class,
                () -> controller().blocks("000"));
        assertEquals("unknown_district", ex.getError());
        Map<String, Object> body = controller().handleUnknown(ex).getBody();
        assertEquals("unknown_district", body.get("error"));
    }

    @Test
    void blocksMissingParamIs400() {
        assertThrows(IllegalArgumentException.class, () -> controller().blocks(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchResolvesLegacyNames() {
        var resp = controller().search("sunam");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        var blocks = (java.util.List<GeographyService.BlockRef>) resp.getBody().get("blocks");
        assertFalse(blocks.isEmpty());
        assertTrue(blocks.stream().anyMatch(b -> b.blockName().equals("Sunam")));
    }

    @Test
    void searchEmptyQueryIsEmptyList() {
        var resp = controller().search("  ");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, resp.getBody().get("count"));
    }
}
