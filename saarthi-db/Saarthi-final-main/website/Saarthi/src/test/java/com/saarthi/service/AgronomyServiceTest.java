package com.saarthi.service;

import com.saarthi.model.FarmerAnalysisRequest;
import com.saarthi.model.FarmerAnalysisResponse;
import com.saarthi.risks.SoilContext;
import com.saarthi.weather.LiveWeatherService;
import com.saarthi.weather.LiveWeatherService.BlockDaily;
import com.saarthi.weather.LiveWeatherService.BlockForecast;
import com.saarthi.weather.LiveWeatherService.LiveForecast;
import com.saarthi.service.RealForecastService.BlockNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Farmer advisory engine: variation, null honesty and provenance (mocked
 * live forecast — no HTTP, no frozen packages).
 */
class AgronomyServiceTest {

    private static final LocalDate ISSUE = LocalDate.parse("2026-09-18");

    private static BlockForecast blockForecast(String name, boolean wet) {
        List<BlockDaily> days = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            days.add(new BlockDaily(ISSUE.plusDays(i), i,
                    wet ? 8.0 : 0.2, wet ? 70.0 : 10.0,
                    wet ? 29.0 : 36.0, 23.0,
                    wet ? 3.0 : 4.5, wet ? 0.20 : 0.10,
                    wet ? 8.0 : 0.2, 0.0, 2));
        }
        double rain = wet ? 8.0 : 0.2;
        return new BlockForecast(name, days, rain * 3, rain * 7, rain * 15,
                wet ? 21.0 : 31.5, "test_mean", ISSUE, Instant.now());
    }

    private static LiveForecast forecast(BlockForecast... blocks) {
        Map<String, BlockForecast> map = new java.util.LinkedHashMap<>();
        for (BlockForecast b : blocks) map.put(b.blockName(), b);
        return new LiveForecast("Open-Meteo", "ECMWF IFS", ISSUE, Instant.now(), false, null, map);
    }

    private AgronomyService service(LiveWeatherService live) {
        RealForecastService registry = new RealForecastService() {
            @Override public java.util.Optional<Map<String, Object>> findBlock(String id) {
                for (String b : List.of("Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam")) {
                    if (b.equalsIgnoreCase(id == null ? "" : id.trim())) {
                        return java.util.Optional.of(Map.of("block_name", b, "block_id", b));
                    }
                }
                return java.util.Optional.empty();
            }
        };
        AgronomyService s = new AgronomyService(registry, live, new SoilContext());
        s.load(); // unit test: @PostConstruct does not fire
        return s;
    }

    private static LiveWeatherService liveOf(LiveForecast fc) {
        return new LiveWeatherService(null, null) {
            @Override public synchronized LiveForecast getForecast(boolean forceRefresh) { return fc; }
        };
    }

    private static FarmerAnalysisRequest req(String block, String crop,
            String sowing, String irrigation) {
        FarmerAnalysisRequest r = new FarmerAnalysisRequest();
        r.setBlock(block);
        r.setCrop(crop);
        r.setSowingDate(sowing);
        r.setIrrigation(irrigation);
        return r;
    }

    @Test
    void unknownBlockThrows404StyleException() {
        AgronomyService s = service(liveOf(forecast(blockForecast("Sunam", true))));
        assertThrows(BlockNotFoundException.class,
                () -> s.computeFarmerAnalysis(req("Atlantis", "Paddy (PR-126)", null, "Canals")));
        assertThrows(BlockNotFoundException.class,
                () -> s.computeFarmerAnalysis(req("", "Paddy (PR-126)", null, "Canals")));
    }

    @Test
    void wetVsDryForecastProducesDifferentAdvisories() {
        AgronomyService s = service(liveOf(forecast(
                blockForecast("Sunam", true), blockForecast("Moonak", false))));

        FarmerAnalysisResponse wet = s.computeFarmerAnalysis(
                req("Sunam", "Paddy (PR-126)", "2026-06-20", "Canals"));
        FarmerAnalysisResponse dry = s.computeFarmerAnalysis(
                req("Moonak", "Paddy (PR-126)", "2026-06-20", "Canals"));

        assertNotEquals(wet.getWeatherSection().get("rain_7d_mm"),
                dry.getWeatherSection().get("rain_7d_mm"),
                "different blocks must show different live rainfall");
        assertNotEquals(wet.getAdvisory().get("actions"),
                dry.getAdvisory().get("actions"),
                "advisory actions must differ between a wet and a dry forecast");
        assertTrue(((String) dry.getRisks().get("dry_spell_watch")).contains("rain-free"),
                "dry block should carry dry-spell wording");
        // Wet block water balance: rain 56.0 mm - ET0 21.0 mm = 35.0 mm surplus.
        assertEquals(35.0, ((Number) wet.getWaterDemand().get("rain_minus_et0_7d_mm")).doubleValue(), 1e-9);
    }

    @Test
    void noFabricatedNumbersInSections() {
        AgronomyService s = service(liveOf(forecast(blockForecast("Sunam", false))));
        FarmerAnalysisResponse r = s.computeFarmerAnalysis(req("Sunam", "Paddy (PR-126)", null, "Rainfed"));

        Map<String, Object> soil = r.getSoilSection();
        assertEquals(0.10, ((Number) soil.get("forecast_surface_soil_moisture_vwc")).doubleValue(), 1e-9,
                "soil moisture must come from the live feed");
        assertTrue(String.valueOf(soil.get("source")).contains("ECMWF IFS"));

        Map<String, Object> risks = r.getRisks();
        for (Object flag : (List<?>) risks.get("flags")) {
            assertFalse(String.valueOf(flag).contains("%"));
        }
        assertTrue(((String) risks.get("rainfed_caution")).contains("Rainfed"));

        // No sowing date -> stage explicitly unavailable, never guessed.
        Map<String, Object> stage = r.getStage();
        assertEquals(false, stage.get("available"));
        assertNull(stage.get("stage"));

        // Sources must cite the live contract and PAU/ICAR, never CHIRPS
        // (the integrity note may name it only as an exclusion).
        String all = r.getSources().toString();
        assertTrue(all.contains("/api/weather/forecast/"));
        assertTrue(all.contains("PAU"));
        String weatherAndAgronomy = (r.getSources().get("weather").toString())
                + (r.getSources().get("agronomy").toString());
        assertFalse(weatherAndAgronomy.toUpperCase().contains("CHIRPS"));
    }

    @Test
    void stageResolvesForCitedCropWithSowingDate() {
        AgronomyService s = service(liveOf(forecast(blockForecast("Sunam", true))));
        // PR-126 sown 40 days ago -> inside the cited tillering band (15-45).
        FarmerAnalysisResponse r = s.computeFarmerAnalysis(
                req("Sunam", "Paddy (PR-126)", LocalDate.now().minusDays(40).toString(), "Canals"));
        assertEquals(true, r.getStage().get("available"));
        assertEquals("tillering", r.getStage().get("stage"));
    }

    @Test
    void unknownCropIsExplicitlyNotAvailable() {
        AgronomyService s = service(liveOf(forecast(blockForecast("Sunam", true))));
        FarmerAnalysisResponse r = s.computeFarmerAnalysis(
                req("Sunam", "Dragon Fruit", "2026-06-20", "Canals"));
        assertEquals(false, r.getCropSection().get("known"));
        assertEquals(false, r.getStage().get("available"));
    }
}
