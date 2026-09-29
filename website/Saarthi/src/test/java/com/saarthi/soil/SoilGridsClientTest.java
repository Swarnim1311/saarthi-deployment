package com.saarthi.soil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saarthi.risks.SoilContext;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SoilGrids point lookup: parses the documented shape, caches, and stays
 * fail-soft (unavailable profile, never fabricated values, never throws).
 */
class SoilGridsClientTest {

    /** Canned response in the documented properties/query shape. */
    static final String CANNED = """
            {
              "properties": {
                "clay": {"depths": {"0-5cm": {
                  "values": {"mean": 271.0, "Q0.5": 270.0},
                  "unit_measure": {"mapped_units": "g/kg", "conversion_factor": 10}}}},
                "sand": {"depths": {"0-5cm": {"values": {"mean": 368.0}}}},
                "silt": {"depths": {"0-5cm": {"values": {"mean": 339.0}}}},
                "soc": {"depths": {"0-5cm": {"values": {"mean": 121.0}}}},
                "phh2o": {"depths": {"0-5cm": {"values": {"mean": 77.0}}}}
              }
            }
            """;

    @Test
    void cannedResponseParsesToBundleScale() throws Exception {
        SoilGridsClient.SoilProfile p = SoilGridsClient.parse(
                new ObjectMapper().readTree(CANNED));
        assertTrue(p.available());
        assertEquals(271.0, p.clayGkg(), 1e-9, "texture kept at API scale like the bundle");
        assertEquals(368.0, p.sandGkg(), 1e-9);
        assertEquals(12.1, p.socGkg(), 1e-9, "SOC dg/kg -> g/kg");
        assertEquals(7.7, p.ph(), 1e-9, "pH*10 -> pH");
        assertTrue(p.source().contains("SoilGrids"));
    }

    @Test
    void emptyOrMalformedIsUnavailableNeverZero() throws Exception {
        assertFalse(SoilGridsClient.parse(
                new ObjectMapper().readTree("{}")).available());
        assertFalse(SoilGridsClient.parse(
                new ObjectMapper().readTree("{\"properties\": {}}")).available());
        SoilGridsClient.SoilProfile p = SoilGridsClient.parse(
                new ObjectMapper().readTree("{\"properties\": {}}"));
        assertNull(p.clayGkg(), "unavailable means nulls, never zero-filled");
    }

    @Test
    void transportFailureIsFailSoft() {
        // Closed port: connection refused, fast.
        SoilGridsClient client = new SoilGridsClient(
                "http://127.0.0.1:9", 5, 120, HttpClient.newHttpClient());
        SoilGridsClient.SoilProfile p = client.lookup(30.06, 75.86);
        assertFalse(p.available());
        assertNull(p.clayGkg());
    }

    @Test
    void secondLookupHitsCache() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            byte[] body = CANNED.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            SoilGridsClient client = new SoilGridsClient(
                    base, 10, 120, HttpClient.newHttpClient());
            SoilGridsClient.SoilProfile first = client.lookup(30.06, 75.86);
            SoilGridsClient.SoilProfile second = client.lookup(30.06, 75.86);
            assertTrue(first.available());
            assertEquals(first.clayGkg(), second.clayGkg());
            assertEquals(1, hits.get(), "cached: one HTTP hit for two lookups");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cecParsesFromTheDeclaredConversionFactor() throws Exception {
        String withCec = """
                {
                  "properties": {
                    "clay": {"depths": {"0-5cm": {
                      "values": {"mean": 271.0},
                      "unit_measure": {"mapped_units": "g/kg", "conversion_factor": 10}}}},
                    "cec": {"depths": {"0-5cm": {
                      "values": {"mean": 152.0},
                      "unit_measure": {"mapped_units": "cmol(c)/kg", "conversion_factor": 10}}}}
                  }
                }
                """;
        SoilGridsClient.SoilProfile p = SoilGridsClient.parse(
                new ObjectMapper().readTree(withCec));
        assertTrue(p.available());
        assertEquals(15.2, p.cecCmolKg(), 1e-9,
                "CEC uses the response's own conversion factor, never a guess");
    }

    @Test
    void cecWithoutADeclaredFactorStaysNullRatherThanGuessed() throws Exception {
        String noFactor = """
                {"properties": {
                  "clay": {"depths": {"0-5cm": {"values": {"mean": 271.0}}}},
                  "cec": {"depths": {"0-5cm": {"values": {"mean": 152.0}}}}
                }}""";
        SoilGridsClient.SoilProfile p = SoilGridsClient.parse(
                new ObjectMapper().readTree(noFactor));
        assertTrue(p.available(), "other properties still serve");
        assertNull(p.cecCmolKg(), "no declared scale means no CEC value, not a guess");
    }

    @Test
    void anImplausibleCecIsUnavailableRatherThanWild() throws Exception {
        String wild = """
                {"properties": {
                  "cec": {"depths": {"0-5cm": {
                    "values": {"mean": 99999.0},
                    "unit_measure": {"mapped_units": "cmol(c)/kg", "conversion_factor": 1}}}}
                }}""";
        SoilGridsClient.SoilProfile p = SoilGridsClient.parse(
                new ObjectMapper().readTree(wild));
        assertFalse(p.available());
    }

    @Test
    void aCacheHitIsLabelledCached() throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            byte[] body = CANNED.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            SoilGridsClient client = new SoilGridsClient(
                    base, 10, 120, HttpClient.newHttpClient());
            SoilGridsClient.SoilProfile first = client.lookup(30.06, 75.86);
            SoilGridsClient.SoilProfile second = client.lookup(30.06, 75.86);
            assertFalse(first.cached(), "first hit is a fresh query");
            assertTrue(second.cached(), "repeat hit must be labelled cached");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void soilContextLineUsesClientOrNull() {
        SoilGridsClient failing = new SoilGridsClient(
                "http://127.0.0.1:9", 5, 120, HttpClient.newHttpClient());
        SoilContext ctx = new SoilContext();
        ctx.setSoilGrids(failing);
        assertNull(ctx.lineForCoords(30.06, 75.86),
                "failed lookup -> null line, never fabricated");
        assertNotNull(ctx.describe("Sunam"), "bundled Sangrur path untouched");
    }
}
