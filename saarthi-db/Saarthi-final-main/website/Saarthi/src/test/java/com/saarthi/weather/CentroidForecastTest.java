package com.saarthi.weather;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dynamic centroid path: arbitrary coordinates flow through the EXISTING
 * IFS provider + 16-day aggregation with an honest single-point label.
 * Sangrur multipoint behaviour is covered by BlockAggregationTest (untouched).
 */
class CentroidForecastTest {

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
            return points.stream().map(p -> {
                List<DailyPointValues> days = new ArrayList<>();
                LocalDate d = LocalDate.parse("2026-09-23");
                for (int i = 0; i < 16; i++) {
                    days.add(new DailyPointValues(d.plusDays(i), 2.5, 40.0,
                            31.0, 23.0, 60.0, 10.0, 4.0, 0.2, 2.5, 0.0, 3));
                }
                return new LocationDailyForecast(p.latitude(), p.longitude(), days);
            }).toList();
        }
    }

    private static LiveWeatherService service() {
        BlockSampler sampler = new BlockSampler(
                "{\"type\": \"FeatureCollection\", \"features\": []}");
        return new LiveWeatherService(new StubProvider(), sampler, 60);
    }

    @Test
    void centroidForecastUsesExistingContract() {
        LiveWeatherService.BlockForecast b =
                service().forecastForCentroid("Bihta", 25.55885, 84.87141);
        assertEquals("Bihta", b.blockName());
        assertEquals(16, b.days().size(), "same 16-day IFS contract");
        assertEquals(2.5, b.days().get(0).rainfallMm(), 1e-9);
        assertEquals(2.5 * 7, b.cum7Mm(), 1e-9);
        assertEquals(BlockSampler.CENTROID_METHOD, b.spatialMethod());
        assertTrue(b.spatialMethod().contains("NOT polygon-averaged"));
    }

    @Test
    void blankNameFallsBackToCoordinates() {
        LiveWeatherService.BlockForecast b =
                service().forecastForCentroid("  ", 25.59408, 85.13563);
        assertTrue(b.blockName().startsWith("centroid ("),
                "unnamed centroid labelled by coordinates, got: " + b.blockName());
    }

    @Test
    void outOfRangeCoordinatesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service().forecastForCentroid("X", 95.0, 80.0));
        assertThrows(IllegalArgumentException.class,
                () -> service().forecastForCentroid("X", 25.0, 200.0));
    }

    @Test
    void centroidSamplesAreSinglePoint() {
        BlockSampler.BlockSamples s = BlockSampler.centroidSamples(26.68, 80.98);
        assertEquals(1, s.points().size());
        assertEquals(26.68, s.points().get(0).latitude(), 1e-9);
        assertEquals(BlockSampler.CENTROID_METHOD, s.spatialMethod());
    }

    @Test
    void samplerIncludesNonLegacyKeysWithoutBreakingLegacy() {
        String geoJson = "{\"type\": \"FeatureCollection\", \"features\": ["
                + "{\"type\": \"Feature\", \"properties\": {\"block_name\": \"Sangrur\"},"
                + " \"geometry\": {\"type\": \"Polygon\", \"coordinates\": [[[75,30],[76,30],[76,31],[75,31],[75,30]]]}}"
                + "]}";
        BlockSampler sampler = new BlockSampler(geoJson);
        List<BlockSampler.SampleRef> refs = sampler.allPoints();
        assertEquals(9, refs.size(), "3x3 grid fully inside the square polygon");
        assertTrue(refs.stream().allMatch(r -> r.blockName().equals("Sangrur")));
        assertFalse(refs.get(0).point().latitude() == 0.0);
    }
}
