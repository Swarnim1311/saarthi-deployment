package com.saarthi.intelligence;

import com.saarthi.risks.HeavyRainThresholds;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the block-specific agricultural reading.
 * Every test asserts derivation from real inputs and difference between two
 * unlike blocks; none asserts a fixed sentence.
 */
class BlockAgriAdvisoryServiceTest {

    private static BlockAgriAdvisoryService service() {
        return new BlockAgriAdvisoryService(new HeavyRainThresholds());
    }

    /**
     * A hand-built context. The daily series is derived from the same aggregate
     * values the service reads, so the test asserts internal consistency rather
     * than a memorised sentence.
     */
    private static ForecastContext context(String block, String state, String district,
            double dailyWetMm, int wetDays, int dryDays, int longestDry, int trailingDry,
            double maxDaily, String maxDate, double tmax, double tmin, Double smv,
            boolean soilAvailable, boolean stale) {
        List<ForecastContext.Day> days = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 9, 27);
        for (int i = 0; i < 16; i++) {
            double rain = i < wetDays ? dailyWetMm : 0.0;
            days.add(new ForecastContext.Day(start.plusDays(i), i + 1, rain, 60.0,
                    tmax, tmin, 5.0, i == 0 ? smv : null, rain, 0.0, 61));
        }
        double rain7 = 0.0;
        for (int i = 0; i < 7; i++) rain7 += i < wetDays ? dailyWetMm : 0.0;
        double rain3 = 0.0;
        for (int i = 0; i < 3; i++) rain3 += i < wetDays ? dailyWetMm : 0.0;
        double et0 = 5.0 * 7;
        Double heaviest = wetDays > 0 ? dailyWetMm : null;
        return new ForecastContext(block, block + " block", "01", state, "001", district,
                "B001", 30.0, 76.0, "registry", "Open-Meteo", "ECMWF IFS",
                LocalDate.of(2026, 9, 27), Instant.now(), stale, null,
                "single_point_centroid", days, rain3, rain7, et0, rain7 - et0,
                heaviest, maxDate, wetDays, wetDays + (wetDays == 0 ? 0 : 1), dryDays,
                longestDry, trailingDry, smv, soilAvailable,
                soilAvailable ? "SoilGrids 0-5 cm block mean" : null,
                soilAvailable ? null : "no bundled soil record for this block", List.of());
    }

    private static String join(List<?> lines) {
        StringBuilder sb = new StringBuilder();
        for (Object l : lines) sb.append(String.valueOf(l)).append(" | ");
        return sb.toString();
    }

    @Test
    void wetBlockCitesItsOwnNumbersAndGetsDrainageAdvice() {
        Map<String, Object> out = service().advise(context("Sangrur", "Punjab", "Sangrur",
                17.0, 3, 0, 1, 0, 17.0, "2026-09-29", 33.0, 24.0, 0.31, true, false));
        assertEquals(Boolean.TRUE, out.get("available"));
        String happening = join(castList(out.get("what_is_happening")));
        assertTrue(happening.contains("Sangrur"), "the block itself must be named: " + happening);
        assertTrue(happening.contains("51.0"), "its own 7-day rain total must be cited: " + happening);
        String actions = join(castList(out.get("what_to_do")));
        assertTrue(actions.contains("drainage"), "a wet block must be told to clear drainage: " + actions);
        assertTrue(happening.contains("16.0"), "its own surplus must be cited: " + happening);
    }

    @Test
    void dryBlockCitesItsDeficitAndNeverClaimsWetDays() {
        Map<String, Object> out = service().advise(context("Bathinda", "Punjab", "Bathinda",
                0.0, 0, 6, 6, 2, 0.0, null, 39.0, 25.0, 0.09, true, false));
        String actions = join(castList(out.get("what_to_do")));
        assertTrue(actions.contains("irrigation"), "a dry block must get irrigation advice: " + actions);
        String matters = join(castList(out.get("why_it_matters")));
        assertTrue(matters.contains("39.0"), "its own heat ceiling must be cited: " + matters);
        String happening = join(castList(out.get("what_is_happening")));
        assertTrue(happening.contains("0 wet day(s) out of 3, 0.0"), "a dry block must honestly report zero wet days: " + happening);
        String watches = join(castList(out.get("what_to_watch")));
        assertTrue(watches.contains("Dry-run"), "a six-day dry run must be watched: " + watches);
    }

    @Test
    void twoUnlikeBlocksGetDifferentReadings() {
        BlockAgriAdvisoryService s = service();
        Map<String, Object> wet = s.advise(context("Sangrur", "Punjab", "Sangrur",
                17.0, 3, 0, 1, 0, 17.0, "2026-09-29", 33.0, 24.0, 0.31, true, false));
        Map<String, Object> dry = s.advise(context("Bathinda", "Punjab", "Bathinda",
                0.0, 0, 6, 6, 2, 0.0, null, 39.0, 25.0, 0.09, true, false));
        assertNotEquals(join(castList(wet.get("what_is_happening"))),
                join(castList(dry.get("what_is_happening"))),
                "two unlike blocks must not receive the same reading");
        assertNotEquals(join(castList(wet.get("what_to_do"))),
                join(castList(dry.get("what_to_do"))),
                "two unlike blocks must not receive the same actions");
        assertNotEquals(join(castList(wet.get("why_it_matters"))),
                join(castList(dry.get("why_it_matters"))),
                "two unlike blocks must not receive the same rationale");
    }

    @Test
    void missingInputsBecomeMissingLinesNotGuesses() {
        Map<String, Object> out = service().advise(context("Leh", "Ladakh", "Leh",
                0.0, 0, 16, 16, 0, 0.0, null, 5.0, -4.0, null, false, false));
        List<?> missing = castList(out.get("missing"));
        assertTrue(missing.size() >= 2, "incomplete inputs must be listed, not filled: " + missing);
        String watches = join(castList(out.get("what_to_watch")));
        assertTrue(watches.contains("not served for this block"),
                "an absent input must say so: " + watches);
    }

    @Test
    void staleForecastIsFlaggedInTheWatchList() {
        Map<String, Object> out = service().advise(context("Sangrur", "Punjab", "Sangrur",
                17.0, 3, 0, 1, 0, 17.0, "2026-09-29", 33.0, 24.0, 0.31, true, true));
        String watches = join(castList(out.get("what_to_watch")));
        assertTrue(watches.contains("stale"), "a stale forecast must be called out: " + watches);
    }

    @Test
    void nullContextStaysHonest() {
        Map<String, Object> out = service().advise(null);
        assertEquals(Boolean.FALSE, out.get("available"));
        assertTrue(out.containsKey("reason"));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object o) {
        return o == null ? List.of() : (List<Object>) o;
    }
}
