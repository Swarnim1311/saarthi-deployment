package com.saarthi.weather;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cache/freshness, provider-failure honesty, sampler geometry and CHIRPS parsing.
 * No live network in any test.
 */
class WeatherEngineTest {

    // ---- Stub provider ----

    static class StubProvider implements WeatherProvider {
        int calls = 0;
        boolean fail = false;
        final int days;

        StubProvider(int days) {
            this.days = days;
        }

        @Override
        public String providerName() {
            return "Stub";
        }

        @Override
        public String modelName() {
            return "StubModel";
        }

        @Override
        public List<LocationDailyForecast> fetch(List<SamplePoint> points) {
            calls++;
            if (fail) throw new WeatherProviderException("stub network down");
            return points.stream().map(p -> {
                var list = new java.util.ArrayList<DailyPointValues>();
                for (int i = 0; i < days; i++) {
                    list.add(new DailyPointValues(
                            LocalDate.parse("2026-09-18").plusDays(i),
                            1.0, 20.0, 32.0, 24.0, 60.0, 10.0));
                }
                return new LocationDailyForecast(p.latitude(), p.longitude(), list);
            }).toList();
        }
    }

    static BlockSampler twoPointSampler() {
        String geo = """
                {"type": "FeatureCollection", "features": [
                  {"type": "Feature",
                   "properties": {"block_name": "Dhuri"},
                   "geometry": {"type": "Polygon",
                     "coordinates": [[[0,0],[1,0],[1,1],[0,1],[0,0]]]}},
                  {"type": "Feature",
                   "properties": {"block_name": "Sangrur"},
                   "geometry": {"type": "Polygon",
                     "coordinates": [[[2,2],[3,2],[3,3],[2,3],[2,2]]]}},
                  {"type": "Feature",
                   "properties": {"block_name": "Lehra"},
                   "geometry": {"type": "Polygon",
                     "coordinates": [[[4,4],[5,4],[5,5],[4,5],[4,4]]]}},
                  {"type": "Feature",
                   "properties": {"block_name": "Malerkotla"},
                   "geometry": {"type": "Polygon",
                     "coordinates": [[[6,6],[7,6],[7,7],[6,7],[6,6]]]}},
                  {"type": "Feature",
                   "properties": {"block_name": "Moonak"},
                   "geometry": {"type": "Polygon",
                     "coordinates": [[[8,8],[9,8],[9,9],[8,9],[8,8]]]}},
                  {"type": "Feature",
                   "properties": {"block_name": "Sunam"},
                   "geometry": {"type": "Polygon",
                     "coordinates": [[[10,10],[11,10],[11,11],[10,11],[10,10]]]}}]}
                """;
        return new BlockSampler(geo);
    }

    @Test
    void cacheAvoidsSecondProviderCall() {
        StubProvider stub = new StubProvider(16);
        LiveWeatherService svc = new LiveWeatherService(stub, twoPointSampler(), 60);
        svc.getForecast(false);
        svc.getForecast(false);
        assertEquals(1, stub.calls, "second call must be served from cache");
    }

    @Test
    void expiredCacheRefetches() {
        StubProvider stub = new StubProvider(16);
        LiveWeatherService svc = new LiveWeatherService(stub, twoPointSampler(), 0);
        svc.getForecast(false);
        svc.getForecast(false);
        assertEquals(2, stub.calls, "TTL 0 forces refetch");
    }

    @Test
    void providerFailureWithCacheServesExplicitStale() {
        // TTL 0 forces a refetch attempt on the second call; with the provider
        // down and a usable cache, the cached value is served as explicit stale.
        // (A fresh cache would be served without contacting the provider at all,
        // so the failure would never be observed — stale-serve requires a fetch
        // attempt, i.e. expired cache or forceRefresh.)
        StubProvider stub = new StubProvider(16);
        LiveWeatherService svc = new LiveWeatherService(stub, twoPointSampler(), 0);
        LiveWeatherService.LiveForecast first = svc.getForecast(false);
        assertFalse(first.stale());
        stub.fail = true;
        LiveWeatherService.LiveForecast stale = svc.getForecast(false);
        assertTrue(stale.stale());
        assertNotNull(stale.staleWarning());
        assertEquals(2, stub.calls, "one failed refetch attempt; cached value served as explicit stale");
    }

