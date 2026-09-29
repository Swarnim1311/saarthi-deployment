package com.saarthi.cropatlas;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dynamic discovery: ECOCROP decides the candidate set; SAARTHI only explains
 * the match dimensions. No static crop list exists anywhere on this path.
 *
 * <p>Stub ECOCROP serves recorded responses; fingerprints are built directly.
 */
class EcocropDiscoveryServiceTest {

    HttpServer server;
    String base;
    volatile byte[] searchBody;

    static byte[] fixture(String name) throws IOException {
        try (var in = new ClassPathResource("ecocrop/" + name).getInputStream()) {
            return in.readAllBytes();
        }
    }

    @BeforeEach
    void startStub() throws IOException {
        searchBody = fixture("search-3hits.html");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/cropSearchForm", ex -> {
            byte[] body = "<html><body></body></html>".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Set-Cookie", "JSESSIONID=STUB; Path=/; HttpOnly");
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/cropSearch", ex -> {
            ex.getResponseHeaders().add("Content-Type", "text/html;charset=UTF-8");
            ex.sendResponseHeaders(200, searchBody.length);
            try (var os = ex.getResponseBody()) {
                os.write(searchBody);
            }
        });
        server.createContext("/cropView", ex -> {
            // Only the recorded coconut page is served; every other id 404s
            // so enrichment stays fail-soft instead of inventing names.
            String q = ex.getRequestURI().getQuery();
            byte[] body;
            int status;
            if (q != null && q.contains("id=744")) {
                body = fixture("cropview-744.html");
                status = 200;
            } else {
                body = new byte[0];
                status = 404;
            }
            ex.sendResponseHeaders(status, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    static class StubAtlas extends CropAtlasService {
        StubAtlas() { super(null, null, null, null); }

        @Override
        public boolean hasClimatologyBlock(String blockName) { return false; }

        @Override
        public Double climatologySumMm(String blockName, List<LocalDate> dates) { return null; }

        @Override
        public String climatologyVintage() { return "test normals"; }
    }

    static EnvironmentalFingerprint fp(String block, Double lat) {
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("block_name", block);
        loc.put("latitude", lat);
        EnvironmentalFingerprint.Climate climate = new EnvironmentalFingerprint.Climate(
                "Open-Meteo", "ECMWF IFS (ecmwf_ifs)", "2026-09-26", "single_point_centroid",
                false, 16, 20.0, 30.0, 14.0, 39.0, 5.0, 9.0, 40.0,
                CropAtlasService.round1(5.0 - 40.0), 2, 5, 0.2);
        EnvironmentalFingerprint.Soil soil = new EnvironmentalFingerprint.Soil(true,
                270.0, 320.0, 375.0, 12.3, 7.7, null, false, null, "SoilGrids test", "0–5 cm");
        return new EnvironmentalFingerprint(loc, climate, soil,
                new EnvironmentalFingerprint.Climatology(false, block, null, null, null, "test"),
                600.0, EnvironmentalFingerprint.REFERENCE, null, null,
                List.of(), List.of());
    }

    EcocropDiscoveryService service() {
        EcocropSearchClient client =
                new EcocropSearchClient(base, 10, 120, HttpClient::newHttpClient);
        return new EcocropDiscoveryService(client, new EcocropQueryMapper(), new StubAtlas());
    }

    @Test
    void candidatesComeFromEcocropWithHonestDimensions() {
        EcocropDiscoveryService.Discovery d = service().discover(fp("Khatav", 17.6));
        assertTrue(d.available());
        assertEquals(3, d.candidates().size());
        assertEquals(3, d.totalFound());
        assertTrue(d.levelsTried() >= 1);

        EcocropDiscoveryService.EcocropCandidate first = d.candidates().get(0);
        assertEquals("Tamarindus indica", first.scientificName());
        assertEquals("2047", first.ecoportId());
        assertTrue(first.cropViewUrl().endsWith("/cropView?id=2047"));
        assertTrue(first.matchedDimensions().contains("temperature"));
        assertFalse(first.matchedDimensions().contains("soil_texture"),
                "categorical soil must never be claimed as matched");

        Map<String, Object> m = first.toMap();
        assertEquals("FAO ECOCROP", m.get("source"));
        assertEquals("ecocrop_match", m.get("suitability"));
        assertEquals(false, m.get("score_available"));
        assertNull(m.get("compatibility_score"),
                "no SAARTHI score may be computed without per-crop requirements");
        assertNull(first.commonName(),
                "unserved common names stay null, never borrowed from another crop");
    }

    @Test
    void altNamesStayEmptyWhenUnserved() {
        EcocropDiscoveryService.Discovery d = service().discover(fp("Khatav", 17.6));
        EcocropDiscoveryService.EcocropCandidate first = d.candidates().get(0);
        assertTrue(first.altNames().isEmpty(),
                "unserved alternates stay empty, never borrowed");
        assertEquals(List.of(), first.toMap().get("alt_names"));
    }

    @Test
    void noStaticCropListNoSixCropIds() {
        EcocropDiscoveryService.Discovery d = service().discover(fp("Khatav", 17.6));
        for (EcocropDiscoveryService.EcocropCandidate c : d.candidates()) {
            String id = c.ecoportId();
            assertFalse(List.of("maize", "paddy", "cotton", "wheat", "basmati", "sugarcane")
                    .contains(id),
                    "candidates are ECOCROP answers, never the old six-crop ids");
        }
    }

    @Test
    void displayCapKeepsEcocropOrderAndReportsTotal() throws IOException {
        StringBuilder rows = new StringBuilder("<html><body>20 Plants were found<table>");
        for (int i = 0; i < 20; i++) {
            rows.append("<tr><td>Species alpha ").append(i).append("</td><td>")
                    .append(9000 + i)
                    .append("</td><td><a href=\"javascript:load(%22/cropView?id=")
                    .append(9000 + i).append("%22)\">View</a></td></tr>");
        }
        rows.append("</table></body></html>");
        searchBody = rows.toString().getBytes(StandardCharsets.UTF_8);
        EcocropDiscoveryService.Discovery d = service().discover(fp("Khatav", 17.6));
        assertEquals(20, d.totalFound());
        assertEquals(EcocropDiscoveryService.MAX_DISPLAY, d.candidates().size());
        assertEquals("Species alpha 0", d.candidates().get(0).scientificName(),
                "cap applies in ECOCROP result order");
    }

    @Test
    void emptyAnswerStaysEmptyAndAvailable() throws IOException {
        searchBody = fixture("search-0hits.html");
        EcocropDiscoveryService.Discovery d = service().discover(fp("Khatav", 17.6));
        assertTrue(d.available(), "an answered empty search is valid, not a failure");
        assertTrue(d.candidates().isEmpty());
        assertEquals(0, d.totalFound());
    }

    @Test
    void upstreamFailureBecomesUnavailableWithZeroCandidates() throws IOException {
        searchBody = fixture("search-error.html");
        EcocropDiscoveryService.Discovery d = service().discover(fp("Khatav", 17.6));
        assertFalse(d.available());
        assertTrue(d.candidates().isEmpty());
        assertTrue(d.reason().contains("could not be reached"),
                "reason must admit the outage: " + d.reason());
    }

    @Test
    void undiscoverableFingerprintReportsNoCandidatesHonestly() {
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("block_name", "Nowhere");
        EnvironmentalFingerprint empty = new EnvironmentalFingerprint(loc, null,
                new EnvironmentalFingerprint.Soil(false, null, null, null, null, null, null,
                        false, null, "unavailable", "0–5 cm"),
                new EnvironmentalFingerprint.Climatology(false, "Nowhere", null, null, null,
                        "test"),
                null, null, null, null, List.of(), List.of());
        EcocropDiscoveryService.Discovery d = service().discover(empty);
        assertTrue(d.available());
        assertTrue(d.candidates().isEmpty());
    }
}
