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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Official FAO ECOCROP search client: session handling, result parsing, error
 * detection, input validation and cache behaviour.
 *
 * <p>No live FAO dependency: a local stub plays the verified two-step flow
 * (form GET sets {@code JSESSIONID}; search POST requires the cookie) and
 * serves byte-recorded real ECOCROP responses from
 * {@code src/test/resources/ecocrop/}.
 */
class EcocropSearchClientTest {

    HttpServer server;
    String base;
    final AtomicInteger formGets = new AtomicInteger();
    final AtomicInteger searchPosts = new AtomicInteger();
    volatile byte[] searchBody;
    volatile boolean requireCookie = true;
    volatile boolean setSessionCookie = true;

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
            formGets.incrementAndGet();
            byte[] body = "<html><body><form action=\"cropSearch\"></form></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html;charset=UTF-8");
            if (setSessionCookie) {
                ex.getResponseHeaders().add("Set-Cookie",
                        "JSESSIONID=STUBSESSION; Path=/; HttpOnly");
            }
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/cropSearch", ex -> {
            searchPosts.incrementAndGet();
            String cookie = ex.getRequestHeaders().getFirst("Cookie");
            byte[] body = (requireCookie
                    && (cookie == null || !cookie.contains("JSESSIONID=STUBSESSION")))
                    ? fixture("search-error.html")
                    : searchBody;
            ex.getResponseHeaders().add("Content-Type", "text/html;charset=UTF-8");
            ex.sendResponseHeaders(200, body.length);
            try (var os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/cropView", ex -> {
            byte[] body = fixture("cropview-744.html");
            ex.getResponseHeaders().add("Content-Type", "text/html;charset=UTF-8");
            ex.sendResponseHeaders(200, body.length);
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

    EcocropSearchClient client() {
        return new EcocropSearchClient(base, 10, 120, HttpClient::newHttpClient);
    }

    static Map<String, String> env() {
        Map<String, String> m = new HashMap<>();
        m.put("minTemperature", "14");
        m.put("maxTemperature", "39");
        m.put("minRainfall", "400");
        m.put("maxRainfall", "700");
        m.put("minSoilPh", "6.5");
        m.put("maxSoilPh", "8.4");
        m.put("latitude", "17.6");
        m.put("altitude", "600");
        return m;
    }

    @Test
    void freshSessionIsEstablishedAndSearchParsesHits() {
        EcocropSearchClient.SearchResult r = client().search(env(), true, "25");
        assertEquals(1, formGets.get(), "exactly one form GET per fresh search");
        assertEquals(1, searchPosts.get());
        assertEquals(3, r.totalFound());
        assertEquals(3, r.hits().size());
        assertFalse(r.cached());
        assertNotNull(r.retrievedAt());

        EcocropSearchClient.EcocropHit first = r.hits().get(0);
        assertEquals("Tamarindus indica", first.scientificName());
        assertEquals("2047", first.ecoportId());
        assertEquals("/cropView?id=2047", first.cropViewPath());
        assertEquals("Centrosema pascuorum", r.hits().get(1).scientificName());
        assertEquals("4411", r.hits().get(1).ecoportId());
        assertEquals("Colophospermum mopane", r.hits().get(2).scientificName());
        assertEquals("4779", r.hits().get(2).ecoportId());
    }

    @Test
    void zeroHitPageParsesToEmptyList() throws IOException {
        searchBody = fixture("search-0hits.html");
        EcocropSearchClient.SearchResult r = client().search(env(), true, "25");
        assertEquals(0, r.totalFound());
        assertTrue(r.hits().isEmpty());
    }

    @Test
    void errorPageBecomesUnavailableNeverAGuess() throws IOException {
        searchBody = fixture("search-error.html");
        EcocropSearchClient.EcocropUnavailableException e = assertThrows(
                EcocropSearchClient.EcocropUnavailableException.class,
                () -> client().search(env(), true, "25"));
        assertEquals("error_page", e.reason());
    }

    @Test
    void missingSessionCookieFailsBeforeSearching() {
        // ECOCROP rejects stateless searches; if the form sets no session the
        // client must stop instead of POSTing blindly.
        setSessionCookie = false;
        EcocropSearchClient.EcocropUnavailableException e = assertThrows(
                EcocropSearchClient.EcocropUnavailableException.class,
                () -> client().search(env(), true, "25"));
        assertEquals("session_failed", e.reason());
        assertEquals(0, searchPosts.get(), "no search may run without a session");
    }

    @Test
    void malformedNumericsAreRejectedBeforeAnyHttpCall() {
        Map<String, String> bad = env();
        bad.put("minTemperature", "abc");
        assertThrows(IllegalArgumentException.class, () -> client().search(bad, true, "25"));
        assertEquals(0, formGets.get(), "no upstream call may be made with bad input");
        assertEquals(0, searchPosts.get());
    }

    @Test
    void outOfRangeNumericsAreRejected() {
        Map<String, String> bad = env();
        bad.put("minSoilPh", "99");
        assertThrows(IllegalArgumentException.class, () -> client().search(bad, true, "25"));
        assertEquals(0, formGets.get());
    }

    @Test
    void blankQueryIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> client().search(Map.of(), true, "25"),
                "a dimension-less query would enumerate the database");
        assertEquals(0, formGets.get());
    }

    @Test
    void successfulSearchIsCachedAndIsolatedByQuery() {
        EcocropSearchClient c = client();
        c.search(env(), true, "25");
        c.search(env(), true, "25");
        assertEquals(1, searchPosts.get(), "repeat query must be served from cache");
        assertEquals(1, c.cacheSize());

        Map<String, String> other = env();
        other.put("latitude", "30.1");
        EcocropSearchClient.SearchResult r2 = c.search(other, true, "25");
        assertEquals(2, searchPosts.get(), "a different environment must re-query");
        assertFalse(r2.cached());
        assertEquals(2, c.cacheSize(), "cache entries must be isolated per query");
    }

    @Test
    void commonNamesComeFromCropViewAndNeverGuessed() {
        String names = client().fetchCommonNames("744");
        assertNotNull(names, "recorded coconut page states common names");
        assertEquals("côco", names,
                "primary is ECOCROP's first listed name, not a wall: " + names);
        assertThrows(IllegalArgumentException.class, () -> client().fetchCommonNames("nope"));
    }

    @Test
    void commonNameListIsConciseOrderedAndHonest() throws IOException {
        String html = new String(fixture("cropview-744.html"), StandardCharsets.UTF_8);
        java.util.List<String> names = EcocropSearchClient.parseCommonNameList(html);
        assertFalse(names.isEmpty());
        assertEquals("côco", names.get(0), "first real name, parenthetical notes skipped");
        assertTrue(names.contains("coconut"), "alternates preserved in ECOCROP order");
        for (String n : names) {
            assertFalse(n.isBlank(), "no blanks");
            assertFalse(n.startsWith("("), "no notes: " + n);
        }
        assertEquals(names.size(), names.stream().map(String::toLowerCase).distinct().count(),
                "no duplicates");
        assertNull(EcocropSearchClient.parseCommonNames(null));
        assertTrue(EcocropSearchClient.parseCommonNameList("<html></html>").isEmpty());
    }

    @Test
    void commonNameListFetchIsFailSoft() {
        java.util.List<String> names = client().fetchCommonNameList("744");
        assertFalse(names.isEmpty());
        assertEquals("côco", names.get(0));
    }

    @Test
    void cropDetailNotesAreQuotedVerbatimPerSpecies() throws IOException {
        String html = new String(fixture("cropview-744.html"), StandardCharsets.UTF_8);
        EcocropSearchClient.CropDetail detail = EcocropSearchClient.parseCropDetail(html);
        assertFalse(detail.names().isEmpty(), "names still parsed from the same page");
        assertNotNull(detail.killingTemp(), "coconut page prints KILLING T.");
        assertTrue(detail.killingTemp().toLowerCase().contains("frost"),
                "cold tolerance is ECOCROP's own prose: " + detail.killingTemp());
        assertNotNull(detail.growingPeriod(), "coconut page prints GROWING PERIOD");
        assertTrue(detail.growingPeriod().contains("Perennial"),
                "growing period is ECOCROP's own prose: " + detail.growingPeriod());
        assertFalse(detail.killingTemp().contains("GROWING PERIOD"),
                "segment stops at the next section marker");
    }

    @Test
    void cropDetailToleratesLivePagesWithoutColons() {
        // Live cropView pages print "KILLING T  ..." (no period, no colon).
        String html = "<html><body><table><tr><td>DESCRIPTION: A hardy crop. "
                + "KILLING T  Damaged or killed at 5C for prolonged time. "
                + "GROWING PERIOD  Perennial, fruiting in 11-36 months. "
                + "COMMON NAMES  Foo, Bar.</td></tr></table></body></html>";
        assertTrue(EcocropSearchClient.parseNoteSegment(html, "KILLING T.")
                .startsWith("Damaged or killed"));
        assertTrue(EcocropSearchClient.parseNoteSegment(html, "GROWING PERIOD")
                .startsWith("Perennial"));
    }

    @Test
    void cropDetailMissingSegmentsStayNull() {        EcocropSearchClient.CropDetail detail =
                EcocropSearchClient.parseCropDetail("<html><body>no description</body></html>");
        assertTrue(detail.names().isEmpty());
        assertNull(detail.killingTemp(), "absent notes are null, never invented");
        assertNull(detail.growingPeriod());
        assertNull(EcocropSearchClient.parseNoteSegment(null, "KILLING T."));
    }

    @Test
    void unreachableUpstreamIsUnavailable() {
        EcocropSearchClient down =
                new EcocropSearchClient("http://127.0.0.1:9", 2, 120, HttpClient::newHttpClient);
        EcocropSearchClient.EcocropUnavailableException e = assertThrows(
                EcocropSearchClient.EcocropUnavailableException.class,
                () -> down.search(env(), true, "25"));
        assertNotNull(e.reason());
    }
}
