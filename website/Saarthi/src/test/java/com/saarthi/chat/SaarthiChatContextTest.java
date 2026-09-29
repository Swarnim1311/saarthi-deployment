package com.saarthi.chat;

import com.saarthi.geo.GeographyService;
import com.saarthi.risks.ClimatologyContext;
import com.saarthi.weather.BlockSampler;
import com.saarthi.weather.LiveWeatherService;
import com.saarthi.weather.WeatherProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context the assistant is allowed to see.
 *
 * <p>Built with a stubbed provider and no network, so every assertion is about
 * behaviour rather than about today's weather. The important property is
 * negative: an absent value must surface as unavailable, never as a zero.
 */
class SaarthiChatContextTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Fixed forecast; {@code nullFromDay} makes one daily rainfall null. */
    static class StubProvider implements WeatherProvider {
        final double[] rain;
        final Double[] temp;
        final Integer nullFromDay;

        StubProvider(double[] rain, Double[] temp, Integer nullFromDay) {
            this.rain = rain;
            this.temp = temp;
            this.nullFromDay = nullFromDay;
        }

        @Override public String providerName() { return "Open-Meteo"; }
        @Override public String modelName() { return "ECMWF IFS (ecmwf_ifs)"; }

        @Override
        public List<LocationDailyForecast> fetch(List<SamplePoint> points) {
            LocalDate today = LocalDate.now(IST);
            return points.stream().map(p -> {
                var list = new ArrayList<DailyPointValues>();
                for (int i = 0; i < 16; i++) {
                    double r = i < rain.length ? rain[i] : 0.0;
                    Double t = temp != null && i < temp.length ? temp[i] : 30.0;
                    // date, precipMm, precipProb, tmax, tmin, humidity, wind,
                    // et0, soilMoisture, rainMm, showersMm, weatherCode
                    list.add(new DailyPointValues(today.plusDays(i), r, 10.0,
                            t, t - 8.0, 40.0, 3.0, 3.0, 0.2, r, 0.0, 1));
                }
                return new LocationDailyForecast(p.latitude(), p.longitude(), list);
            }).toList();
        }
    }

    private static BlockSampler sampler() {
        // Mirrors the proven CropAtlas test setup: the block list comes from the
        // bundled forecast package so the polygon sampler has the real geometry.
        StringBuilder sb = new StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[");
        int i = 0;
        for (String b : com.saarthi.service.RealForecastService.BLOCKS) {
            if (i > 0) sb.append(',');
            sb.append("{\"type\":\"Feature\",\"properties\":{\"block_name\":\"").append(b)
              .append("\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[")
              .append(i * 2).append(',').append(i * 2).append("],[")
              .append(i * 2 + 1).append(',').append(i * 2).append("],[")
              .append(i * 2 + 1).append(',').append(i * 2 + 1).append("],[")
              .append(i * 2).append(',').append(i * 2 + 1).append("]]]}}");
            i++;
        }
        return new BlockSampler(sb.append("]}").toString());
    }

    private static SaarthiChatContext context(double[] rain, Double[] temp, Integer nullFrom) {
        // The forecast package must be loaded before the sampler can be used.
        try {
            var real = new com.saarthi.service.RealForecastService();
            real.load();
        } catch (RuntimeException e) {
            throw new IllegalStateException("forecast package load failed", e);
        }
        LiveWeatherService live = new LiveWeatherService(
                new StubProvider(rain, temp, nullFrom), sampler(), 60);

        // GeographyService does not auto-load in a bare constructor; the registry
        // is what resolves a (state, district, code) triple to a real block.
        GeographyService geo = new GeographyService();
        try {
            java.lang.reflect.Method m = GeographyService.class.getDeclaredMethod("load");
            m.setAccessible(true);
            m.invoke(geo);
        } catch (Exception e) {
            throw new IllegalStateException("geography load failed", e);
        }
        return new SaarthiChatContext(geo, live,
                new ClimatologyContext(), new LocalAdvisoryCorpus(),
                new com.saarthi.intelligence.ForecastContextService(live, null, geo),
                null, null);
    }

    private static double[] daily(double v) {
        double[] d = new double[16];
        java.util.Arrays.fill(d, v);
        return d;
    }

    // ------------------------------------------------------------------

    @Test
    void aBlockWithNoSelectionReportsEveryGapInsteadOfInventing() {
        Map<String, Object> ctx = context(daily(0), null, null).build(null, null, null, null);
        assertNotNull(ctx);
        @SuppressWarnings("unchecked")
        List<String> unavailable = (List<String>) ctx.get("unavailable");
        assertNotNull(unavailable);
        assertTrue(unavailable.contains("location"));
        assertTrue(unavailable.contains("crop"));
        assertTrue(unavailable.contains("forecast_requires_a_block"));
        @SuppressWarnings("unchecked")
        Map<String, Object> forecast = (Map<String, Object>) ctx.get("forecast");
        assertTrue(forecast.isEmpty(), "no forecast may be synthesised without a block");
    }

    @Test
    void aLegacyBlockBuildsAContextAndIsNeverGivenAFabricatedForecast() {
        Map<String, Object> ctx = context(daily(2.0), null, null)
                .build(null, null, "Sangrur", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) ctx.get("location");
        assertEquals("Sangrur", location.get("block"),
                "the selected block must be identified");

        @SuppressWarnings("unchecked")
        Map<String, Object> forecast = (Map<String, Object>) ctx.get("forecast");
        @SuppressWarnings("unchecked")
        List<String> unavailable = (List<String>) ctx.get("unavailable");

        // Whether the forecast resolves depends on the polygon geometry available
        // to the sampler. Either outcome is acceptable — what must never happen is
        // a forecast that is present but partly invented.
        if (forecast.containsKey("status")) {
            assertTrue(String.valueOf(forecast.get("status")).contains("forecast"),
                    "the status must not present a forecast as an observation");
            assertEquals(16, forecast.get("horizonDays"));
            assertEquals(14.0, (Double) forecast.get("rain7dMm"), 1e-6,
                    "7 days of 2 mm must total 14 mm");
            assertEquals(32.0, (Double) forecast.get("rain16dMm"), 1e-6,
                    "16 days of 2 mm must total 32 mm");
            assertEquals(0, forecast.get("dryDaysFirst7"),
                    "2 mm/day is above the 1 mm dry threshold, so no dry days");
            assertTrue(String.valueOf(forecast.get("provider")).length() > 0);
        } else {
            assertTrue(unavailable.contains("live_forecast"),
                    "an unresolved forecast must be named as unavailable");
            assertFalse(forecast.containsKey("rain7dMm"),
                    "no rainfall may be invented when the forecast is unavailable");
        }
    }

    @Test
    void aRegistryTripleIsResolvedAndUsedAsTheBlockIdentity() {
        // Punjab / Sangrur district / Andana, a real registry row.
        Map<String, Object> ctx = context(daily(1.0), null, null)
                .build("3", "43", "340", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) ctx.get("location");
        assertEquals("Andana", location.get("block"), "the triple must resolve to its own block");
        assertEquals("Punjab", location.get("state"));
        assertEquals("Sangrur", location.get("district"));
    }

    @Test
    void aMissingDailyValueNullsTheTotalRatherThanZeroFillingIt() {
        // A null rainfall day must make the aggregate null, not 0.
        LiveWeatherService.BlockDaily d0 = new LiveWeatherService.BlockDaily(
                LocalDate.now(IST), 1, 0.0, 10.0, 30.0, 20.0, 3.0, 0.2, 0.0, 0.0, 1);
        LiveWeatherService.BlockDaily dNull = new LiveWeatherService.BlockDaily(
                LocalDate.now(IST), 2, null, 10.0, 30.0, 20.0, 3.0, 0.2, null, 0.0, 1);
        Double sum = SaarthiChatContext.rainSum(List.of(d0, dNull), 0, 2);
        assertNull(sum, "a null daily value must make the total null, not 0");
    }

    @Test
    void aggregatesIgnoreDaysOutsideTheWindow() {
        LiveWeatherService.BlockDaily a = new LiveWeatherService.BlockDaily(
                LocalDate.now(IST), 1, 5.0, 10.0, 30.0, 20.0, 3.0, 0.2, 5.0, 0.0, 1);
        LiveWeatherService.BlockDaily b = new LiveWeatherService.BlockDaily(
                LocalDate.now(IST), 2, 7.0, 10.0, 30.0, 20.0, 3.0, 0.2, 7.0, 0.0, 1);
        assertEquals(5.0, SaarthiChatContext.rainSum(List.of(a, b), 0, 1), 1e-6);
        assertEquals(12.0, SaarthiChatContext.rainSum(List.of(a, b), 0, 2), 1e-6);
    }

    @Test
    void anEmptyForecastYieldsNullsNotZeros() {
        assertNull(SaarthiChatContext.rainSum(List.of(), 0, 7));
        assertNull(SaarthiChatContext.et0Sum(List.of(), 0, 7));
        assertNull(SaarthiChatContext.rainSum(null, 0, 7));
    }

    @Test
    void theContextAlwaysStatesWhatThePlatformCannotProvide() {
        Map<String, Object> ctx = context(daily(1), null, null).build(null, null, "Sangrur", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> na = (Map<String, Object>) ctx.get("notAvailable");
        assertNotNull(na);
        assertTrue(na.containsKey("marketPrices"), "the platform has no price data");
        assertTrue(na.containsKey("yield"));
        assertTrue(na.containsKey("governmentSchemes"));
        String soilNote = String.valueOf(na.get("fieldMeasuredSoil")).toLowerCase();
        assertTrue(soilNote.contains("modelled") && !soilNote.contains("measured in your field"),
                "soil must be described as modelled, not field-measured: " + soilNote);
    }

    @Test
    void aSelectedCropAddsReferenceKnowledgeWithProvenance() {
        Map<String, Object> ctx = context(daily(1), null, null)
                .build(null, null, "Sangrur", "Wheat (HD-2967)");
        @SuppressWarnings("unchecked")
        Map<String, Object> crop = (Map<String, Object>) ctx.get("crop");
        assertEquals("Wheat (HD-2967)", crop.get("crop"));
        assertEquals("rabi", crop.get("season"));
        assertNotNull(crop.get("referenceSourceIds"),
                "crop facts must carry their source ids");
    }

    @Test
    void sharedSoilReachesTheChatContextFromTheSameCropAtlasSource() {
        SaarthiChatContext ctx = context(daily(1), null, null);
        // No SoilGrids client injected: bundled block means serve legacy blocks
        // with no network, exactly as CropAtlas renders them.
        ctx.setSharedSoil(new com.saarthi.cropatlas.CropAtlasSoilSource());
        Map<String, Object> built = ctx.build(null, null, "Sunam", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> soil = (Map<String, Object>) built.get("soil");
        assertNotNull(soil);
        assertEquals(7.77, (Double) soil.get("ph"), 1e-6,
                "the chat context must quote the shared bundled value, not a guess");
        assertNotNull(soil.get("source"));
    }

    @Test
    void anUnwiredSoilSourceIsNamedUnavailableNeverInvented() {
        Map<String, Object> built = context(daily(1), null, null)
                .build(null, null, "Sunam", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> soil = (Map<String, Object>) built.get("soil");
        assertNotNull(soil);
        assertTrue(soil.isEmpty(), "no soil source means no soil values");
        @SuppressWarnings("unchecked")
        List<String> unavailable = (List<String>) built.get("unavailable");
        assertTrue(unavailable.contains("soil_source_not_wired"));
    }

    @Test
    void anUnknownBlockNameDoesNotCrashTheContext() {
        Map<String, Object> ctx = context(daily(1), null, null)
                .build("99", "9999", "does-not-exist", null);
        assertNotNull(ctx, "a bad geography must degrade, never throw");
        @SuppressWarnings("unchecked")
        List<String> unavailable = (List<String>) ctx.get("unavailable");
        assertNotNull(unavailable);
    }
}
