package com.saarthi.cropatlas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Elevation lookup: parses the Open-Meteo shape, caches per coordinate, and
 * stays fail-soft (unavailable marker, never a fabricated height).
 */
class ElevationClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aNormalResponseParsesToMetres() throws Exception {
        ElevationClient.Elevation e = ElevationClient.parse(
                MAPPER.readTree("{\"elevation\": [257.0]}"));
        assertTrue(e.available());
        assertEquals(257.0, e.metres(), 1e-9);
        assertFalse(e.cached());
        assertTrue(e.source().contains("Open-Meteo"));
    }

    @Test
    void malformedOrOutOfRangeIsUnavailableNeverZero() throws Exception {
        assertFalse(ElevationClient.parse(MAPPER.readTree("{}")).available());
        assertFalse(ElevationClient.parse(
                MAPPER.readTree("{\"elevation\": []}")).available());
        assertFalse(ElevationClient.parse(
                MAPPER.readTree("{\"elevation\": [\"high\"]}")).available());
        assertFalse(ElevationClient.parse(
                MAPPER.readTree("{\"elevation\": [99999.0]}")).available(),
                "above Everest means the encoding changed: unavailable, not wild");
        ElevationClient.Elevation e = ElevationClient.parse(
                MAPPER.readTree("{\"elevation\": [null]}"));
        assertFalse(e.available());
        assertNull(e.metres(), "unavailable means null, never zero-filled");
    }

    @Test
    void outOfRangeCoordinatesAreRejected() {
        ElevationClient client = new ElevationClient(
                "http://127.0.0.1:9", 5, 168, HttpClient.newHttpClient());
        assertThrows(IllegalArgumentException.class, () -> client.lookup(95.0, 76.0));
        assertThrows(IllegalArgumentException.class, () -> client.lookup(30.0, 190.0));
    }

    @Test
    void transportFailureIsFailSoft() {
        // Closed port: connection refused, fast.
        ElevationClient client = new ElevationClient(
                "http://127.0.0.1:9", 5, 168, HttpClient.newHttpClient());
        ElevationClient.Elevation e = client.lookup(30.47, 76.36);
        assertFalse(e.available());
        assertNull(e.metres());
    }

    @Test
    void secondLookupHitsCache() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            byte[] body = "{\"elevation\": [257.0]}"
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
            ElevationClient client = new ElevationClient(
                    base, 10, 168, HttpClient.newHttpClient());
            ElevationClient.Elevation first = client.lookup(30.47, 76.36);
            ElevationClient.Elevation second = client.lookup(30.47, 76.36);
            assertTrue(first.available());
            assertFalse(first.cached(), "first hit is a fresh query");
            assertTrue(second.cached(), "second hit must be labelled cached");
            assertEquals(first.metres(), second.metres());
            assertEquals(1, hits.get(), "cached: one HTTP hit for two lookups");
        } finally {
            server.stop(0);
        }
    }
}
