package com.saarthi.risks;

import com.saarthi.weather.BlockSampler;
import com.saarthi.weather.LiveWeatherService;
import com.saarthi.weather.RecentRainfallService;
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
 * Composite service integration: dry-watch reuse of the frozen DrySpellRule,
 * heavy-rain evidence that never affects severity, context lines that never
 * affect severity, and fail-soft degradation. No network.
 */
class CompositeServiceTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static class StubProvider implements WeatherProvider {
        final double[] rain;
        boolean fail = false;

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
            if (fail) throw new WeatherProviderException("stub network down");
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

    com.saarthi.service.RealForecastService realBlocks() {
        com.saarthi.service.RealForecastService svc =
                new com.saarthi.service.RealForecastService();
        svc.load();
        return svc;
    }

    /** CHIRPS fixture: all-dry 60 days ending today (antecedent available). */
    RecentRainfallService dryChirps(Path dir) throws Exception {
        LocalDate today = LocalDate.now(IST);
        Path p = dir.resolve("chirps.csv");
        StringBuilder sb = new StringBuilder("block,date,rainfall_mm\n");
        for (String b : com.saarthi.service.RealForecastService.BLOCKS) {
            for (int k = -60; k <= 0; k++) {
                sb.append(b).append(',').append(today.plusDays(k)).append(",0.0\n");
            }
        }
        Files.writeString(p, sb.toString());
        RecentRainfallService s = new RecentRainfallService();
        s.setChirpsPath(p.toString());
        return s;
    }

    CompositeRiskService service(double[] rain, RecentRainfallService recent) {
        StubProvider stub = new StubProvider(rain);
        LiveWeatherService live = new LiveWeatherService(stub, sixBlockSampler(), 60);
        return new CompositeRiskService(live, realBlocks(), recent,
                new HeavyRainThresholds(), new SoilContext(), new ClimatologyContext(), true);
    }

