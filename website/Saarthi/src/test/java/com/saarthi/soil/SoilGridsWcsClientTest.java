package com.saarthi.soil;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SoilGrids WCS subset retrieval: the centre pixel of a small coverage subset
 * is read with the JDK only, scaled by ISRIC-declared factors, and served
 * per-property fail-soft with coordinate caching.
 *
 * <p>No network: the TIFF fixtures are byte-identical recordings of real
 * ISRIC WCS responses (Sunam, Sangrur — {@code *_0-5cm_Q0.5}), and the client
 * is exercised through a local stub server.
 */
class SoilGridsWcsClientTest {

    private static byte[] fixture(String name) throws Exception {
        try (InputStream in = new ClassPathResource("soil/wcs/" + name).getInputStream()) {
            return in.readAllBytes();
        }
    }

    // ---- centre-pixel parsing of recorded responses ----

    @Test
    void recordedSunamResponsesDecodeToTheirCentrePixels() throws Exception {
        assertEquals(80.0, SoilGridsWcsClient.centerRaw(fixture("sunam-phh2o.tif")));
        assertEquals(405.0, SoilGridsWcsClient.centerRaw(fixture("sunam-sand.tif")));
        assertEquals(333.0, SoilGridsWcsClient.centerRaw(fixture("sunam-silt.tif")));
        assertEquals(154.0, SoilGridsWcsClient.centerRaw(fixture("sunam-clay.tif")));
        assertEquals(80.0, SoilGridsWcsClient.centerRaw(fixture("sunam-soc.tif")));
        assertEquals(114.0, SoilGridsWcsClient.centerRaw(fixture("sunam-cec.tif")));
    }

    @Test
    void errorDocumentsAndGarbageAreNeverNumeric() {
        assertNull(SoilGridsWcsClient.centerRaw(
                "<ows:ExceptionReport/>".getBytes(StandardCharsets.US_ASCII)));
        assertNull(SoilGridsWcsClient.centerRaw(new byte[0]));
        assertNull(SoilGridsWcsClient.centerRaw(new byte[]{1, 2, 3, 4}));
        assertNull(SoilGridsWcsClient.centerRaw("not a tiff at all".getBytes()));
    }

    @Test
    void officialConversionFactorsApply() {
        assertEquals(8.0, SoilGridsWcsClient.Property.PH.convert(80));
        assertEquals(405.0, SoilGridsWcsClient.Property.SAND.convert(405));
        assertEquals(8.0, SoilGridsWcsClient.Property.SOC.convert(80));
        assertEquals(11.4, SoilGridsWcsClient.Property.CEC.convert(114));
    }

    @Test
    void implausiblePixelsStayUnavailable() {
        assertNull(SoilGridsWcsClient.Property.PH.convert(0), "pH 0 is nodata, not soil");
        assertNull(SoilGridsWcsClient.Property.PH.convert(200), "pH 20 is impossible");
        assertNull(SoilGridsWcsClient.Property.SAND.convert(65535), "sentinel, not sand");
        assertNull(SoilGridsWcsClient.Property.CEC.convert(70000), "out of uint16 range");
    }

    // ---- stub-server client behaviour ----

    /** Serves fixture bytes per requested WCS property; records queries. */
    static final class StubWcs implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final Map<String, byte[]> byProp = new ConcurrentHashMap<>();
        final List<String> queries = new ArrayList<>();
        final AtomicInteger hits = new AtomicInteger();
        final int failStatusForCec;

