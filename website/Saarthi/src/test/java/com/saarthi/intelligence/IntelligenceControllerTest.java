package com.saarthi.intelligence;

import com.saarthi.geo.GeographyService;
import com.saarthi.service.RealForecastService;
import com.saarthi.weather.BlockSampler;
import com.saarthi.weather.LiveWeatherService;
import com.saarthi.weather.WeatherProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BUILD 1 controller tests with a stub provider (no network).
 */
class IntelligenceControllerTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static class StubProvider implements WeatherProvider {
        final double[] rain;

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
            LocalDate today = LocalDate.now(IST);
            return points.stream().map(p -> {
                var list = new java.util.ArrayList<DailyPointValues>();
                for (int i = 0; i < 16; i++) {
                    double r = i < rain.length ? rain[i] : 0.0;
                    list.add(new DailyPointValues(today.plusDays(i), r, 10.0,
                            32.0, 24.0, 60.0, 10.0, 4.0, 0.2, r, 0.0, 1));
                }
                return new LocationDailyForecast(p.latitude(), p.longitude(), list);
            }).toList();
        }
    }

    static BlockSampler sixBlockSampler() {
        StringBuilder sb = new StringBuilder(
                "{\"type\": \"FeatureCollection\", \"features\": [");
        int i = 0;
        for (String b : RealForecastService.BLOCKS) {
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

    IntelligenceController controller(double... rain) {
        RealForecastService real = new RealForecastService();
        real.load();
        LiveWeatherService live = new LiveWeatherService(new StubProvider(rain),
                sixBlockSampler(), 60);
        GeographyService geo = new GeographyService();
        try {
            java.lang.reflect.Method m = GeographyService.class.getDeclaredMethod("load");
            m.setAccessible(true);
            m.invoke(geo);
        } catch (Exception e) {
            throw new IllegalStateException("geography load failed", e);
        }
        ForecastContextService ctxSvc =
                new ForecastContextService(live, real, geo, new com.saarthi.risks.SoilContext());
        SectorThresholds th = new SectorThresholds();
        SectorRiskService sectors = new SectorRiskService(th, new com.saarthi.risks.HeavyRainThresholds());
        GridGroundwaterService grid = new GridGroundwaterService(th);
        BlockAgriAdvisoryService advisory =
                new BlockAgriAdvisoryService(new com.saarthi.risks.HeavyRainThresholds());
        return new IntelligenceController(ctxSvc, sectors, grid, advisory);
    }

    @Test
    void validBlockServesAllTrees() {
        IntelligenceController c = controller(0, 0, 0, 0, 0, 0, 0);
        ResponseEntity<Map<String, Object>> r = c.risks("Sangrur", null, null, null, null, null, null);
        assertEquals(200, r.getStatusCode().value());
        Map<String, Object> sectors = (Map<String, Object>) r.getBody().get("sectors");
        assertEquals(4, sectors.size());
        ResponseEntity<Map<String, Object>> g = c.gridGroundwater(
                "Sangrur", null, null, null, null, null, null);
        assertEquals(200, g.getStatusCode().value());
        ResponseEntity<Map<String, Object>> lw = c.logisticsWarehouse(
                "Sangrur", null, null, null, null, null, null);
        assertEquals(200, lw.getStatusCode().value());
        assertEquals(2, ((Map<String, Object>) lw.getBody().get("sectors")).size());
        ResponseEntity<Map<String, Object>> ctx = c.context(
                "Sangrur", null, null, null, null, null, null);
        assertEquals(200, ctx.getStatusCode().value());
        assertTrue(ctx.getBody().containsKey("context"));
    }

    @Test
    void unknownBlockAndBadRequest() {
        IntelligenceController c = controller(0, 0, 0, 0, 0, 0, 0);
        assertThrows(RealForecastService.BlockNotFoundException.class,
                () -> c.risks("Nope", null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> c.risks(null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> c.risks(null, null, null, null, 10.0, null, null));
        ResponseEntity<Map<String, Object>> nf = c.handleUnknownBlock(
                new RealForecastService.BlockNotFoundException("Nope"));
        assertEquals(404, nf.getStatusCode().value());
        assertEquals("unknown_block", nf.getBody().get("error"));
        ResponseEntity<Map<String, Object>> bad = c.handleBadRequest(
                new IllegalArgumentException("bad"));
        assertEquals(400, bad.getStatusCode().value());
    }
}
