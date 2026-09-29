package com.saarthi.cropatlas;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CropAtlas explainable suitability engine: scoring, missing-data honesty,
 * explainability, determinism and NaN/null safety.
 *
 * <p>No network and no Spring context: the fingerprint is constructed directly
 * and the climatology probe is stubbed, so every assertion is on CropAtlas logic
 * rather than on live data.
 */
class CropSuitabilityServiceTest {

    static final LocalDate IN_WHEAT_WINDOW = LocalDate.of(2026, 11, 10);
    static final LocalDate OUT_OF_SEASON = LocalDate.of(2026, 1, 15);

    /** Overrides only the three climatology accessors the engine uses. */
    static class StubAtlas extends CropAtlasService {
        boolean hasNormals = false;
        Double normalMm = null;

        StubAtlas() { super(null, null, null, null); }

        @Override
        public boolean hasClimatologyBlock(String blockName) { return hasNormals; }

        @Override
        public Double climatologySumMm(String blockName, List<LocalDate> dates) { return normalMm; }

        @Override
        public String climatologyVintage() { return "test normals"; }
    }

    CropSuitabilityService service(StubAtlas atlas) {
        return new CropSuitabilityService(new CropRequirements(), new CropAtlasMethod(), atlas);
    }

    // ---- fingerprint builders ----

    static EnvironmentalFingerprint.Climate climate(Double rain7, Double et0_7d,
            Integer dryDays, boolean stale) {
        Double balance = (rain7 != null && et0_7d != null)
                ? CropAtlasService.round1(rain7 - et0_7d) : null;
        return new EnvironmentalFingerprint.Climate("Open-Meteo", "ECMWF IFS (ecmwf_ifs)",
                "2026-11-09", "single_point_centroid", stale, 16,
                12.0, 24.0, 8.0, 31.0, rain7, rain7 == null ? null : rain7 * 2,
                et0_7d, balance, rain7 == null ? null : 3, dryDays, 0.22);
    }

    static EnvironmentalFingerprint.Soil soil(boolean available) {
        if (!available) {
            return new EnvironmentalFingerprint.Soil(false, null, null, null, null, null, null,
                    false, null,
                    "Soil data unavailable for this block", "0–5 cm");
        }
        return new EnvironmentalFingerprint.Soil(true, 270.0, 320.0, 375.0, 12.3, 7.7, null,
                false, null,
                "SoilGrids 0–5 cm block means (bundled)", "0–5 cm");
    }

    static EnvironmentalFingerprint fp(EnvironmentalFingerprint.Climate climate,
            EnvironmentalFingerprint.Soil soil) {
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("block_name", "Sangrur");
        loc.put("block_code", "1234");
        loc.put("label", "Sangrur · Sangrur, Punjab");
        return new EnvironmentalFingerprint(loc, climate, soil,
                new EnvironmentalFingerprint.Climatology(false, "Sangrur", null, null, null,
                        "Reference rainfall normals are not published for this block"),
                null, null, null, null,
                List.of("elevation_unavailable", "cec_unavailable_no_source"),
                List.of());
    }

    static EnvironmentalFingerprint full(Double rain7, Double et0_7d, Integer dryDays,
            boolean soilAvailable, boolean stale) {
        return fp(climate(rain7, et0_7d, dryDays, stale), soil(soilAvailable));
    }

    // ---- provenance serialisation ----

