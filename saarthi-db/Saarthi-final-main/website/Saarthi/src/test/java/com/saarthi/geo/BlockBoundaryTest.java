package com.saarthi.geo;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Selected-block display boundaries: exact-triple lookup, honest 404 when
 * no compiled geometry exists, 400 on missing params. Never another
 * block's geometry, never an invented polygon.
 */
class BlockBoundaryTest {

    private static BlockBoundaryService service() {
        return new BlockBoundaryService(BlockBoundaryService.RESOURCE);
    }

    private static GeographyController controller() {
        var c = new GeographyController(new GeographyService(GeographyService.RESOURCE));
        c.setBoundaries(service());
        return c;
    }

    @Test
    void registryLoadsNationalBoundaries() {
        assertTrue(service().count() >= 7000, "simplified LGD boundaries loaded");
    }

    @Test
    void rajpurTripleResolvesToPolygon() {
        var geom = service().findBoundary("22", "649", "3728");
        assertTrue(geom.isPresent(), "Rajpur (Balrampur, Chhattisgarh) boundary");
        assertEquals("Polygon", geom.get().get("type").asText());
        assertTrue(geom.get().get("coordinates").isArray());
    }

    @Test
    void focusedBlocksResolve() {
        // Sunam, Hisar-I, Malihabad, Bihta, Medziphema — full triples.
        assertTrue(service().findBoundary("3", "43", "350").isPresent());
        assertTrue(service().findBoundary("6", "63", "489").isPresent());
        assertTrue(service().findBoundary("9", "162", "1334").isPresent());
        assertTrue(service().findBoundary("10", "212", "1950").isPresent());
        assertTrue(service().findBoundary("13", "758", "2315").isPresent());
    }

    @Test
    void unknownTripleIsEmpty() {
        assertTrue(service().findBoundary("22", "649", "000000").isEmpty());
        assertTrue(service().findBoundary(null, "649", "3728").isEmpty());
        assertTrue(service().findBoundary(" ", "649", "3728").isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void endpointReturnsSingleBlockFeature() {
        var resp = controller().blockBoundary("22", "649", "3728");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertEquals("Feature", body.get("type"));
        assertNotNull(body.get("geometry"));
        Map<String, Object> props = (Map<String, Object>) body.get("properties");
        assertEquals("3728", props.get("block_code"));
    }

    @Test
    void endpointUnknownTripleIs404() {
        var ex = assertThrows(GeographyController.UnknownGeographyException.class,
                () -> controller().blockBoundary("22", "649", "000000"));
        assertEquals("boundary_unavailable", ex.getError());
        assertEquals(HttpStatus.NOT_FOUND, controller().handleUnknown(ex).getStatusCode());
    }

    @Test
    void endpointMissingParamsIs400() {
        assertThrows(IllegalArgumentException.class,
                () -> controller().blockBoundary("22", null, "3728"));
        assertThrows(IllegalArgumentException.class,
                () -> controller().blockBoundary(" ", "649", "3728"));
    }

    @Test
    void endpointWithoutServiceIs404() {
        var bare = new GeographyController(new GeographyService(GeographyService.RESOURCE));
        var ex = assertThrows(GeographyController.UnknownGeographyException.class,
                () -> bare.blockBoundary("22", "649", "3728"));
        assertEquals("boundary_unavailable", ex.getError());
    }
}
