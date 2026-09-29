package com.saarthi.weather;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FINAL operational regression: the live IFS forecast path must work when
 * CHIRPS (observed context) and shadow persistence are unavailable.
 * No network in any test.
 */
class ChirpsShadowOptionalTest {

    static class StubProvider implements WeatherProvider {
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
            LocalDate today = LocalDate.now(LiveWeatherService.IST);
            return points.stream().map(p -> {
                var list = new java.util.ArrayList<DailyPointValues>();
                for (int i = 0; i < 16; i++) {
                    list.add(new DailyPointValues(today.plusDays(i),
                            1.5, 20.0, 32.0, 24.0, 60.0, 10.0));
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
    void forecastWorksWithNoChirpsAndNoShadowHooks() {
        LiveWeatherService svc = new LiveWeatherService(new StubProvider(), sixBlockSampler(), 60);
        // No shadow hooks installed at all: plain operational path.
        LiveWeatherService.LiveForecast fc = svc.getForecast(false);
        assertEquals(6, fc.blocks().size());
        assertEquals("Open-Meteo", fc.provider());
        assertTrue(fc.model().contains("ecmwf_ifs"));
        assertEquals(16, fc.blocks().get("Sangrur").days().size());

        // Observed context is unavailable (CHIRPS unconfigured), not zero.
        RecentRainfallService recent = new RecentRainfallService();
        recent.setChirpsPath("");
        var obs = recent.observedLast(7);
        assertEquals(false, obs.get("available"));
    }

    @Test
    void brokenShadowLedgerStillServesForecast() throws Exception {
        // Fail-soft hook installed, ledger unwritable (file where a
        // directory is needed) -> forecast must still serve.
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("shadow-opt");
        java.nio.file.Path fileAsDir = dir.resolve("afile");
        java.nio.file.Files.writeString(fileAsDir, "x");
        LiveWeatherService svc = new LiveWeatherService(new StubProvider(), sixBlockSampler(), 60);
        svc.setShadowCapture(new com.saarthi.shadow.ShadowCaptureService(
                new com.saarthi.shadow.ShadowLedger(fileAsDir.resolve("s.jsonl")),
                (RecentRainfallService) null));
        svc.setFieldShadow(new com.saarthi.risks.FieldShadowService(
                (com.saarthi.shadow.ShadowLedger) null, null));
        LiveWeatherService.LiveForecast fc = svc.getForecast(false);
        assertEquals(6, fc.blocks().size(), "forecast intact despite shadow failure");
        assertFalse(fc.stale());
    }

    @Test
    void chirpsDailyWindowNullNeverBlocksForecast() {
        RecentRainfallService recent = new RecentRainfallService();
        recent.setChirpsPath("");
        assertNull(recent.dailyWindow("Sangrur",
                LocalDate.now().minusDays(30), LocalDate.now().minusDays(3)));
        // Forecast itself is unaffected.
        LiveWeatherService svc = new LiveWeatherService(new StubProvider(), sixBlockSampler(), 60);
        assertEquals(6, svc.getForecast(false).blocks().size());
    }
}