    @Test
    void servedElevationAndCecCarryTheirProvenance() {
        EnvironmentalFingerprint.Soil soil = new EnvironmentalFingerprint.Soil(
                true, 270.0, 320.0, 375.0, 12.3, 7.7, 15.2, true, null,
                "SoilGrids 0–5 cm point query (ISRIC) at the block centroid", "0–5 cm");
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("block_name", "Sangrur");
        EnvironmentalFingerprint fp = new EnvironmentalFingerprint(loc,
                climate(5.0, 3.0, 1, false), soil,
                new EnvironmentalFingerprint.Climatology(false, "Sangrur", null, null, null,
                        "Reference rainfall normals are not published for this block"),
                257.0, EnvironmentalFingerprint.REFERENCE,
                15.2, EnvironmentalFingerprint.CACHED,
                List.of(), List.of());
        Map<String, Object> m = fp.toMap();
        assertEquals(257.0, (Double) m.get("elevation_m"), 1e-9);
        assertEquals("reference", m.get("elevation_provenance"));
        assertEquals(15.2, (Double) m.get("cec_cmol_kg"), 1e-9);
        assertEquals("cached", m.get("cec_provenance"));
        @SuppressWarnings("unchecked")
        Map<String, Object> soilMap = (Map<String, Object>) m.get("soil");
        assertEquals("cached", soilMap.get("provenance"),
                "a coordinate-cache hit must read CACHED, never LIVE");
        assertEquals(15.2, (Double) soilMap.get("cec_cmol_kg"), 1e-9);
    }

    @Test
    void newlyAvailableSoilDataDoesNotChangeScoring() {
        // CEC is informational: the reference states no per-crop soil
        // requirement, so the soil component stays unscored and the overall
        // score is identical with or without CEC present.
        CropSuitabilityService svc = service(new StubAtlas());
        EnvironmentalFingerprint noCec = full(5.0, 3.0, 1, true, false);
        EnvironmentalFingerprint.Soil withCecSoil =
                new EnvironmentalFingerprint.Soil(true, 270.0, 320.0, 375.0, 12.3, 7.7,
                        15.2, false, null, "SoilGrids point query", "0–5 cm");
        EnvironmentalFingerprint withCec = fp(climate(5.0, 3.0, 1, false), withCecSoil);
        for (CropRequirements.Requirement r : new CropRequirements().all()) {
            CropSuitabilityResult a = svc.assessOne(r, noCec, java.time.LocalDate.of(2026, 7, 15));
            CropSuitabilityResult b = svc.assessOne(r, withCec, java.time.LocalDate.of(2026, 7, 15));
            assertEquals(a.componentsAvailable(), b.componentsAvailable(),
                    "CEC must not create a scored component for " + r.cropId());
            assertEquals(a.compatibilityScore(), b.compatibilityScore(),
                    "CEC must not move the compatibility score for " + r.cropId());
            assertTrue(b.missingData().stream().anyMatch(s -> s.startsWith("soil: ")),
                    "soil must still be reported as unscored for " + r.cropId());
        }
    }

    // ---- tests ----

    @Test
    void wellWateredMediumDemandCropMatchesWell() {
        StubAtlas a = new StubAtlas();
        a.hasNormals = true;
        a.normalMm = 320.0;
        // maize: medium water need (ordinal 1); abundant supply (ordinal 3) → step +2
        CropSuitabilityResult r = service(a).assessById("maize",
                full(60.0, 20.0, 0, true, false), IN_WHEAT_WINDOW);
        assertNotNull(r);
        CropSuitabilityResult.Component water = component(r, "water");
        assertTrue(water.available());
        assertEquals(1.0, water.score(), 1e-9, "abundant water vs medium need must be excellent");
    }

    @Test
    void dryConditionsDowngradeWaterAndVeryHighDemandCropHardest() {
        StubAtlas a = new StubAtlas();
        a.hasNormals = true;
        a.normalMm = 320.0;
        CropSuitabilityService svc = service(a);
        // paddy: very_high need (ordinal 3); deficit supply (ordinal 0) → step -3
        CropSuitabilityResult paddy = svc.assessById("paddy",
                full(2.0, 30.0, 7, true, false), IN_WHEAT_WINDOW);
        CropSuitabilityResult.Component w = component(paddy, "water");
        assertTrue(w.available());
        assertEquals(0.1, w.score(), 1e-9);
        assertFalse(w.constraints().isEmpty(), "drought constraint must be explained");
        // The same drought should hurt paddy far more than maize.
        CropSuitabilityResult maize = svc.assessById("maize",
                full(2.0, 30.0, 7, true, false), IN_WHEAT_WINDOW);
        assertTrue(component(maize, "water").score() > component(paddy, "water").score());
    }

