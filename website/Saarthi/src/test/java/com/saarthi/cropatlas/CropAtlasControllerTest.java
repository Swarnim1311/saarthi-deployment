package com.saarthi.cropatlas;

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
 * CropAtlas controller contract with a stub provider (no network): selection
 * modes, payload shape, error semantics and the no-fallback guarantee.
 */
class CropAtlasControllerTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Fixed rainfall series. {@code nullRain} is retained for engine-level tests
     *  only: the shared LiveWeatherService aggregates cumulatives itself, so a
     *  null daily value is exercised through the CropAtlas layer, not here. */
    static class StubProvider implements WeatherProvider {
        final double[] rain;

        StubProvider(boolean nullRain, double... rain) {
            this.rain = rain;
        }

        @Override
        public String providerName() { return "Open-Meteo"; }

        @Override
        public String modelName() { return "ECMWF IFS (ecmwf_ifs)"; }

        @Override
        public List<LocationDailyForecast> fetch(List<SamplePoint> points) {
            LocalDate today = LocalDate.now(IST);
            return points.stream().map(p -> {
                var list = new java.util.ArrayList<DailyPointValues>();
                for (int i = 0; i < 16; i++) {
                    double r = i < rain.length ? rain[i] : 0.0;
                    list.add(new DailyPointValues(today.plusDays(i), r, 40.0,
                            26.0, 12.0, 60.0, 8.0, 3.0, 0.2, r, 0.0, 1));
                }
                return new LocationDailyForecast(p.latitude(), p.longitude(), list);
            }).toList();
        }
    }

    static BlockSampler sixBlockSampler() {
        StringBuilder sb = new StringBuilder("{\"type\": \"FeatureCollection\", \"features\": [");
        int i = 0;
        for (String b : RealForecastService.BLOCKS) {
            if (i > 0) sb.append(',');
            sb.append("{\"type\": \"Feature\", \"properties\": {\"block_name\": \"")
                    .append(b).append("\"}, \"geometry\": {\"type\": \"Polygon\", ")
                    .append("\"coordinates\": [[[").append(i * 2).append(',').append(i * 2)
                    .append("],[").append(i * 2 + 1).append(',').append(i * 2)
                    .append("],[").append(i * 2 + 1).append(',').append(i * 2 + 1)
                    .append("],[").append(i * 2).append(',').append(i * 2 + 1)
                    .append("],[").append(i * 2).append(',').append(i * 2).append("]]]}}");
            i++;
        }
        return new BlockSampler(sb.append("]}").toString());
    }

    CropAtlasController controller(boolean nullRain, double... rain) {
        return controllerWithElevation(null, nullRain, rain);
    }

    CropAtlasController controllerWithElevation(String elevationBaseUrl,
            boolean nullRain, double... rain) {
        RealForecastService real = new RealForecastService();
        real.load();
        LiveWeatherService live = new LiveWeatherService(new StubProvider(nullRain, rain),
                sixBlockSampler(), 60);
        GeographyService geo = new GeographyService();
        try {
            java.lang.reflect.Method m = GeographyService.class.getDeclaredMethod("load");
            m.setAccessible(true);
            m.invoke(geo);
        } catch (Exception e) {
            throw new IllegalStateException("geography load failed", e);
        }
        // No SoilGrids client injected: soil must fall back to the bundled block
        // means for legacy blocks, and report unavailable for anything else.
        CropAtlasSoilSource soil = new CropAtlasSoilSource();
        CropAtlasService atlas = new CropAtlasService(live, geo, new com.saarthi.risks.ClimatologyContext(),
                soil);
        if (elevationBaseUrl != null) {
            atlas.setElevation(new ElevationClient(elevationBaseUrl, 10, 168,
                    java.net.http.HttpClient.newHttpClient()));
        }
        CropRequirements reqs = new CropRequirements();
        CropAtlasMethod method = new CropAtlasMethod();
        CropSuitabilityService suitability = new CropSuitabilityService(reqs, method, atlas);
        return new CropAtlasController(atlas,
                suitability, reqs, method,
                new GlobalRegionMatcher(), new CropConditionMatchService(suitability, reqs));
    }

    @Test
    @SuppressWarnings("unchecked")
    void contextServesFingerprintForALegacyBlock() {
        CropAtlasController c = controller(false, 4, 2, 0, 6, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0);
        ResponseEntity<Map<String, Object>> r = c.context(null, null, null, "Sangrur");
        assertEquals(200, r.getStatusCode().value());
        Map<String, Object> body = r.getBody();
        assertNotNull(body.get("location"));
        Map<String, Object> fp = (Map<String, Object>) body.get("fingerprint");
        assertNotNull(fp.get("climate"));
        assertNotNull(fp.get("soil"));
        assertNotNull(fp.get("climatology"));
        assertNotNull(fp.get("sources"));
        assertTrue(fp.containsKey("missing"), "missing dimensions must be listed");
        assertNotNull(body.get("methodology"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void contextResolvesARegistryTripleToItsOwnBlock() {
        CropAtlasController c = controller(false, 4, 2, 0, 6, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0);
        // Punjab(3) / Sangrur district(43) / block 340 = Andana. A triple must
        // resolve to ITS OWN block and never fall back to Sangrur.
        ResponseEntity<Map<String, Object>> r = c.context("3", "43", "340", null);
        assertEquals(200, r.getStatusCode().value());
        Map<String, Object> loc = (Map<String, Object>) r.getBody().get("location");
        assertEquals("Andana", loc.get("block_name"));
        assertEquals("340", loc.get("block_code"));
        assertEquals("3", loc.get("state_code"));
        assertEquals("43", loc.get("district_code"));
        assertNotEquals("Sangrur", loc.get("block_name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tripleAndLegacyBlockCanCoexistWithoutCrossContamination() {
        CropAtlasController c = controller(false, 4, 2, 0, 6, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0);
        Map<String, Object> triple = (Map<String, Object>)
                c.context("3", "43", "340", null).getBody().get("location");
        Map<String, Object> legacy = (Map<String, Object>)
                c.context(null, null, null, "Sangrur").getBody().get("location");
        assertEquals("Andana", triple.get("block_name"));
        assertEquals("Sangrur", legacy.get("block_name"));
    }

    @Test
    void recommendationsServeBandedCandidatesAndHonestMethodology() {
        CropAtlasController c = controller(false, 4, 2, 0, 6, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0);
        ResponseEntity<Map<String, Object>> r = c.recommendations(null, null, null, "Sangrur");
        assertEquals(200, r.getStatusCode().value());
        Map<String, Object> body = r.getBody();
        assertNotNull(body.get("groups"));
        assertNotNull(body.get("candidates"));
        assertNotNull(body.get("fingerprint"));
        assertNotNull(body.get("global_comparison"));
        Map<String, Object> methodology = (Map<String, Object>) body.get("methodology");
        assertEquals(false, methodology.get("is_ml_prediction"));
        assertEquals(false, methodology.get("is_probability"));
        assertEquals(false, methodology.get("is_yield_guarantee"));
        assertNotNull(methodology.get("not_scored_dimensions"));
    }

    @Test
    void cropsEndpointDescribesTheDynamicSource() {
        CropAtlasController c = controller(false, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        ResponseEntity<Map<String, Object>> r = c.crops();
        assertEquals(200, r.getStatusCode().value());
        assertEquals(true, r.getBody().get("dynamic"),
                "no static crop catalogue may be served any more");
        assertEquals("FAO ECOCROP", r.getBody().get("source"));
        assertNotNull(r.getBody().get("source_url"));
        assertFalse(r.getBody().containsKey("crops"),
                "a per-block dynamic source has no global crop list");
    }

    @Test
    void catalogEndpointReportsSourceAndCacheState() {
        CropAtlasController c = controller(false, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        ResponseEntity<Map<String, Object>> r = c.catalog();
        assertEquals(200, r.getStatusCode().value());
        assertEquals("FAO ECOCROP", r.getBody().get("source"));
        assertEquals(true, r.getBody().get("dynamic"));
        assertEquals(false, r.getBody().get("discovery_wired"));
        assertNotNull(r.getBody().get("retrieved_at"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recommendationsWithoutDiscoveryAreUnavailableWithZeroCandidates() {
        CropAtlasController c = controller(false, 4, 2, 0, 6, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0);
        ResponseEntity<Map<String, Object>> r = c.recommendations(null, null, null, "Sangrur");
        assertEquals(200, r.getStatusCode().value());
        Map<String, Object> body = r.getBody();
        assertEquals(0, body.get("candidate_count"));
        assertTrue(((List<?>) body.get("candidates")).isEmpty());
        Map<String, Object> eco = (Map<String, Object>) body.get("ecocrop");
        assertEquals(false, eco.get("available"));
        String dumped = body.toString().toLowerCase(java.util.Locale.ROOT);
        for (String legacy : List.of("paddy", "basmati", "sugarcane", "maize", "cotton", "wheat")) {
            assertFalse(dumped.contains(legacy),
                    "the old six-crop catalogue must never leak into the payload: " + legacy);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void recommendationsWithDiscoveryServeDynamicCandidates() throws Exception {
        byte[] hits;
        try (var in = new org.springframework.core.io.ClassPathResource(
                "ecocrop/search-3hits.html").getInputStream()) {
            hits = in.readAllBytes();
        }
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/cropSearchForm", ex -> {
            byte[] body = "<html></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=STUB; Path=/; HttpOnly");
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/cropSearch", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/html;charset=UTF-8");
            ex.sendResponseHeaders(200, hits.length);
            try (var os = ex.getResponseBody()) {
                os.write(hits);
            }
        });
        server.createContext("/cropView", ex -> {
            ex.sendResponseHeaders(404, 0);
            ex.getResponseBody().close();
        });
        server.start();
        try {
            String ecoBase = "http://127.0.0.1:" + server.getAddress().getPort();
            CropAtlasController c = controller(false, 4, 2, 0, 6, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0);
            EcocropSearchClient client = new EcocropSearchClient(ecoBase, 10, 120,
                    java.net.http.HttpClient::newHttpClient);
            // Reuse the controller's own atlas so block identity stays real.
            java.lang.reflect.Field atlasField =
                    CropAtlasController.class.getDeclaredField("atlas");
            atlasField.setAccessible(true);
            CropAtlasService atlas = (CropAtlasService) atlasField.get(c);
            c.setDiscovery(new EcocropDiscoveryService(client, new EcocropQueryMapper(), atlas));

            ResponseEntity<Map<String, Object>> r =
                    c.recommendations(null, null, null, "Sangrur");
            assertEquals(200, r.getStatusCode().value());
            Map<String, Object> body = r.getBody();
            List<Map<String, Object>> candidates =
                    (List<Map<String, Object>>) body.get("candidates");
            assertEquals(3, candidates.size(), "candidates are ECOCROP answers, count varies");
            assertEquals(3, body.get("candidate_count"));
            Map<String, Object> first = candidates.get(0);
            assertEquals("Tamarindus indica", first.get("scientific_name"));
            assertEquals("2047", first.get("ecoport_id"));
            assertEquals("FAO ECOCROP", first.get("source"));
            assertEquals("ecocrop_match", first.get("suitability"));
            assertEquals(false, first.get("score_available"));
            Map<String, Object> eco = (Map<String, Object>) body.get("ecocrop");
            assertEquals(true, eco.get("available"));
            Map<String, Object> groups = (Map<String, Object>) body.get("groups");
            assertEquals(3, ((List<?>) groups.get("ecocrop_matches")).size());

            ResponseEntity<Map<String, Object>> cat = c.catalog();
            assertEquals(true, cat.getBody().get("discovery_wired"));
            assertEquals(1, cat.getBody().get("cache_size"),
                    "the served search must have been cached");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingSelectionIsRejectedAndNeverDefaultsToSangrur() {
        CropAtlasController c = controller(false, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> c.context(null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> c.context("3", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> c.context("3", "43", null, null));
    }

    @Test
    void blankBlockIsTreatedAsAbsent() {
        CropAtlasController c = controller(false, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> c.context(null, null, null, "   "));
    }

    @Test
    void unknownBlockYields404WithNoFallback() {
        CropAtlasController c = controller(false, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        assertThrows(RuntimeException.class,
                () -> c.context(null, null, null, "Nowhere-Village-9999"));
        ResponseEntity<Map<String, Object>> nf = c.unknownGeography(
                new com.saarthi.geo.GeographyController.UnknownGeographyException(
                        "unknown_block", "Unknown block"));
        assertEquals(404, nf.getStatusCode().value());
        assertEquals("unknown_block", nf.getBody().get("error"));
        assertEquals(true, nf.getBody().get("no_fallback"));
    }

    @Test
    void unknownTripleYields404() {
        CropAtlasController c = controller(false, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        assertThrows(com.saarthi.geo.GeographyController.UnknownGeographyException.class,
                () -> c.context("99", "9999", "999999", null));
    }

    @Test
    void errorResponsesDeclareNoFallback() {
        CropAtlasController c = controller(false, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1);
        ResponseEntity<Map<String, Object>> bad =
                c.badRequest(new IllegalArgumentException("no selection"));
        assertEquals(400, bad.getStatusCode().value());
        assertEquals("bad_request", bad.getBody().get("error"));
        assertEquals(Boolean.TRUE, bad.getBody().get("no_fallback"));

        ResponseEntity<Map<String, Object>> unavailable = c.forecastUnavailable(
                new com.saarthi.weather.WeatherController.WeatherUnavailableException("down"));
        assertEquals(503, unavailable.getStatusCode().value());
        assertEquals("forecast_unavailable", unavailable.getBody().get("error"));

        ResponseEntity<Map<String, Object>> provider = c.providerError(
                new com.saarthi.weather.WeatherProviderException("upstream"));
        assertEquals(502, provider.getStatusCode().value());
        assertEquals("provider_error", provider.getBody().get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fingerprintAlwaysDeclaresUnsourcedAndUnavailableDimensions() {
        CropAtlasController c = controller(false, 4, 2, 0, 6, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0);
        ResponseEntity<Map<String, Object>> r = c.recommendations(null, null, null, "Sangrur");
        Map<String, Object> fp = (Map<String, Object>) r.getBody().get("fingerprint");

        // No elevation client is wired in this test, and the bundled soil means
        // carry no CEC: both must be declared unavailable rather than invented.
        assertNull(fp.get("elevation_m"));
        assertNull(fp.get("cec_cmol_kg"));
        assertEquals("unavailable", fp.get("elevation_provenance"));
        assertEquals("unavailable", fp.get("cec_provenance"));

        List<String> missing = (List<String>) fp.get("missing");
        assertTrue(missing.contains("elevation_unavailable"));
        assertTrue(missing.contains("cec_unavailable"),
                "bundled soil is available but carries no CEC: " + missing);
        assertFalse(missing.contains("elevation_unavailable_no_source"),
                "the stale no-source code must not be emitted");
        assertFalse(missing.contains("cec_unavailable_no_source"),
                "soil itself is available, so only the CEC gap applies: " + missing);

        // Every dimension carries a provenance class so the UI can distinguish
        // live / cached / historical / reference / unavailable.
        assertEquals("live", ((Map<String, Object>) fp.get("climate")).get("provenance"));
        assertNotNull(((Map<String, Object>) fp.get("soil")).get("provenance"));
        assertNotNull(((Map<String, Object>) fp.get("climatology")).get("provenance"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aServedElevationIsReportedWithProvenance() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            byte[] body = "{\"elevation\": [300.5]}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            CropAtlasController c = controllerWithElevation(base, false,
                    4, 2, 0, 6, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0);
            Map<String, Object> fp = (Map<String, Object>) c
                    .recommendations(null, null, null, "Sangrur").getBody().get("fingerprint");
            assertEquals(300.5, (Double) fp.get("elevation_m"), 1e-9,
                    "a served elevation must be reported, never rounded away");
            assertEquals("reference", fp.get("elevation_provenance"),
                    "a fresh external query is reference data, not live");
            List<String> missing = (List<String>) fp.get("missing");
            assertFalse(missing.contains("elevation_unavailable"));

            // A repeat request for the same block must be served from the cache.
            Map<String, Object> fp2 = (Map<String, Object>) c
                    .recommendations(null, null, null, "Sangrur").getBody().get("fingerprint");
            assertEquals("cached", fp2.get("elevation_provenance"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnreachableElevationSourceDegradesToUnavailable() {
        // Closed port: connection refused, fast. The fingerprint must still
        // serve every other dimension.
        CropAtlasController c = controllerWithElevation("http://127.0.0.1:9", false,
                4, 2, 0, 6, 1, 0, 3, 0, 0, 0, 0, 0, 0, 0);
        ResponseEntity<Map<String, Object>> r = c.recommendations(null, null, null, "Sangrur");
        assertEquals(200, r.getStatusCode().value());
        Map<String, Object> fp = (Map<String, Object>) r.getBody().get("fingerprint");
        assertNull(fp.get("elevation_m"));
        assertEquals("unavailable", fp.get("elevation_provenance"));
        assertTrue(((List<String>) fp.get("missing")).contains("elevation_unavailable"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void climateAndSoilAreNeverSubstitutedWithZeroLikeValues() {
        // A dry, zero-rain forecast: the fingerprint must carry real zeros (which
        // are genuinely observed) but must NOT invent a value where the source
        // returned nothing. ET0 is present here, water balance is computable.
        CropAtlasController c = controller(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        ResponseEntity<Map<String, Object>> r = c.recommendations(null, null, null, "Sangrur");
        Map<String, Object> climate =
                (Map<String, Object>) ((Map<String, Object>) r.getBody().get("fingerprint"))
                        .get("climate");
        assertEquals(0.0, (Double) climate.get("rain_7d_mm"), 1e-9, "observed zero rain is a real zero");
        assertNotNull(climate.get("water_balance_7d_mm"));
        assertEquals(16, climate.get("horizon_days"));
    }

    /**
     * Regression: a daily value the provider left null must make the aggregate
     * {@code null}, never throw.
     *
     * <p>Found by live verification on 2026-09-25 against a real 16-day Open-Meteo
     * forecast for block Andana (3/43/340), which returned HTTP 500. The aggregate
     * took a {@code ToDoubleFunction}, so applying it unboxed the null daily value
     * and threw before the existing null guard could run, turning an honest
     * "unavailable" into a 500.
     */
    @Test
    void aggregateOverANullDailyValueIsUnavailableRatherThanThrowing() {
        assertNull(CropAtlasService.sumRain(withNullRainAt(9), 0, 16),
                "a null daily rainfall must yield an unavailable sum, not a NullPointerException");
        assertNull(CropAtlasService.sumEt0(withNullEt0At(3), 0, 7),
                "a null daily ET0 must yield an unavailable sum, not a NullPointerException");

        // The same null, before the window opens, must not poison a shorter window.
        assertEquals(70.0, CropAtlasService.sumRain(withNullRainAt(9), 0, 7), 1e-9,
                "a null outside the requested window must not affect the sum");
        assertEquals(35.0, CropAtlasService.sumEt0(withNullEt0At(9), 0, 7), 1e-9,
                "a null ET0 outside the requested window must not affect the sum");

        // A fully present series still aggregates normally.
        assertEquals(160.0, CropAtlasService.sumRain(completeDays(), 0, 16), 1e-9,
                "a complete series must still sum normally");
    }

    /** 16 days of 10 mm rain / 5 mm ET0. */
    private static List<LiveWeatherService.BlockDaily> completeDays() {
        List<LiveWeatherService.BlockDaily> out = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            out.add(new LiveWeatherService.BlockDaily(
                    LocalDate.of(2026, 1, 1).plusDays(i), i + 1,
                    10.0, 40.0, 26.0, 12.0, 5.0, 0.2, 10.0, 0.0, 1));
        }
        return out;
    }

    private static List<LiveWeatherService.BlockDaily> withNullRainAt(int index) {
        List<LiveWeatherService.BlockDaily> out = completeDays();
        LiveWeatherService.BlockDaily d = out.get(index);
        out.set(index, new LiveWeatherService.BlockDaily(d.date(), d.horizonDay(),
                null, d.rainProbabilityPct(), d.temperatureMaxC(), d.temperatureMinC(),
                d.et0Mm(), d.soilMoisture0To7CmVwc(), d.rainMm(), d.showersMm(), d.weatherCode()));
        return out;
    }

    private static List<LiveWeatherService.BlockDaily> withNullEt0At(int index) {
        List<LiveWeatherService.BlockDaily> out = completeDays();
        LiveWeatherService.BlockDaily d = out.get(index);
        out.set(index, new LiveWeatherService.BlockDaily(d.date(), d.horizonDay(),
                d.rainfallMm(), d.rainProbabilityPct(), d.temperatureMaxC(), d.temperatureMinC(),
                null, d.soilMoisture0To7CmVwc(), d.rainMm(), d.showersMm(), d.weatherCode()));
        return out;
    }
}