        StubWcs(int failStatusForCec) throws Exception {
            this.failStatusForCec = failStatusForCec;
            for (String p : List.of("phh2o", "sand", "silt", "clay", "soc", "cec")) {
                byProp.put(p, fixture("sunam-" + p + ".tif"));
            }
            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(0), 0);
            server.createContext("/", ex -> {
                hits.incrementAndGet();
                String q = String.valueOf(ex.getRequestURI().getRawQuery());
                synchronized (queries) {
                    queries.add(q);
                }
                String prop = null;
                for (String p : byProp.keySet()) {
                    if (q.contains("/map/" + p + ".map")) prop = p;
                }
                byte[] body;
                int status = 200;
                if (prop == null) {
                    status = 400;
                    body = "<ows:ExceptionReport/>".getBytes(StandardCharsets.UTF_8);
                } else if ("cec".equals(prop) && failStatusForCec != 200) {
                    status = failStatusForCec;
                    body = "<ows:ExceptionReport/>".getBytes(StandardCharsets.UTF_8);
                } else {
                    body = byProp.get(prop);
                }
                ex.getResponseHeaders().add("Content-Type",
                        status == 200 ? "image/tiff" : "text/xml");
                ex.sendResponseHeaders(status, body.length);
                try (var os = ex.getResponseBody()) {
                    os.write(body);
                }
            });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        SoilGridsWcsClient client() {
            return new SoilGridsWcsClient(base(), 10, HttpClient.newHttpClient());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void aFullLookupServesAllSixConvertedProperties() throws Exception {
        try (StubWcs stub = new StubWcs(200)) {
            SoilGridsWcsClient.WcsSoil s = stub.client().lookup(30.0699, 75.8647);
            assertTrue(s.available());
            assertEquals(8.0, s.ph(), 1e-9);
            assertEquals(405.0, s.sandGkg(), 1e-9, "texture stays at bundle scale (g/kg)");
            assertEquals(333.0, s.siltGkg(), 1e-9);
            assertEquals(154.0, s.clayGkg(), 1e-9);
            assertEquals(8.0, s.socGkg(), 1e-9);
            assertEquals(11.4, s.cecCmolKg(), 1e-9);
            assertTrue(s.source().contains("WCS"), "provenance must name the WCS service");
            assertFalse(s.allCached(), "first lookup is fresh, never labelled cached");
        }
    }

    @Test
    void oneFailingPropertyLeavesTheOthersServed() throws Exception {
        try (StubWcs stub = new StubWcs(503)) {
            SoilGridsWcsClient.WcsSoil s = stub.client().lookup(30.0699, 75.8647);
            assertTrue(s.available(), "five served properties still count");
            assertNull(s.cecCmolKg(), "the failed property alone is unavailable");
            assertEquals(8.0, s.ph(), 1e-9);
            assertEquals(405.0, s.sandGkg(), 1e-9);
        }
    }

    @Test
    void totalOutageIsUnavailableWithNullsNeverZeros() throws Exception {
        SoilGridsWcsClient client = new SoilGridsWcsClient(
                "http://127.0.0.1:9", 5, HttpClient.newHttpClient());
        SoilGridsWcsClient.WcsSoil s = client.lookup(30.0699, 75.8647);
        assertFalse(s.available());
        assertNull(s.ph());
        assertNull(s.cecCmolKg(), "unavailable means nulls, never zero-filled");
    }

    @Test
    void repeatLookupsAreServedFromTheCoordinateCache() throws Exception {
        try (StubWcs stub = new StubWcs(200)) {
            SoilGridsWcsClient client = stub.client();
            SoilGridsWcsClient.WcsSoil first = client.lookup(30.0699, 75.8647);
            SoilGridsWcsClient.WcsSoil second = client.lookup(30.0699, 75.8647);
            assertEquals(6, stub.hits.get(), "one HTTP hit per property, then cache");
            assertTrue(second.allCached(), "repeat lookup must read CACHED");
            assertEquals(first.ph(), second.ph());
            assertEquals(first.cecCmolKg(), second.cecCmolKg());
        }
    }

    @Test
    void differentCoordinatesHitDifferentSubsets() throws Exception {
        try (StubWcs stub = new StubWcs(200)) {
            SoilGridsWcsClient client = stub.client();
            client.lookup(30.0699, 75.8647);
            client.lookup(17.63721, 74.44907);
            synchronized (stub.queries) {
                // The URL carries the subset bounds (centre ± half-box), so the
                // Sunam and Khatav latitudes appear as their lower bounds.
                assertTrue(stub.queries.stream().anyMatch(q -> q.contains("30.0599")),
                        "Sunam request must carry Sunam's subset bounds");
                assertTrue(stub.queries.stream().anyMatch(q -> q.contains("17.6272")),
                        "Khatav request must carry Khatav's subset bounds, never Sunam's");
                assertTrue(stub.queries.stream().allMatch(
                        q -> q.contains("0-5cm_Q0.5") && q.contains("GetCoverage")),
                        "every request is a median-subset GetCoverage, never a full map");
            }
        }
    }

    @Test
    void outOfRangeCoordinatesAreRejected() {
        SoilGridsWcsClient client = new SoilGridsWcsClient(
                "http://127.0.0.1:9", 5, HttpClient.newHttpClient());
        assertThrows(IllegalArgumentException.class, () -> client.lookup(95.0, 76.0));
        assertThrows(IllegalArgumentException.class, () -> client.lookup(30.0, 190.0));
    }
}
