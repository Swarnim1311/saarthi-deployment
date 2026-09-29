package com.saarthi.cropatlas;

import com.saarthi.soil.SoilGridsWcsClient;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CropAtlas soil assembly: WCS values win where served, bundled block means
 * backfill only WCS gaps for legacy blocks, and anything else stays
 * unavailable. No network: a local stub serves recorded ISRIC responses.
 */
class CropAtlasSoilSourceTest {

    /** Local WCS stub serving recorded responses per property. */
    static final class StubWcs implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final Map<String, byte[]> byProp = new ConcurrentHashMap<>();
        String failProp = null;

        StubWcs() throws Exception {
            for (String p : List.of("phh2o", "sand", "silt", "clay", "soc", "cec")) {
                try (InputStream in = new ClassPathResource("soil/wcs/sunam-" + p + ".tif")
                        .getInputStream()) {
                    byProp.put(p, in.readAllBytes());
                }
            }
            server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(0), 0);
            server.createContext("/", ex -> {
                String q = String.valueOf(ex.getRequestURI().getRawQuery());
                String prop = null;
                for (String p : byProp.keySet()) {
                    if (q.contains("/map/" + p + ".map")) prop = p;
                }
                byte[] body;
                int status = 200;
                if (prop == null || (failProp != null && failProp.equals(prop))) {
                    status = 503;
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

        CropAtlasSoilSource source() {
            CropAtlasSoilSource s = new CropAtlasSoilSource();
            s.setSoilWcs(new SoilGridsWcsClient(
                    "http://127.0.0.1:" + server.getAddress().getPort(), 10,
                    HttpClient.newHttpClient()));
            return s;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    @Test
    void wcsValuesWinWhereServed() throws Exception {
        try (StubWcs stub = new StubWcs()) {
            EnvironmentalFingerprint.Soil s =
                    stub.source().soilFor("Sunam", 30.0699, 75.8647);
            assertTrue(s.available());
            assertEquals(8.0, s.ph(), 1e-9, "WCS median, not the bundled mean 7.77");
            assertEquals(405.0, s.sandGkg(), 1e-9);
            assertEquals(11.4, s.cecCmolKg(), 1e-9, "CEC only WCS can serve");
            assertFalse(s.cached(), "first lookup is fresh");
            assertTrue(s.source().contains("WCS"));
        }
    }

    @Test
    void bundledMeansBackfillOnlyWcsGaps() throws Exception {
        // CEC has no bundled fallback: a failed CEC stays null with a pure
        // WCS source label.
        try (StubWcs stub = new StubWcs()) {
            stub.failProp = "cec";
            EnvironmentalFingerprint.Soil s =
                    stub.source().soilFor("Sunam", 30.0699, 75.8647);
            assertTrue(s.available());
            assertEquals(8.0, s.ph(), 1e-9, "WCS value kept where served");
            assertNull(s.cecCmolKg(), "the bundle has no CEC: stays null, never borrowed");
            assertTrue(s.source().contains("WCS"));
            assertFalse(s.source().contains("bundled"),
                    "nothing was backfilled, so no bundled claim: " + s.source());
        }
        // A failed pH IS backfilled from the bundled means, and the mixed
        // provenance is disclosed.
        try (StubWcs stub = new StubWcs()) {
            stub.failProp = "phh2o";
            EnvironmentalFingerprint.Soil s =
                    stub.source().soilFor("Sunam", 30.0699, 75.8647);
            assertTrue(s.available());
            assertEquals(7.77, s.ph(), 1e-6, "bundled mean fills the WCS gap");
            assertEquals(405.0, s.sandGkg(), 1e-9, "WCS value kept where served");
            assertTrue(s.source().contains("bundled"),
                    "mixed provenance must be disclosed: " + s.source());
        }
    }

    @Test
    void bundledFallbackServesLegacyBlocksWhenWcsIsDown() {
        CropAtlasSoilSource s = new CropAtlasSoilSource();
        s.setSoilWcs(new SoilGridsWcsClient(
                "http://127.0.0.1:9", 5, HttpClient.newHttpClient()));
        EnvironmentalFingerprint.Soil soil = s.soilFor("Sunam", 30.0699, 75.8647);
        assertTrue(soil.available());
        assertEquals(7.77, soil.ph(), 1e-6, "bundled Phase 4.0 mean");
        assertNull(soil.cecCmolKg());
    }

    @Test
    void unknownBlocksWithNoSourceStayUnavailable() {
        CropAtlasSoilSource s = new CropAtlasSoilSource();
        s.setSoilWcs(new SoilGridsWcsClient(
                "http://127.0.0.1:9", 5, HttpClient.newHttpClient()));
        EnvironmentalFingerprint.Soil soil =
                s.soilFor("Nowhere-Village-9999", 11.1111, 22.2222);
        assertFalse(soil.available());
        assertNull(soil.ph(), "unavailable means nulls, never another block's values");
    }
}