    @Test
    void sustainedDryRunStepsSupplyOneBandDrier() {
        StubAtlas a = new StubAtlas();
        a.hasNormals = true;
        a.normalMm = 320.0;
        CropSuitabilityService svc = service(a);
        // balance +10 mm (adequate) with 0 dry days vs 6 dry days (≥ 5 → stepped drier).
        CropSuitabilityResult noDry = svc.assessById("maize",
                full(30.0, 20.0, 0, true, false), IN_WHEAT_WINDOW);
        CropSuitabilityResult dry = svc.assessById("maize",
                full(30.0, 20.0, 6, true, false), IN_WHEAT_WINDOW);
        assertTrue(component(noDry, "water").score() > component(dry, "water").score(),
                "an observed dry run must lower water compatibility");
    }

    @Test
    void soilCompatibilityIsNeverScoredBecauseNoCropSoilRangeIsSourced() {
        StubAtlas a = new StubAtlas();
        a.hasNormals = true;
        a.normalMm = 320.0;
        CropSuitabilityResult r = service(a).assessById("wheat",
                full(60.0, 20.0, 0, true, false), IN_WHEAT_WINDOW);
        CropSuitabilityResult.Component soil = component(r, "soil");
        assertFalse(soil.available(), "soil must not be scored without a sourced crop range");
        assertNull(soil.score());
        assertNotNull(soil.unavailableReason());
        assertTrue(r.missingData().stream().anyMatch(s -> s.startsWith("soil:")));
    }

    @Test
    void missingSoilIsReportedNotSubstituted() {
        StubAtlas a = new StubAtlas();
        a.hasNormals = true;
        a.normalMm = 320.0;
        CropSuitabilityResult r = service(a).assessById("wheat",
                full(60.0, 20.0, 0, false, false), IN_WHEAT_WINDOW);
        assertFalse(fp(climate(60.0, 20.0, 0, false), soil(false)).soil().available());
        assertEquals(4, r.componentsTotal(), "all four dimensions are always reported");
        assertEquals(3, r.componentsAvailable(), "only three can be evaluated here");
        assertEquals("moderate", r.dataConfidence());
    }

    @Test
    void missingWeatherMakesWaterComponentUnavailableRatherThanZero() {
        StubAtlas a = new StubAtlas();
        a.hasNormals = true;
        a.normalMm = 320.0;
        // rain7 null → water balance incomplete → water component must be unavailable.
        CropSuitabilityResult r = service(a).assessById("wheat",
                full(null, 20.0, null, true, false), IN_WHEAT_WINDOW);
        CropSuitabilityResult.Component water = component(r, "water");
        assertFalse(water.available());
        assertNull(water.score(), "missing weather must never become a zero score");
        assertTrue(r.missingData().stream().anyMatch(s -> s.contains("zero-filled")));
    }

    @Test
    void partialDataReducesComponentsAndLowersConfidence() {
        StubAtlas a = new StubAtlas();
        a.hasNormals = false;   // no reference normals for this block
        a.normalMm = null;
        CropSuitabilityResult r = service(a).assessById("wheat",
                full(60.0, 20.0, 0, true, false), IN_WHEAT_WINDOW);
        assertEquals(2, r.componentsAvailable(), "water + season only");
        assertTrue(r.dataConfidence().equals("moderate") || r.dataConfidence().equals("low"));
        assertFalse(component(r, "climate").available());
    }

    @Test
    void tooFewComponentsYieldsInsufficientDataNotAThinLabel() {
        CropAtlasMethod method = new CropAtlasMethod();
        List<CropSuitabilityResult.Component> onlyTwo = List.of(
                CropSuitabilityResult.Component.of("water", "Water compatibility", 0.9,
                        List.of("r"), List.of()),
                CropSuitabilityResult.Component.of("growing_season", "Growing-season", 0.9,
                        List.of("r"), List.of()));
        // Shipped default: two components meet the minimum, so a score is produced.
        assertEquals(2, method.minComponentsForOverall());
        Double score = CropSuitabilityResult.overallScore(onlyTwo, method.minComponentsForOverall());
        assertNotNull(score, "two components meet the shipped minimum");
        // A single component must never produce an overall label, and an empty
        // set must not either.
        assertNull(CropSuitabilityResult.overallScore(onlyTwo.subList(0, 1),
                        method.minComponentsForOverall()),
                "a single component must never produce an overall label");
        assertNull(CropSuitabilityResult.overallScore(List.of(),
                        method.minComponentsForOverall()));
    }