    @Test
    void providerFailureWithoutCacheThrowsNoSynthetic() {
        StubProvider stub = new StubProvider(16);
        stub.fail = true;
        LiveWeatherService svc = new LiveWeatherService(stub, twoPointSampler(), 60);
        assertThrows(WeatherProviderException.class, () -> svc.getForecast(false));
    }

    @Test
    void freshnessBeforeAnyFetchIsUnavailable() {
        LiveWeatherService svc = new LiveWeatherService(new StubProvider(16), twoPointSampler(), 60);
        Map<String, Object> f = svc.getFreshness();
        assertEquals(false, f.get("available"));
    }

    // ---- Sampler geometry ----

    @Test
    void rayCastInsideOutside() {
        double[][] square = {{0, 0}, {1, 0}, {1, 1}, {0, 1}, {0, 0}};
        assertTrue(BlockSampler.rayCast(square, 0.5, 0.5));
        assertFalse(BlockSampler.rayCast(square, 1.5, 0.5));
    }

    @Test
    void realGeoJsonYieldsSamplesForAllSixBlocks() throws Exception {
        String geo = java.nio.file.Files.readString(
                Paths.get("src/main/resources/forecast/sangrur_blocks.geojson"));
        BlockSampler sampler = new BlockSampler(geo);
        Map<String, BlockSampler.BlockSamples> samples = sampler.samples();
        for (String b : com.saarthi.service.RealForecastService.BLOCKS) {
            assertNotNull(samples.get(b), "missing samples for " + b);
            assertTrue(samples.get(b).points().size() >= 1, "no points for " + b);
            assertTrue(samples.get(b).spatialMethod().startsWith("multipoint_mean"),
                    b + " should use multipoint sampling, got: " + samples.get(b).spatialMethod());
        }
        assertTrue(sampler.allPoints().size() >= 6, "at least one point per block");
    }

    // ---- CHIRPS recent rainfall ----

    @Test
    void chirpsSumsAndNormalisesLehragaga() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("chirps", ".csv");
        java.nio.file.Files.writeString(tmp, """
                block,date,rainfall_mm
                Dhuri,2025-12-29,1.0
                Dhuri,2025-12-30,2.0
                Dhuri,2025-12-31,3.0
                Lehragaga,2025-12-29,4.0
                Lehragaga,2025-12-30,5.0
                Lehragaga,2025-12-31,6.0
                """);
        RecentRainfallService svc = new RecentRainfallService();
        svc.setChirpsPath(tmp.toString());
        Map<String, Object> out = svc.observedLast(3);
        assertEquals(true, out.get("available"));
        @SuppressWarnings("unchecked")
        Map<String, Object> byBlock =
                (Map<String, Object>) out.get("observed_last_3d_mm_by_block");
        assertEquals(6.0, (Double) byBlock.get("Dhuri"), 1e-9);
        assertEquals(15.0, (Double) byBlock.get("Lehra"), 1e-9,
                "Lehragaga variant must normalise to Lehra");
    }

    @Test
    void chirpsMissingDaysReportNullNotZero() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("chirps-gap", ".csv");
        java.nio.file.Files.writeString(tmp, """
                block,date,rainfall_mm
                Dhuri,2025-12-30,2.0
                Dhuri,2025-12-31,3.0
                """);
        RecentRainfallService svc = new RecentRainfallService();
        svc.setChirpsPath(tmp.toString());
        Map<String, Object> out = svc.observedLast(3);
        assertEquals(false, out.get("available"));
        @SuppressWarnings("unchecked")
        Map<String, Object> byBlock =
                (Map<String, Object>) out.get("observed_last_3d_mm_by_block");
        assertNull(byBlock.get("Dhuri"), "gap days must not be zero-filled");
    }

    @Test
    void chirpsUnconfiguredIsUnavailableNotFabricated() {
        RecentRainfallService svc = new RecentRainfallService();
        svc.setChirpsPath("");
        Map<String, Object> out = svc.observedLast(7);
        assertEquals(false, out.get("available"));
        assertTrue(out.get("reason").toString().contains("not configured"));
    }

    @Test
    void chirpsReadSeriesHelper() throws Exception {
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("chirps-s", ".csv");
        java.nio.file.Files.writeString(tmp, "block,date,rainfall_mm\nSunam,2025-01-01,1.5\n");
        Map<String, TreeMap<LocalDate, Double>> s = RecentRainfallService.readSeries(tmp);
        assertEquals(1.5, s.get("Sunam").get(LocalDate.parse("2025-01-01")), 1e-9);
    }
}
