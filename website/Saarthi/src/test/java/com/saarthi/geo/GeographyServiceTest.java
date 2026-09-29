package com.saarthi.geo;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Geography registry: seed loading, cascading filters, fail-loud parsing.
 * No network, no Spring — pure registry behaviour.
 */
class GeographyServiceTest {

    private static GeographyService seed() {
        return new GeographyService(GeographyService.RESOURCE);
    }

    @Test
    void registryLoadsNationalDataset() {
        GeographyService g = seed();
        assertTrue(g.all().size() >= 5000,
                "national registry must be substantially larger than the 14-row seed, was "
                        + g.all().size());
    }

    @Test
    void statesCoverIndia() {
        List<Map<String, String>> states = seed().states();
        assertTrue(states.size() >= 25, "states/UTs covered, was " + states.size());
        assertTrue(states.stream().anyMatch(s -> s.get("state_code").equals("3")
                && s.get("state_name").equals("Punjab")));
        assertTrue(states.stream().anyMatch(s -> s.get("state_code").equals("10")));
    }

    @Test
    void districtsFilterByState() {
        GeographyService g = seed();
        List<Map<String, String>> pb = g.districts("3");
        assertTrue(pb.size() >= 20, "Punjab districts, was " + pb.size());
        assertTrue(pb.stream().anyMatch(d -> d.get("district_code").equals("43")));
        assertTrue(seed().districts("999").isEmpty(), "unknown state -> empty, never fallback");
    }

    @Test
    void blocksFilterByDistrict() {
        GeographyService g = seed();
        assertTrue(g.blocks("43").size() >= 8, "Sangrur district blocks");
        assertTrue(g.blocks("43").stream()
                .anyMatch(b -> b.blockName().equals("Sunam")));
        assertTrue(g.blocks("212").size() >= 20, "Patna district blocks");
        assertTrue(g.blocks("000").isEmpty());
    }

    @Test
    void legacySangrurRowsRemainPresent() {
        GeographyService g = seed();
        // Code -> expected (name, lat, lon, method): polygon path preserved.
        assertEquals("Dhuri", g.findBlock("343").orElseThrow().blockName());
        assertEquals("Lehragaga", g.findBlock("344").orElseThrow().blockName());
        assertEquals("Sangrur", g.findBlock("347").orElseThrow().blockName());
        GeographyService.BlockRef sunam = g.findBlock("350").orElseThrow();
        assertEquals("Sunam", sunam.blockName());
        assertEquals("Sangrur", sunam.districtName());
        assertEquals(30.06990, sunam.latitude(), 1e-9);
        assertEquals(75.86470, sunam.longitude(), 1e-9);
        assertEquals("bhuvan_polygon_centroid", sunam.locationMethod());
        assertEquals("bhuvan_polygon_centroid",
                g.findBlock("343").orElseThrow().locationMethod());
        assertTrue(g.findBlock("bhuvan_b_274").isPresent(), "Moonak legacy row kept");
        assertTrue(g.findBlock("nope").isEmpty());
        assertTrue(g.findBlock(null).isEmpty());
    }

    @Test
    void allRowsAreIndiaBoundedAndLabelled() {
        Map<String, String> stateNameByCode = new java.util.LinkedHashMap<>();
        Map<String, String> distNameByCode = new java.util.LinkedHashMap<>();
        for (GeographyService.BlockRef b : seed().all()) {
            assertTrue(b.latitude() >= 6.0 && b.latitude() <= 38.0,
                    b.blockName() + " latitude");
            assertTrue(b.longitude() >= 68.0 && b.longitude() <= 98.0,
                    b.blockName() + " longitude");
            assertFalse(b.blockCode().isBlank());
            assertFalse(b.blockName().isBlank());
            assertNotNull(b.locationMethod(), "location_method required");
            assertTrue(
                    b.locationMethod().equals("bhuvan_polygon_centroid")
                            || b.locationMethod().equals("polygon_centroid")
                            || b.locationMethod().equals("geocoded_centroid"),
                    "known method, was " + b.locationMethod());
            // Hierarchy validity: one name per state/district code.
            String prevState = stateNameByCode.putIfAbsent(b.stateCode(), b.stateName());
            assertTrue(prevState == null || prevState.equals(b.stateName()),
                    "state " + b.stateCode() + " has two names");
            String dkey = b.stateCode() + ":" + b.districtCode();
            String prevDist = distNameByCode.putIfAbsent(dkey, b.districtName());
            assertTrue(prevDist == null || prevDist.equals(b.districtName()),
                    "district " + dkey + " has two names");
        }
    }

    private static final String HEADER = "state_code,state_name,district_code,district_name,"
            + "block_code,block_name,latitude,longitude,location_method\n";

    @Test
    void duplicateBlockCodeFailsLoudly() throws Exception {
        String csv = HEADER
                + "3,Punjab,43,Sangrur,350,Sunam,30.06,75.86,polygon_centroid\n"
                + "3,Punjab,43,Sangrur,350,Sunam Dupe,30.07,75.87,polygon_centroid\n";
        assertThrows(IllegalStateException.class,
                () -> GeographyService.parse(new StringReader(csv)));
    }

    @Test
    void reusedCodeAcrossDistrictsLoadsWithTripleIdentity() {
        // LGD block codes were reused across district splits (e.g. code 6935
        // labels Sumb in two J&K districts): both rows load, triple resolves.
        GeographyService g = seed();
        assertTrue(g.findBlock("1", "624", "6935").isPresent());
        assertTrue(g.findBlock("1", "5", "6935").isPresent());
        assertEquals("Sumb", g.findBlock("1", "624", "6935").orElseThrow().blockName());
        assertTrue(g.findBlock("1", "624", "00000").isEmpty());
        assertTrue(g.findBlock(null, "5", "6935").isEmpty());
    }

    @Test
    void badCoordinatesFailLoudly() throws Exception {
        String csv = HEADER
                + "3,Punjab,43,Sangrur,350,Sunam,300.0,75.86,polygon_centroid\n";
        assertThrows(IllegalStateException.class,
                () -> GeographyService.parse(new StringReader(csv)));
    }

    @Test
    void missingColumnFailsLoudly() throws Exception {
        String csv = "state_code,state_name,district_code,district_name,"
                + "block_code,block_name,latitude,location_method\n"
                + "3,Punjab,43,Sangrur,350,Sunam,30.06,polygon_centroid\n";
        assertThrows(IllegalStateException.class,
                () -> GeographyService.parse(new StringReader(csv)));
    }

    @Test
    void commentsAndBlankLinesAreSkipped() throws Exception {
        String csv = "# provenance comment\n"
                + "\n"
                + HEADER
                + "10,Bihar,212,Patna,1950,Bihta,25.55885,84.87141,polygon_centroid\n";
        List<GeographyService.BlockRef> rows =
                GeographyService.parse(new StringReader(csv));
        assertEquals(1, rows.size());
        assertEquals("Bihta", rows.get(0).blockName());
    }
}
