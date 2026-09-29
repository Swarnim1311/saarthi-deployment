package com.saarthi.weather;

import com.saarthi.shadow.ShadowCaptureService;
import com.saarthi.shadow.ShadowLedger;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shadow ledger must never become a single point of failure for weather
 * serving: storage failure keeps the API working, and provider failure
 * semantics are unchanged with the hook installed.
 */
class ShadowServingTest {

    static class StubProvider implements WeatherProvider {
        int calls = 0;
        boolean fail = false;

        @Override
        public String providerName() {
            return ShadowCaptureService.PROVIDER;
        }

        @Override
        public String modelName() {
            return ShadowCaptureService.MODEL;
        }

        @Override
        public List<LocationDailyForecast> fetch(List<SamplePoint> points) {
            calls++;
            if (fail) throw new WeatherProviderException("stub network down");
            LocalDate today = LocalDate.now(LiveWeatherService.IST);
            return points.stream().map(p -> {
                var list = new java.util.ArrayList<DailyPointValues>();
                for (int i = 0; i < 16; i++) {
                    list.add(new DailyPointValues(today.plusDays(i),
                            0.5, 20.0, 32.0, 24.0, 60.0, 10.0));
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

    static RecentRainfallService recentAroundToday(Path dir) throws Exception {
        LocalDate today = LocalDate.now(LiveWeatherService.IST);
        Path p = dir.resolve("chirps.csv");
        StringBuilder sb = new StringBuilder("block,date,rainfall_mm\n");
        for (String b : com.saarthi.service.RealForecastService.BLOCKS) {
            for (int k = -45; k <= 0; k++) {
                sb.append(b).append(',').append(today.plusDays(k)).append(",0.0\n");
            }
        }
        Files.writeString(p, sb.toString());
        RecentRainfallService s = new RecentRainfallService();
        s.setChirpsPath(p.toString());
        return s;
    }

    @Test
    void freshFetchCapturesSixLinesSecondServeUsesCache() throws Exception {
        Path dir = Files.createTempDirectory("shadow-serve");
        StubProvider stub = new StubProvider();
        LiveWeatherService svc = new LiveWeatherService(stub, sixBlockSampler(), 60);
        ShadowCaptureService shadow = new ShadowCaptureService(
                new ShadowLedger(dir.resolve("ifs_shadow.jsonl")), recentAroundToday(dir));
        svc.setShadowCapture(shadow);

        LiveWeatherService.LiveForecast first = svc.getForecast(false);
        assertFalse(first.stale());
        assertEquals(1, stub.calls);
        assertEquals(6, Files.readAllLines(dir.resolve("ifs_shadow.jsonl")).size());

        // cached serve: no refetch, no duplicate ledger lines
        svc.getForecast(false);
        assertEquals(1, stub.calls);
        assertEquals(6, Files.readAllLines(dir.resolve("ifs_shadow.jsonl")).size());
    }

    @Test
    void brokenLedgerKeepsWeatherServing() throws Exception {
        Path dir = Files.createTempDirectory("shadow-broken");
        Path fileAsDir = dir.resolve("afile");
        Files.writeString(fileAsDir, "x");
        StubProvider stub = new StubProvider();
        LiveWeatherService svc = new LiveWeatherService(stub, sixBlockSampler(), 60);
        svc.setShadowCapture(new ShadowCaptureService(
                new ShadowLedger(fileAsDir.resolve("s.jsonl")), null));

        LiveWeatherService.LiveForecast fc = svc.getForecast(false);
        assertFalse(fc.stale());
        assertEquals(6, fc.blocks().size(), "weather response intact despite ledger failure");
    }

    @Test
    void providerFailureSemanticsUnchangedWithHook() {
        StubProvider stub = new StubProvider();
        stub.fail = true;
        LiveWeatherService svc = new LiveWeatherService(stub, sixBlockSampler(), 60);
        svc.setShadowCapture(new ShadowCaptureService(
                (ShadowLedger) null, (RecentRainfallService) null));
        assertThrows(WeatherProviderException.class, () -> svc.getForecast(false),
                "no cache + provider failure must still surface, never synthetic data");
    }
}
