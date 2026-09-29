package com.saarthi.shadow;

import com.saarthi.weather.LiveWeatherService;
import com.saarthi.weather.RecentRainfallService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live IFS shadow validation tests (no network, no Spring context).
 * Mirrors the Python {@code src/shadow/test_shadow.py} contract on the Java side:
 * frozen rule, exact D+1..D+7 window, immutability, missing-never-zero,
 * fail-soft capture, and no second weather client (capture consumes an
 * already-retrieved LiveForecast).
 */
class ShadowCaptureTest {

    static final LocalDate ISSUE = LocalDate.parse("2026-09-21");

    // ---- Frozen rule ----

    @Test
    void dryBoundaryAndNullSemantics() {
        assertEquals(1.0, DrySpellRule.DRY_MM);
        assertTrue(DrySpellRule.isDry(0.99));
        assertFalse(DrySpellRule.isDry(1.00));
        assertFalse(DrySpellRule.isDry(null), "missing is never dry (never zero)");
        assertEquals("phase4.0-frozen", DrySpellRule.RULE_VERSION);
    }

    @Test
    void frozenWarnVectorsMatchBacktest() {
        assertEquals(Boolean.TRUE, DrySpellRule.evaluate(4, 6).warn());
        assertEquals(List.of("f_dry>=6"), DrySpellRule.evaluate(4, 6).reasonCodes());
        assertEquals(List.of("dry_run>=3&f_dry>=5"),
                DrySpellRule.evaluate(3, 5).reasonCodes());
        assertEquals(Boolean.FALSE, DrySpellRule.evaluate(2, 5).warn());
        assertEquals(Boolean.FALSE, DrySpellRule.evaluate(9, 4).warn());
        assertNull(DrySpellRule.evaluate(null, 6).warn());
        assertNull(DrySpellRule.evaluate(4, null).warn());
        // parity with WARN=(dry_run>=3 and f_dry>=5) or (f_dry>=6)
        for (int dr = 0; dr < 10; dr++) {
            for (int fd = 0; fd < 8; fd++) {
                boolean expect = (dr >= 3 && fd >= 5) || (fd >= 6);
                assertEquals(expect, DrySpellRule.evaluate(dr, fd).warn(),
                        "dr=" + dr + " fd=" + fd);
            }
        }
    }

