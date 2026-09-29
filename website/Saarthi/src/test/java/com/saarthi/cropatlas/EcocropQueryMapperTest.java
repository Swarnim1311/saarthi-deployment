package com.saarthi.cropatlas;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fingerprint → ECOCROP parameters: only measured values are sent, everything
 * else is omitted, and the fallback only sheds dimensions.
 *
 * <p>No network: the climatology probe is stubbed and fingerprints are built
 * directly, so every assertion is on mapping honesty rather than live data.
 */
class EcocropQueryMapperTest {

    /** Climatology stub: fixed annual normal for blocks that "ship" normals. */
    static class StubAtlas extends CropAtlasService {
        boolean hasNormals = true;
        Double annualMm = 650.0;

        StubAtlas() { super(null, null, null, null); }

        @Override
        public boolean hasClimatologyBlock(String blockName) { return hasNormals; }

        @Override
        public Double climatologySumMm(String blockName, List<LocalDate> dates) {
            return annualMm;
        }

        @Override
        public String climatologyVintage() { return "test normals"; }
    }

    static EnvironmentalFingerprint fp(Double tMinLow, Double tMaxHigh, Double ph,
            Double lat, Double elev, String block, Double rain7d) {
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("block_name", block);
        loc.put("latitude", lat);
        EnvironmentalFingerprint.Climate climate = new EnvironmentalFingerprint.Climate(
                "Open-Meteo", "ECMWF IFS (ecmwf_ifs)", "2026-09-26", "single_point_centroid",
                false, 16, 20.0, 30.0, tMinLow, tMaxHigh, rain7d,
                rain7d == null ? null : rain7d * 2, 40.0,
                rain7d == null ? null : CropAtlasService.round1(rain7d - 40.0), 2, 5, 0.2);
        EnvironmentalFingerprint.Soil soil = ph == null
                ? new EnvironmentalFingerprint.Soil(false, null, null, null, null, null, null,
                        false, null, "Soil data unavailable for this block", "0–5 cm")
                : new EnvironmentalFingerprint.Soil(true, 270.0, 320.0, 375.0, 12.3, ph, null,
                        false, null, "SoilGrids test", "0–5 cm");
        return new EnvironmentalFingerprint(loc, climate, soil,
                new EnvironmentalFingerprint.Climatology(false, block, null, null, null, "test"),
                elev, elev == null ? null : EnvironmentalFingerprint.REFERENCE, null, null,
                List.of(), List.of());
    }

    @Test
    void fullFingerprintQueriesEveryMeasuredDimension() {
        List<EcocropQueryMapper.EcocropQuery> levels =
                new EcocropQueryMapper().levelsFor(
                        fp(14.5, 38.5, 7.7, 17.6372, 600.4, "Khatav", 12.0), new StubAtlas());
        assertFalse(levels.isEmpty());
        EcocropQueryMapper.EcocropQuery l0 = levels.get(0);
        assertEquals(0, l0.level());
        // Horizon extremes rounded OUTWARD: floor(low), ceil(high).
        assertEquals("14.5", l0.params().get("minTemperature"));
        assertEquals("38.5", l0.params().get("maxTemperature"));
        // Annual normal from the stubbed deployment normals, NOT the 7-day rain.
        assertEquals("650", l0.params().get("minRainfall"));
        assertEquals("650", l0.params().get("maxRainfall"));
        assertEquals("7.7", l0.params().get("minSoilPh"));
        assertEquals("17.6", l0.params().get("latitude"));
        assertEquals("600", l0.params().get("altitude"));
        assertTrue(l0.dimensionsUsed().containsAll(List.of("temperature",
                "rainfall_annual_normal", "soil_ph", "latitude", "elevation")));
    }