    @Test
    void growingSeasonTracksTheReferenceWindowNotTheWeather() {
        StubAtlas a = new StubAtlas();
        a.hasNormals = true;
        a.normalMm = 320.0;
        CropSuitabilityService svc = service(a);
        // Wheat: reference window 11-01..11-25.
        CropSuitabilityResult inWindow = svc.assessById("wheat",
                full(60.0, 20.0, 0, true, false), IN_WHEAT_WINDOW);
        CropSuitabilityResult outWindow = svc.assessById("wheat",
                full(60.0, 20.0, 0, true, false), OUT_OF_SEASON);
        assertTrue(component(inWindow, "growing_season").score()
                > component(outWindow, "growing_season").score(),
                "in-window must outscore out-of-window for the same weather");
        assertTrue(component(outWindow, "growing_season").constraints().size() > 0);
    }

    @Test
    void growingWindowResolvesToConcreteContiguousDates() {
        CropRequirements.Requirement wheat = new CropRequirements().byId("wheat");
        List<LocalDate> w = CropSuitabilityService.growingWindow(IN_WHEAT_WINDOW, wheat);
        assertNotNull(w);
        assertEquals(wheat.durationDays(), w.size());
        for (int i = 1; i < w.size(); i++) {
            assertEquals(w.get(i - 1).plusDays(1), w.get(i), "window must be contiguous");
        }
        // Anchored at the window opening (or inside it), never before today.
        assertFalse(w.get(0).isBefore(IN_WHEAT_WINDOW));
    }

    @Test
    void everyCandidateExplainsItself() {
        StubAtlas a = new StubAtlas();
        a.hasNormals = true;
        a.normalMm = 320.0;
        for (CropCandidate c : service(a).assess(full(60.0, 20.0, 0, true, false), IN_WHEAT_WINDOW)) {
            CropSuitabilityResult r = c.result();
            assertNotNull(r.suitability());
            assertFalse(r.why().isEmpty(), r.cropName() + " must state why");
            assertFalse(r.agronomicConsiderations().isEmpty(),
                    r.cropName() + " must carry the reference irrigation guidance");
            assertEquals(4, r.componentsTotal(), r.cropName() + " reports all dimensions");
            for (CropSuitabilityResult.Component comp : r.components()) {
                if (comp.available()) {
                    assertFalse(comp.reasons().isEmpty(),
                            r.cropName() + "/" + comp.key() + " must explain its score");
                } else {
                    assertNotNull(comp.unavailableReason(),
                            r.cropName() + "/" + comp.key() + " must explain its absence");
                }
            }
        }
    }