    @Test
    void nullNeverBecomesZeroInCounts() {
        assertNull(DrySpellRule.countDry(java.util.Arrays.asList(0.0, null)),
                "a missing forecast day must not be counted as zero");
        assertNull(DrySpellRule.trailingDryRun(java.util.Arrays.asList(0.0, null, 0.0)),
                "a missing antecedent day must not be skipped over");
        assertEquals(7, DrySpellRule.countDry(List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)));
    }

    // ---- Capture ----

    /** Six-block LiveForecast with 16 days starting at the issue date, all dry. */
    static LiveWeatherService.LiveForecast dryForecast(LocalDate issue) {
        Map<String, LiveWeatherService.BlockForecast> blocks = new LinkedHashMap<>();
        for (String b : com.saarthi.service.RealForecastService.BLOCKS) {
            List<LiveWeatherService.BlockDaily> days = new ArrayList<>();
            for (int k = 0; k < 16; k++) {
                days.add(new LiveWeatherService.BlockDaily(
                        issue.plusDays(k), k + 1, 0.0, 10.0));
            }
            blocks.put(b, new LiveWeatherService.BlockForecast(b, days,
                    0.0, 0.0, 0.0, "multipoint_mean_n5", issue, Instant.now()));
        }
        return new LiveWeatherService.LiveForecast(
                ShadowCaptureService.PROVIDER,
                ShadowCaptureService.MODEL, issue, Instant.now(), false, null, blocks);
    }

    static Path chirpsAllDry(Path dir, LocalDate from, int days) throws Exception {
        Path p = dir.resolve("chirps.csv");
        StringBuilder sb = new StringBuilder("block,date,rainfall_mm\n");
        for (String b : com.saarthi.service.RealForecastService.BLOCKS) {
            for (int k = 0; k < days; k++) {
                sb.append(b).append(',').append(from.plusDays(k)).append(",0.0\n");
            }
        }
        Files.writeString(p, sb.toString());
        return p;
    }

    @Test
    void sixBlockCaptureWithExactWindow() throws Exception {
        Path dir = Files.createTempDirectory("shadow-cap");
        Path chirps = chirpsAllDry(dir, ISSUE.minusDays(40), 60);
        RecentRainfallService recent = new RecentRainfallService();
        recent.setChirpsPath(chirps.toString());
        ShadowLedger ledger = new ShadowLedger(dir.resolve("ifs_shadow.jsonl"));
        ShadowCaptureService svc = new ShadowCaptureService(ledger, recent);

        Map<String, String> res = svc.tryCapture(dryForecast(ISSUE));
        assertEquals(6, res.size());
        for (String b : com.saarthi.service.RealForecastService.BLOCKS) {
            assertEquals("written", res.get(b), b);
        }
        assertEquals(6, Files.readAllLines(ledger.path()).size());

        com.fasterxml.jackson.databind.ObjectMapper m =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode rec =
                m.readTree(Files.readAllLines(ledger.path()).get(0));
        assertEquals("ifs-shadow/v1", rec.path("schema_version").asText());
        assertEquals("forecast_issue", rec.path("record_kind").asText());
        assertEquals("ifs:2026-09-21", rec.path("issue_id").asText());
        assertEquals(ISSUE.minusDays(3).toString(),
                rec.path("chirps_antecedent_cutoff").asText());
        assertEquals(7, rec.path("truth_window").size());
        assertEquals("2026-09-22", rec.path("truth_window").get(0).asText());
        assertEquals("2026-09-28", rec.path("truth_window").get(6).asText());
        assertEquals(7, rec.path("forecast_d1_d7_mm").size());
        assertEquals(28, rec.path("chirps_antecedent_mm").size());
        assertEquals(1, rec.path("predicted_dryspell").asInt());
        assertEquals("pending", rec.path("truth_status").asText());
        assertTrue(rec.path("observed_label").isNull());
    }

    @Test
    void duplicateIssueIsSkippedFirstRecordPreserved() throws Exception {
        Path dir = Files.createTempDirectory("shadow-dup");
        Path chirps = chirpsAllDry(dir, ISSUE.minusDays(40), 60);
        RecentRainfallService recent = new RecentRainfallService();
        recent.setChirpsPath(chirps.toString());
        ShadowLedger ledger = new ShadowLedger(dir.resolve("s.jsonl"));
        ShadowCaptureService svc = new ShadowCaptureService(ledger, recent);

        assertEquals("written", svc.tryCapture(dryForecast(ISSUE)).get("Dhuri"));
        String before = Files.readString(ledger.path());
        // "same issue retrieved again" — must not overwrite, never throws
        Map<String, String> again = svc.tryCapture(dryForecast(ISSUE));
        assertEquals("duplicate_skipped", again.get("Dhuri"));
        assertEquals(before, Files.readString(ledger.path()));
    }

    @Test
    void missingDataYieldsNullPredictionNeverZero() throws Exception {
        Path dir = Files.createTempDirectory("shadow-miss");
        // CHIRPS unconfigured -> antecedent unavailable
        RecentRainfallService recent = new RecentRainfallService();
        recent.setChirpsPath("");
        ShadowLedger ledger = new ShadowLedger(dir.resolve("s.jsonl"));
        ShadowCaptureService svc = new ShadowCaptureService(ledger, recent);

        // one forecast day missing (null) in Dhuri's window
        LiveWeatherService.LiveForecast fc = dryForecast(ISSUE);
        LiveWeatherService.BlockForecast dh = fc.blocks().get("Dhuri");
        List<LiveWeatherService.BlockDaily> days = new ArrayList<>(dh.days());
        LiveWeatherService.BlockDaily missing = days.get(3);
        days.set(3, new LiveWeatherService.BlockDaily(
                missing.date(), missing.horizonDay(), null, null));
        Map<String, LiveWeatherService.BlockForecast> blocks =
                new LinkedHashMap<>(fc.blocks());
        blocks.put("Dhuri", new LiveWeatherService.BlockForecast("Dhuri", days,
                null, null, null, dh.spatialMethod(), ISSUE, Instant.now()));
        LiveWeatherService.LiveForecast fc2 = new LiveWeatherService.LiveForecast(
                fc.provider(), fc.model(), fc.issueDate(), fc.retrievedAt(),
                false, null, blocks);

        assertEquals("written", svc.tryCapture(fc2).get("Dhuri"));
        com.fasterxml.jackson.databind.ObjectMapper m =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode rec =
                m.readTree(Files.readAllLines(ledger.path()).get(0));
        assertTrue(rec.path("predicted_dryspell").isNull());
        assertTrue(rec.path("chirps_antecedent_mm").isNull());
        assertEquals(false, rec.path("forecast_complete").asBoolean());
        assertTrue(rec.path("reason_codes").toString().contains("antecedent_unavailable"));
        assertTrue(rec.path("reason_codes").toString().contains("incomplete_forecast"));
    }

    @Test
    void ledgerFailureNeverThrows() throws Exception {
        Path dir = Files.createTempDirectory("shadow-fail");
        Path fileAsDir = dir.resolve("afile");
        Files.writeString(fileAsDir, "x");
        // ledger path *under* a regular file: storage must fail loudly inside, softly outside
        ShadowLedger broken = new ShadowLedger(fileAsDir.resolve("s.jsonl"));
        ShadowCaptureService svc = new ShadowCaptureService(broken, null);
        Map<String, String> res = svc.tryCapture(dryForecast(ISSUE));
        assertEquals("skipped_error", res.get("status"));
    }

    @Test
    void nonIfsForecastIsNotLedgered() throws Exception {
        Path dir = Files.createTempDirectory("shadow-nonifs");
        ShadowLedger ledger = new ShadowLedger(dir.resolve("s.jsonl"));
        ShadowCaptureService svc = new ShadowCaptureService(ledger, null);
        LiveWeatherService.LiveForecast fc = dryForecast(ISSUE);
        LiveWeatherService.LiveForecast other = new LiveWeatherService.LiveForecast(
                "Other", "GFS", fc.issueDate(), fc.retrievedAt(), false, null, fc.blocks());
        assertEquals("skipped_not_ifs", svc.tryCapture(other).get("status"));
        assertFalse(Files.exists(ledger.path()));
    }
}