    @Test
    void sevenDayForecastRainIsNeverUsedAsAnnualRainfall() {
        // Generous live rain but no deployment normals: rainfall stays omitted.
        StubAtlas atlas = new StubAtlas();
        atlas.hasNormals = false;
        List<EcocropQueryMapper.EcocropQuery> levels = new EcocropQueryMapper()
                .levelsFor(fp(10.0, 30.0, 7.0, 30.1, 230.0, "Sunam", 120.0), atlas);
        assertFalse(levels.isEmpty());
        for (EcocropQueryMapper.EcocropQuery q : levels) {
            assertFalse(q.params().containsKey("minRainfall"),
                    "live 7-day rain must never become an annual normal");
            assertFalse(q.params().containsKey("maxRainfall"));
        }
        assertTrue(levels.get(0).dimensionsOmitted().contains("rainfall_annual_normal"));
    }

    @Test
    void missingPhIsOmittedNeverBorrowed() {
        List<EcocropQueryMapper.EcocropQuery> levels = new EcocropQueryMapper()
                .levelsFor(fp(10.0, 30.0, null, 30.1, 230.0, "Sunam", null), new StubAtlas());
        EcocropQueryMapper.EcocropQuery l0 = levels.get(0);
        assertFalse(l0.params().containsKey("minSoilPh"));
        assertTrue(l0.dimensionsOmitted().contains("soil_ph"));
    }

    @Test
    void fallbackShedsDimensionsInDocumentedOrderWithoutEditingValues() {
        List<EcocropQueryMapper.EcocropQuery> levels = new EcocropQueryMapper()
                .levelsFor(fp(14.0, 39.0, 7.0, 17.6, 600.0, "Khatav", null), new StubAtlas());
        // L0 all five; then -elevation, -latitude, -ph, -rainfall, temp-only.
        assertEquals(5, levels.size());
        assertEquals(List.of("temperature", "rainfall_annual_normal", "soil_ph", "latitude",
                "elevation"), levels.get(0).dimensionsUsed());
        assertEquals(List.of("temperature", "rainfall_annual_normal", "soil_ph", "latitude"),
                levels.get(1).dimensionsUsed());
        assertEquals(List.of("temperature", "rainfall_annual_normal", "soil_ph"),
                levels.get(2).dimensionsUsed());
        assertEquals(List.of("temperature", "rainfall_annual_normal"),
                levels.get(3).dimensionsUsed());
        assertEquals(List.of("temperature"), levels.get(4).dimensionsUsed());
        // Measured values survive untouched through every level.
        for (EcocropQueryMapper.EcocropQuery q : levels) {
            assertEquals("14", q.params().get("minTemperature"));
            assertEquals("39", q.params().get("maxTemperature"));
        }
    }

    @Test
    void undiscoverableFingerprintYieldsNoLevels() {
        StubAtlas atlas = new StubAtlas();
        atlas.hasNormals = false;
        List<EcocropQueryMapper.EcocropQuery> levels = new EcocropQueryMapper()
                .levelsFor(fp(null, null, null, null, null, "Nowhere", null), atlas);
        assertTrue(levels.isEmpty(),
                "with no measurable dimension no query may be built");
    }

    @Test
    void categoricalSoilPropertiesAreNeverSent() {
        List<EcocropQueryMapper.EcocropQuery> levels = new EcocropQueryMapper()
                .levelsFor(fp(14.0, 39.0, 7.0, 17.6, 600.0, "Khatav", null), new StubAtlas());
        for (EcocropQueryMapper.EcocropQuery q : levels) {
            for (String forbidden : List.of("soilDepth", "soilTexture", "soilFertility",
                    "soilSalinity", "soilDrainage")) {
                assertFalse(q.params().containsKey(forbidden),
                        "categorical soil codes must never be guessed");
            }
        }
    }

    @Test
    void differentFingerprintsProduceDifferentQueries() {
        EcocropQueryMapper mapper = new EcocropQueryMapper();
        StubAtlas atlas = new StubAtlas();
        Map<String, String> a = mapper
                .levelsFor(fp(14.0, 39.0, 7.0, 17.6, 600.0, "Khatav", null), atlas)
                .get(0).params();
        Map<String, String> b = mapper
                .levelsFor(fp(20.0, 37.0, 8.0, 16.9, 10.0, "Rayavaram", null), atlas)
                .get(0).params();
        assertNotEquals(a, b, "different blocks must query different environments");
    }
}