    @Test
    void assessmentIsDeterministic() {
        StubAtlas a = new StubAtlas();
        a.hasNormals = true;
        a.normalMm = 320.0;
        CropSuitabilityService svc = service(a);
        List<CropCandidate> first = svc.assess(full(60.0, 20.0, 0, true, false), IN_WHEAT_WINDOW);
        List<CropCandidate> second = svc.assess(full(60.0, 20.0, 0, true, false), IN_WHEAT_WINDOW);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).result().cropId(), second.get(i).result().cropId(),
                    "ordering must be stable");
            assertEquals(first.get(i).result().compatibilityScore(),
                    second.get(i).result().compatibilityScore());
        }
    }

    @Test
    void noNaNOrInfiniteScoresEverLeak() {
        StubAtlas a = new StubAtlas();
        a.hasNormals = true;
        a.normalMm = 320.0;
        for (CropCandidate c : service(a).assess(full(60.0, 20.0, 6, true, false), IN_WHEAT_WINDOW)) {
            Double s = c.result().compatibilityScore();
            if (s != null) {
                assertTrue(Double.isFinite(s), "score must be finite, was " + s);
                assertTrue(s >= 0.0 && s <= 1.0,
                        c.result().cropName() + " score out of range: " + s);
            }
            for (CropSuitabilityResult.Component comp : c.result().components()) {
                if (comp.available()) {
                    assertFalse(Double.isNaN(comp.score()));
                    assertFalse(Double.isInfinite(comp.score()));
                }
            }
        }
    }

    @Test
    void unknownCropIdYieldsNullRatherThanAFabricatedCrop() {
        StubAtlas a = new StubAtlas();
        assertNull(service(a).assessById("not-a-real-crop",
                full(60.0, 20.0, 0, true, false), IN_WHEAT_WINDOW));
    }

    @Test
    void staleForecastIsSurfacedAsAWatchNotSilentlyIgnored() {
        StubAtlas a = new StubAtlas();
        a.hasNormals = true;
        a.normalMm = 320.0;
        CropSuitabilityResult r = service(a).assessById("wheat",
                full(60.0, 20.0, 0, true, true), IN_WHEAT_WINDOW);
        assertTrue(r.watch().stream().anyMatch(w -> w.toLowerCase().contains("stale")));
    }

    @Test
    void candidatesAreBandedAndOrderedWithoutCallingAnyCropTheBest() {
        StubAtlas a = new StubAtlas();
        a.hasNormals = true;
        a.normalMm = 320.0;
        List<CropCandidate> cands = service(a).assess(full(60.0, 20.0, 0, true, false), IN_WHEAT_WINDOW);
        assertFalse(cands.isEmpty());
        String prev = null;
        for (CropCandidate c : cands) {
            String band = c.band();
            assertTrue(List.of("strong_matches", "potential_matches", "limited_matches",
                    "insufficient_data").contains(band));
            if (prev != null) {
                assertTrue(rankOf(prev) <= rankOf(band), "bands must be ordered strongest first");
            }
            prev = band;
        }
        Map<String, Object> grouped = CropSuitabilityService.group(cands);
        assertTrue(grouped.containsKey("strong_matches"));
        assertTrue(grouped.containsKey("potential_matches"));
        assertTrue(grouped.containsKey("limited_matches"));
    }

    @Test
    void noCatalogueYieldsNoCandidatesRatherThanPlaceholders() {
        CropRequirements empty = new CropRequirements("cropatlas/missing.json");
        CropSuitabilityService svc = new CropSuitabilityService(empty, new CropAtlasMethod(),
                new StubAtlas());
        List<CropCandidate> cands = svc.assess(full(60.0, 20.0, 0, true, false), IN_WHEAT_WINDOW);
        assertTrue(cands.isEmpty(), "an empty catalogue must produce zero candidates");
    }

    @Test
    void emptyFingerprintProducesNoCandidates() {
        assertTrue(service(new StubAtlas()).assess(null, IN_WHEAT_WINDOW).isEmpty());
    }

    @Test
    void globalRegionPanelIsHonestlyUnavailableWithNoSourcedRegions() {
        GlobalRegionMatcher m = new GlobalRegionMatcher();
        assertFalse(m.hasRegions(), "no global region data may be invented");
        Map<String, Object> out = m.compare(full(60.0, 20.0, 0, true, false));
        assertEquals(false, out.get("available"));
        assertTrue(((List<?>) out.get("regions")).isEmpty());
        assertNotNull(out.get("message"));
        assertTrue(String.valueOf(out.get("message")).contains("verified"));
    }

    private static int rankOf(String band) {
        return switch (band) {
            case "strong_matches" -> 0;
            case "potential_matches" -> 1;
            case "limited_matches" -> 2;
            default -> 3;
        };
    }

    private static CropSuitabilityResult.Component component(CropSuitabilityResult r, String key) {
        for (CropSuitabilityResult.Component c : r.components()) {
            if (c.key().equals(key)) return c;
        }
        throw new AssertionError("component '" + key + "' missing");
    }
}
