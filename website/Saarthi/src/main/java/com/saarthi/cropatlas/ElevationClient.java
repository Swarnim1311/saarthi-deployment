package com.saarthi.cropatlas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block elevation from the keyless Open-Meteo elevation API (Copernicus DEM,
 * same provider family as the live ECMWF IFS forecast SAARTHI already uses).
 *
 * <p>Contract: one small GET per uncached coordinate, cached with a long TTL
 * (elevation does not change between requests), and FAIL-SOFT — any
 * transport/parse/shape problem yields {@link Elevation#unavailable()}, never
 * a fabricated number and never an exception to callers. A missing result
 * stays {@code null} so the fingerprint reports UNAVAILABLE honestly.
 */
@Component
public class ElevationClient {

    private static final Logger log = LoggerFactory.getLogger(ElevationClient.class);

    static final String DEFAULT_BASE_URL = "https://api.open-meteo.com";

    /** Elevation in metres above sea level; {@code available=false} means unknown. */
    public record Elevation(Double metres, boolean available, boolean cached, String source) {
        static Elevation unavailable() {
            return new Elevation(null, false, false,
                    "Elevation unavailable for this block");
        }

        Elevation asCached() {
            return new Elevation(metres, available, true, source);
        }
    }

    @Value("${saarthi.elevation.base-url:https://api.open-meteo.com}")
    private String baseUrl = DEFAULT_BASE_URL;

    @Value("${saarthi.elevation.timeout-seconds:12}")
    private int timeoutSeconds = 12;

    @Value("${saarthi.elevation.cache-ttl-hours:168}")
    private long cacheTtlHours = 168;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final Map<String, CachedElevation> cache = new ConcurrentHashMap<>();

    public ElevationClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    /** Test seam: inject base URL / timeouts / client without Spring. */
    ElevationClient(String baseUrl, int timeoutSeconds, long cacheTtlHours,
            HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.cacheTtlHours = cacheTtlHours;
        this.httpClient = httpClient;
    }

    /** Cached elevation lookup; fail-soft → {@link Elevation#unavailable()}. */
    public Elevation lookup(double lat, double lon) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new IllegalArgumentException(
                    "Coordinates out of range (" + lat + ", " + lon + ")");
        }
        String key = String.format(Locale.ROOT, "%.4f,%.4f", lat, lon);
        CachedElevation hit = cache.get(key);
        if (hit != null && !isExpired(hit)) {
            return hit.elevation().available() ? hit.elevation().asCached() : hit.elevation();
        }
        Elevation fresh = fetch(lat, lon);
        cache.put(key, new CachedElevation(fresh, Instant.now()));
        return fresh;
    }

    private boolean isExpired(CachedElevation c) {
        return Duration.between(c.cachedAt(), Instant.now()).toHours() >= cacheTtlHours;
    }

    private Elevation fetch(double lat, double lon) {
        String url = String.format(Locale.ROOT,
                "%s/v1/elevation?latitude=%.4f&longitude=%.4f", baseUrl, lat, lon);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp =
                    httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300
                    || resp.body() == null || resp.body().isBlank()) {
                log.debug("Elevation unavailable (HTTP {}); elevation omitted",
                        resp.statusCode());
                return Elevation.unavailable();
            }
            return parse(mapper.readTree(resp.body()));
        } catch (Exception e) {
            log.debug("Elevation lookup failed ({}); elevation omitted", e.getMessage());
            return Elevation.unavailable();
        }
    }

    /**
     * Parse an {@code /v1/elevation} response ({@code {"elevation": [...]}}).
     * Structural mismatch or a non-finite value yields
     * {@link Elevation#unavailable()} — never a guess.
     */
    static Elevation parse(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return Elevation.unavailable();
        }
        JsonNode arr = root.path("elevation");
        if (!arr.isArray() || arr.isEmpty()) return Elevation.unavailable();
        JsonNode first = arr.get(0);
        if (first == null || !first.isNumber()) return Elevation.unavailable();
        double v = first.asDouble();
        if (Double.isNaN(v) || Double.isInfinite(v)) return Elevation.unavailable();
        // Below the Dead Sea shore or above Everest means the encoding changed:
        // report unavailable rather than a wild value.
        if (v < -500 || v > 9000) return Elevation.unavailable();
        return new Elevation(v, true, false,
                "Open-Meteo elevation (Copernicus DEM), context only — not a field survey");
    }

    private record CachedElevation(Elevation elevation, Instant cachedAt) {
    }
}