    @Test
    void dryForecastWithDryAntecedentGivesWatchModerate() throws Exception {
        Path dir = Files.createTempDirectory("comp-watch");
        // D+0..: all dry -> FIELD LOW (0-1 wet in D+1..D+3), f_dry=7 -> watch ACTIVE
        CompositeRiskService svc = service(
                new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0}, dryChirps(dir));
        Map<String, Object> body = svc.getBlockComposite("Sangrur", "3d");
        assertEquals("MODERATE", body.get("overall_risk"));
        assertEquals("DRY_SPELL_WATCH", body.get("primary_concern"));
        assertEquals("LOW", body.get("category"), "legacy field category preserved");
        assertEquals("LOW", body.get("confidence"), "unvalidated watch caps confidence");
        assertEquals("composite_v1", body.get("composite_method_version"));
        assertEquals("field_work_v1", body.get("method_version"));
    }

    @Test
    void wetForecastGivesHighRegardlessOfWatch() throws Exception {
        Path dir = Files.createTempDirectory("comp-high");
        CompositeRiskService svc = service(
                new double[]{0.0, 5.0, 5.0, 0.0, 0.0, 0.0, 0.0, 0.0}, dryChirps(dir));
        Map<String, Object> body = svc.getBlockComposite("Dhuri", "3d");
        assertEquals("HIGH", body.get("overall_risk"));
        assertEquals("FIELD_WORK_DISRUPTION", body.get("primary_concern"));
        assertTrue(body.get("advisories").toString().contains("at least 1 mm rain"));
    }

    @Test
    void unknownWatchFallsThroughToLow() throws Exception {
        // No CHIRPS configured -> watch UNKNOWN; fresh dry forecast -> LOW
        RecentRainfallService recent = new RecentRainfallService();
        recent.setChirpsPath("");
        CompositeRiskService svc = service(new double[8], recent);
        Map<String, Object> body = svc.getBlockComposite("Lehra", "3d");
        assertEquals("LOW", body.get("overall_risk"));
        assertEquals("NONE", body.get("primary_concern"));
        List<?> risks = (List<?>) body.get("risks");
        assertEquals("UNKNOWN", ((Map<?, ?>) risks.get(1)).get("state"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void heavyEvidencePresentButSeverityUnchanged() throws Exception {
        Path dir = Files.createTempDirectory("comp-heavy");
        // D+1..D+3 = [0, 25, 0]: LOW field (1 wet) + heavy (25 > Sangrur p95 21.151)
        CompositeRiskService svc = service(
                new double[]{0.0, 0.0, 25.0, 0.0, 0.0, 0.0, 0.0, 0.0}, dryChirps(dir));
        Map<String, Object> body = svc.getBlockComposite("Sangrur", "3d");
        assertEquals("MODERATE", body.get("overall_risk"),
                "watch (all-dry D+1..D+7 + dry antecedent), not heavy, drives MODERATE");
        List<Map<String, Object>> risks = (List<Map<String, Object>>) body.get("risks");
        Map<String, Object> heavy = risks.get(2);
        assertEquals("HEAVY_RAIN_EVIDENCE", heavy.get("name"));
        assertEquals("PRESENT", heavy.get("state"));
        assertEquals(true, heavy.get("evidence_only"));
        assertEquals("DISPLAY_ONLY", heavy.get("validation"));
        // Same forecast without heavy-able max -> identical overall
        CompositeRiskService svc2 = service(
                new double[]{0.0, 0.0, 5.0, 0.0, 0.0, 0.0, 0.0, 0.0}, dryChirps(dir));
        Map<String, Object> body2 = svc2.getBlockComposite("Sangrur", "3d");
        assertEquals(body.get("overall_risk"), body2.get("overall_risk"));
        assertEquals(body.get("confidence"), body2.get("confidence"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void contextLinesPresentAndSeverityNeutral() throws Exception {
        Path dir = Files.createTempDirectory("comp-ctx");
        // D+1..D+3 dry (LOW field), D+4..D+7 wet (f_dry=3 -> watch QUIET)
        CompositeRiskService svc = service(
                new double[]{0.0, 0.0, 0.0, 0.0, 5.0, 5.0, 5.0, 5.0}, dryChirps(dir));
        Map<String, Object> body = svc.getBlockComposite("Moonak", "3d");
        Map<String, Object> ctx = (Map<String, Object>) body.get("context");
        Map<String, Object> recentCtx = (Map<String, Object>) ctx.get("recent_rainfall");
        assertEquals(true, recentCtx.get("available"));
        assertEquals(0.0, (Double) recentCtx.get("d14_mm"));
        Map<String, Object> climCtx = (Map<String, Object>) ctx.get("climatology");
        assertEquals(true, climCtx.get("available"));
        Map<String, Object> soilCtx = (Map<String, Object>) ctx.get("soil");
        assertEquals(true, soilCtx.get("available"));
        assertTrue(soilCtx.get("line").toString().contains("context only"));
        assertEquals("LOW", body.get("overall_risk"),
                "full context availability must not raise severity");
    }

    @Test
    void readersFailSoft() {
        HeavyRainThresholds missing =
                new HeavyRainThresholds("risk/does-not-exist.json");
        assertNull(missing.dailyP95("Sangrur"));
        SoilContext noSoil = new SoilContext("risk/does-not-exist.json");
        assertNull(noSoil.describe("Sangrur"));
        ClimatologyContext noClim = new ClimatologyContext("risk/does-not-exist.csv");
        assertNull(noClim.normalSumMm("Sangrur", List.of(LocalDate.now())));
        // Real resources load with train-frozen values
        HeavyRainThresholds thr = new HeavyRainThresholds();
        assertEquals(21.151, thr.dailyP95("Sangrur"));
        assertEquals("excess-v1", thr.methodVersion());
        assertEquals("2010-2019", thr.fitPeriod());
        assertNotNull(new SoilContext().describe("Dhuri"));
        assertNotNull(new ClimatologyContext().normalSumMm("Dhuri",
                List.of(LocalDate.of(2021, 7, 8), LocalDate.of(2021, 7, 9),
                        LocalDate.of(2021, 7, 10))));
    }

    @Test
    void allBlocksHaveOverallRisk() throws Exception {
        Path dir = Files.createTempDirectory("comp-all");
        CompositeRiskService svc = service(new double[8], dryChirps(dir));
        Map<String, Object> all = svc.getAllComposites("3d");
        List<?> blocks = (List<?>) all.get("blocks");
        assertEquals(6, blocks.size());
        assertEquals("composite_v1", all.get("composite_method_version"));
        for (Object o : blocks) {
            assertNotNull(((Map<?, ?>) o).get("overall_risk"));
            assertNotNull(((Map<?, ?>) o).get("risks"));
            assertNotNull(((Map<?, ?>) o).get("context"));
        }
    }
}
