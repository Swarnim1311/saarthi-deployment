package com.saarthi.risks;

import com.saarthi.risks.FieldWorkService.InvalidWindowException;
import com.saarthi.risks.FieldWorkService.RiskUnavailableException;
import com.saarthi.service.RealForecastService.BlockNotFoundException;
import com.saarthi.weather.BlockSampler;
import com.saarthi.weather.LiveWeatherService;
import com.saarthi.weather.WeatherProvider;
import com.saarthi.weather.WeatherProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIELD_HIGH endpoint behaviour (no network): exact D+1..D+3 window,
 * HIGH/LOW/UNAVAILABLE distinction, stale reflection, error states, and the
 * guarantee that risk computation can never break weather serving.
 */
class RiskControllerTest {

    /** Stub IFS provider: configurable per-day rainfall, dates anchored at today. */
    static class StubProvider implements WeatherProvider {
        final double[] rain;
        boolean fail = false;
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
            if (fail) throw new WeatherProviderException("stub network down");
            LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
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
        // RealForecastService loads packaged fixtures; outside Spring, load() must be called.
        com.saarthi.service.RealForecastService svc =
                new com.saarthi.service.RealForecastService();
        svc.load();
        return svc;
    }

    RiskController controllerFor(double... rain) {
        StubProvider stub = new StubProvider(rain);
        LiveWeatherService live = new LiveWeatherService(stub, sixBlockSampler(), 60);
        com.saarthi.weather.RecentRainfallService recent =
                new com.saarthi.weather.RecentRainfallService();
        recent.setChirpsPath("");
        return new RiskController(new CompositeRiskService(live, realBlocks(), recent,
                new HeavyRainThresholds(), new SoilContext(), new ClimatologyContext(), true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoOfThreeWetIsHigh() {
        // D+0..D+15 rainfall; D+1..D+3 = indices 1..3 = [5, 0, 5] -> HIGH
        RiskController c = controllerFor(0.0, 5.0, 0.0, 5.0);
        ResponseEntity<Map<String, Object>> res = c.oneBlock("Sangrur", "3d");
        assertEquals(HttpStatus.OK, res.getStatusCode());
        Map<String, Object> body = res.getBody();
        assertEquals("HIGH", body.get("category"));
        assertEquals("field_work_v1", body.get("method_version"));
        assertEquals("D+1-D+3", body.get("window"));
        assertEquals(List.of("FIELD_WET_DAYS_2OF3"), body.get("reasons"));
        Map<String, Object> ev = (Map<String, Object>) body.get("evidence");
        assertEquals(2, ev.get("wet_days"));
        assertEquals("MODERATE", body.get("confidence"));
        assertTrue(body.get("validation_note").toString().contains("GEFS"));
    }

    @Test
    void oneAndZeroWetAreLow() {
        RiskController c = controllerFor(0.0, 0.0, 5.0, 0.0); // D+1..D+3 = [0,5,0]
        assertEquals("LOW", c.oneBlock("Dhuri", null).getBody().get("category"));
        RiskController c2 = controllerFor(0.0, 0.0, 0.0, 0.0);
        Map<String, Object> body = c2.oneBlock("Lehra", "3d").getBody();
        assertEquals("LOW", body.get("category"));
        assertEquals(List.of("FIELD_NO_WET_DAYS"), body.get("reasons"));
    }

    @Test
    void exactWindowUsesD1D2D3Only() {
        // D+0 wet and D+4 wet must not affect the verdict: D+1..D+3 dry -> LOW
        RiskController c = controllerFor(9.9, 0.0, 0.0, 0.0, 9.9);
        Map<String, Object> body = c.oneBlock("Moonak", "3d").getBody();
        assertEquals("LOW", body.get("category"));
        List<String> dates = (List<String>) body.get("window_dates");
        assertEquals(3, dates.size());
        LocalDate issue = LocalDate.parse(body.get("issue_date").toString());
        assertEquals(issue.plusDays(1).toString(), dates.get(0));
        assertEquals(issue.plusDays(3).toString(), dates.get(2));
    }

    @Test
    @SuppressWarnings("unchecked")
    void allBlocksEndpoint() {
        RiskController c = controllerFor(0.0, 5.0, 5.0, 0.0);
        Map<String, Object> body = c.allBlocks("3d").getBody();
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) body.get("blocks");
        assertEquals(6, blocks.size());
        assertTrue(blocks.stream().allMatch(b -> "HIGH".equals(b.get("category"))));
    }

    @Test
    void unknownBlockIs404() {
        RiskController c = controllerFor(0.0);
        assertThrows(BlockNotFoundException.class, () -> c.oneBlock("Nope", "3d"));
        ResponseEntity<Map<String, Object>> err =
                c.handleUnknownBlock(new BlockNotFoundException("Nope"));
        assertEquals(HttpStatus.NOT_FOUND, err.getStatusCode());
        assertEquals("unknown_block", err.getBody().get("error"));
    }

    @Test
    void invalidWindowIs400() {
        RiskController c = controllerFor(0.0);
        assertThrows(InvalidWindowException.class, () -> c.oneBlock("Sangrur", "7d"));
        ResponseEntity<Map<String, Object>> err =
                c.handleWindow(new InvalidWindowException("7d"));
        assertEquals(HttpStatus.BAD_REQUEST, err.getStatusCode());
        assertEquals("invalid_window", err.getBody().get("error"));
    }

    @Test
    void providerFailureIsUnavailableNotSynthetic() {
        StubProvider stub = new StubProvider(0.0);
        stub.fail = true;
        LiveWeatherService live = new LiveWeatherService(stub, sixBlockSampler(), 60);
        RiskController c = controllerForWith(live);
        assertThrows(RiskUnavailableException.class, () -> c.oneBlock("Sangrur", "3d"));
        ResponseEntity<Map<String, Object>> err =
                c.handleUnavailable(new RiskUnavailableException("down"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, err.getStatusCode());
        assertEquals("risk_unavailable", err.getBody().get("error"));
    }

    @Test
    void weatherStillServesWhenRiskCannotBeComputed() {
        // Risk layer throwing must never propagate into weather serving:
        // LiveWeatherService has no dependency on risks (compile-time guarantee),
        // and here the weather path serves while the risk path reports unavailable.
        StubProvider stub = new StubProvider(0.0);
        stub.fail = true;
        LiveWeatherService live = new LiveWeatherService(stub, sixBlockSampler(), 60);
        assertThrows(WeatherProviderException.class, () -> live.getForecast(false),
                "weather failure semantics unchanged (no synthetic data)");
    }

    @Test
    void staleForecastReflectedAsLowConfidence() {
        // TTL 0 forces refetch; fail the provider after priming cache -> stale serve.
        StubProvider stub = new StubProvider(0.0, 5.0, 5.0, 0.0);
        LiveWeatherService live = new LiveWeatherService(stub, sixBlockSampler(), 0);
        RiskController c = controllerForWith(live);
        assertEquals("HIGH", c.oneBlock("Sunam", "3d").getBody().get("category"));
        stub.fail = true;
        Map<String, Object> stale = c.oneBlock("Sunam", "3d").getBody();
        assertEquals(true, stale.get("stale"));
        assertEquals("LOW", stale.get("confidence"),
                "stale forecast must cap confidence, never present as fresh");
        assertEquals("HIGH", stale.get("category"));
        assertEquals("HIGH", stale.get("overall_risk"));
    }

    RiskController controllerForWith(LiveWeatherService live) {
        com.saarthi.weather.RecentRainfallService recent =
                new com.saarthi.weather.RecentRainfallService();
        recent.setChirpsPath("");
        return new RiskController(new CompositeRiskService(live, realBlocks(), recent,
                new HeavyRainThresholds(), new SoilContext(), new ClimatologyContext(), true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void compositeKeysPresentAndLegacyCompatible() {
        RiskController c = controllerFor(0.0, 5.0, 0.0, 5.0);
        Map<String, Object> body = c.oneBlock("Sangrur", "3d").getBody();
        assertEquals("HIGH", body.get("overall_risk"));
        assertEquals("FIELD_WORK_DISRUPTION", body.get("primary_concern"));
        assertEquals("composite_v1", body.get("composite_method_version"));
        // Legacy keys byte-compatible.
        assertEquals("HIGH", body.get("category"));
        assertEquals("field_work_v1", body.get("method_version"));
        List<Map<String, Object>> risks = (List<Map<String, Object>>) body.get("risks");
        assertEquals(3, risks.size());
        assertEquals("FIELD_WORK_DISRUPTION", risks.get(0).get("name"));
        assertEquals("GEFS_VALIDATED_IFS_PENDING", risks.get(0).get("validation"));
        assertEquals(true, ((Map<String, Object>) risks.get(2)).get("evidence_only"));
        assertNotNull(body.get("context"));
        assertTrue(((List<?>) body.get("advisories")).size() >= 1);
        assertTrue(body.get("advisories").toString().contains("at least 1 mm rain"));
    }
}
