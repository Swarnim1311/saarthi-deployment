package com.saarthi.risks;

import com.saarthi.shadow.ShadowLedger;
import com.saarthi.weather.BlockSampler;
import com.saarthi.weather.LiveWeatherService;
import com.saarthi.weather.WeatherProvider;
import com.saarthi.weather.WeatherProviderException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIELD_HIGH live shadow ledger (separate file/schema from the dry-spell
 * ledger; dry-spell behaviour covered by ShadowCaptureTest, untouched here).
 */
class FieldShadowTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static class StubProvider implements WeatherProvider {
        final double[] rain;
        int calls = 0;

        StubProvider(double... rain) {
            this.rain = rain;
        }

        @Override
        public String providerName() {
            return "Open-Meteo";
        }

        @Override
        public String modelName() {
            return "ECMWF IFS (ecmwf_ifs)";
        }

        @Override
        public List<LocationDailyForecast> fetch(List<SamplePoint> points) {
            calls++;
            LocalDate today = LocalDate.now(IST);
            return points.stream().map(p -> {
                var list = new java.util.ArrayList<DailyPointValues>();
                for (int i = 0; i < 16; i++) {
                    double r = i < rain.length ? rain[i] : 0.0;
                    list.add(new DailyPointValues(today.plusDays(i), r, 10.0, 32.0, 24.0, 60.0, 10.0));
                }
                return new LocationDailyForecast(p.latitude(), p.longitude(), list);
            }).toList();
        }
    }

    static BlockSampler sixBlockSampler() {
        StringBuilder sb = new StringBuilder(
                "{\"type\": \"FeatureCollection\", \"features\": [");
        int i = 0;
        for (String b : com.saarthi.service.RealForecastService.BLOCKS) {
            if (i > 0) sb.append(',');
            sb.append("{\"type\": \"Feature\", \"properties\": {\"block_name\": \"")
                    .append(b).append("\"}, \"geometry\": {\"type\": \"Polygon\", ")
                    .append("\"coordinates\": [[[")
                    .append(i * 2).append(',').append(i * 2).append("],[")
                    .append(i * 2 + 1).append(',').append(i * 2).append("],[")
                    .append(i * 2 + 1).append(',').append(i * 2 + 1).append("],[")
                    .append(i * 2).append(',').append(i * 2 + 1).append("],[")
                    .append(i * 2).append(',').append(i * 2).append("]]]}}");
            i++;
        }
        return new BlockSampler(sb.append("]}").toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void captureWritesSixFieldRecords() throws Exception {
        Path dir = Files.createTempDirectory("field-shadow");
        FieldShadowService svc = new FieldShadowService(
                new ShadowLedger(dir.resolve("field_shadow.jsonl"),
                        FieldShadowService.SCHEMA_VERSION),
                new HeavyRainThresholds());
        StubProvider stub = new StubProvider(0.0, 5.0, 5.0, 0.0);
        LiveWeatherService live = new LiveWeatherService(stub, sixBlockSampler(), 60);
        Map<String, String> res = svc.tryCapture(live.getForecast(false));
        assertEquals(6, res.size());
        for (String b : com.saarthi.service.RealForecastService.BLOCKS) {
            assertEquals("written", res.get(b), b);
        }
        List<String> lines = Files.readAllLines(dir.resolve("field_shadow.jsonl"));
        assertEquals(6, lines.size());
        com.fasterxml.jackson.databind.ObjectMapper m =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode rec = m.readTree(lines.get(0));
        assertEquals("field-shadow/v1", rec.path("schema_version").asText());
        assertTrue(rec.path("issue_id").asText().startsWith("field:"));
        assertEquals(3, rec.path("truth_window").size());
        assertEquals(3, rec.path("forecast_d1_d3_mm").size());
        assertEquals(1, rec.path("predicted_high").asInt());
        assertEquals(2, rec.path("wet_days").asInt());
        assertEquals("field_work_v1", rec.path("method_version").asText());
        assertEquals("pending", rec.path("truth_status").asText());
        assertTrue(rec.path("observed_disrupted").isNull());
    }

    @Test
    void duplicateIssueSkippedAndLedgerSeparateFromDry() throws Exception {
        Path dir = Files.createTempDirectory("field-shadow-dup");
        ShadowLedger fieldLedger = new ShadowLedger(dir.resolve("field_shadow.jsonl"),
                FieldShadowService.SCHEMA_VERSION);
        ShadowLedger dryLedger = new ShadowLedger(dir.resolve("ifs_shadow.jsonl"));
        assertEquals("ifs-shadow/v1", dryLedger.schemaVersion());
        assertEquals("field-shadow/v1", fieldLedger.schemaVersion());
        FieldShadowService svc = new FieldShadowService(fieldLedger, new HeavyRainThresholds());
        StubProvider stub = new StubProvider(0.0);
        LiveWeatherService live = new LiveWeatherService(stub, sixBlockSampler(), 60);
        LiveWeatherService.LiveForecast fc = live.getForecast(false);
        assertEquals("written", svc.tryCapture(fc).get("Dhuri"));
        String before = Files.readString(dir.resolve("field_shadow.jsonl"));
        assertEquals("duplicate_skipped", svc.tryCapture(fc).get("Dhuri"));
        assertEquals(before, Files.readString(dir.resolve("field_shadow.jsonl")));
        assertFalse(Files.exists(dir.resolve("ifs_shadow.jsonl")),
                "field capture must never touch the dry-spell ledger file");
    }

    @Test
    void bothHooksCaptureOnOneFetchWithoutBreakingServing() throws Exception {
        Path dir = Files.createTempDirectory("field-shadow-dual");
        StubProvider stub = new StubProvider(0.0, 5.0, 0.0, 5.0);
        LiveWeatherService live = new LiveWeatherService(stub, sixBlockSampler(), 60);
        live.setFieldShadow(new FieldShadowService(
                new ShadowLedger(dir.resolve("field_shadow.jsonl"),
                        FieldShadowService.SCHEMA_VERSION),
                new HeavyRainThresholds()));
        // No dry-shadow hook set: only the field ledger is written.
        LiveWeatherService.LiveForecast fc = live.getForecast(false);
        assertFalse(fc.stale());
        assertEquals(1, stub.calls, "single provider call serves weather + shadow");
        assertEquals(6, Files.readAllLines(dir.resolve("field_shadow.jsonl")).size());
    }

    @Test
    void ledgerFailureIsFailSoft() throws Exception {
        Path dir = Files.createTempDirectory("field-shadow-fail");
        Path fileAsDir = dir.resolve("afile");
        Files.writeString(fileAsDir, "x");
        FieldShadowService svc = new FieldShadowService(
                new ShadowLedger(fileAsDir.resolve("s.jsonl"),
                        FieldShadowService.SCHEMA_VERSION),
                new HeavyRainThresholds());
        StubProvider stub = new StubProvider(0.0);
        LiveWeatherService live = new LiveWeatherService(stub, sixBlockSampler(), 60);
        Map<String, String> res = svc.tryCapture(live.getForecast(false));
        assertEquals("skipped_error", res.get("status"));
        assertFalse(live.getForecast(false).stale(), "weather serving unaffected");
    }

    @Test
    void gateConstantsPreRegistered() {
        assertEquals(0.60, FieldShadowService.RECALL_GATE);
        assertEquals(0.40, FieldShadowService.PRECISION_GATE);
        assertEquals(30, FieldShadowService.MIN_ISSUES_FOR_GATE);
        assertEquals("live_ifs_field", FieldShadowService.SOURCE_TAG);
    }
}
