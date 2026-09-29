package com.saarthi.soil;

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
 * Lightweight SoilGrids v2.0 point lookup for dynamic (non-Sangrur) blocks.
 *
 * <p>Endpoint: {@code GET {base}/soilgrids/v2.0/properties/query?lon=&lat=}
 * (keyless ISRIC REST). One pixel, 0–5 cm, means only.
 *
 * <p>Contract: lightweight (single small GET per uncached block), cached
 * with a short TTL ({@code saarthi.soil.cache-ttl-minutes}, default 120),
 * and FAIL-SOFT — any transport/parse/shape problem yields
 * {@link SoilProfile#unavailable()}, never fabricated numbers and never an
 * exception to callers.
 *
 * <p>Scale note: values are expressed in the SAME scale as the bundled
 * Sangrur file ({@code risk/soil_context.json}) so context lines stay
 * comparable — texture as the API mean (labelled g/kg, as in the bundle),
 * SOC and pH as mean/10. See {@code DIVISORS}. Missing means stay
 * {@code null} (never zero-filled).
 */
@Component
public class SoilGridsClient {

    private static final Logger log = LoggerFactory.getLogger(SoilGridsClient.class);

    static final String DEFAULT_BASE_URL = "https://rest.isric.org";
    static final String DEPTH = "0-5cm";

    /**
     * API-mean divisors reproducing the bundled-file scale: texture means are
     * used as-is (the bundle stores them at API scale, labelled g/kg);
     * SOC (dg/kg) and pH (pH*10) are divided by 10.
     */
    static final Map<String, Double> DIVISORS = Map.of(
            "clay", 1.0, "sand", 1.0, "silt", 1.0, "soc", 10.0, "phh2o", 10.0);

    /** Point soil values; {@code available=false} means "No data", never zeros. */
    public record SoilProfile(Double clayGkg, Double sandGkg, Double siltGkg,
            Double socGkg, Double ph, boolean available, String source) {
        static SoilProfile unavailable() {
            return new SoilProfile(null, null, null, null, null, false,
                    "SoilGrids point query unavailable for this block");
        }
    }

    @Value("${saarthi.soil.base-url:https://rest.isric.org}")
    private String baseUrl = DEFAULT_BASE_URL;

    @Value("${saarthi.soil.timeout-seconds:15}")
    private int timeoutSeconds = 15;

    @Value("${saarthi.soil.cache-ttl-minutes:120}")
    private long cacheTtlMinutes = 120;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final Map<String, CachedProfile> cache = new ConcurrentHashMap<>();

    public SoilGridsClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Test seam: inject base URL / timeouts / client without Spring. */
    SoilGridsClient(String baseUrl, int timeoutSeconds, long cacheTtlMinutes,
            HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.cacheTtlMinutes = cacheTtlMinutes;
        this.httpClient = httpClient;
    }

    /** Cached point lookup; fail-soft → {@link SoilProfile#unavailable()}. */
    public SoilProfile lookup(double lat, double lon) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new IllegalArgumentException(
                    "Coordinates out of range (" + lat + ", " + lon + ")");
        }
        String key = String.format(Locale.ROOT, "%.5f,%.5f", lat, lon);
        CachedProfile hit = cache.get(key);
        if (hit != null && !isExpired(hit)) {
            return hit.profile();
        }
        SoilProfile fresh = fetch(lat, lon);
        cache.put(key, new CachedProfile(fresh, Instant.now()));
        return fresh;
    }

    private boolean isExpired(CachedProfile c) {
        return Duration.between(c.cachedAt(), Instant.now()).toMinutes() >= cacheTtlMinutes;
    }

    private SoilProfile fetch(double lat, double lon) {
        String url = String.format(Locale.ROOT,
                "%s/soilgrids/v2.0/properties/query?lon=%.5f&lat=%.5f",
                baseUrl, lon, lat);
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
                log.warn("SoilGrids unavailable (HTTP {}); soil context omitted",
                        resp.statusCode());
                return SoilProfile.unavailable();
            }
            return parse(mapper.readTree(resp.body()));
        } catch (Exception e) {
            // Network, timeout, malformed JSON: omit soil, never break callers.
            log.warn("SoilGrids lookup failed ({}); soil context omitted", e.getMessage());
            return SoilProfile.unavailable();
        }
    }

    /**
     * Parse a {@code properties/query} response. Navigates
     * {@code properties.{prop}.depths["0-5cm"].values.mean} with fallbacks
     * for flatter shapes; any structural mismatch yields
     * {@link SoilProfile#unavailable()} — never partial fabrication mixed
     * with real values for the SAME pixel... (per-property nulls are kept
     * null; the profile is marked available only when at least one property
     * parsed).
     */
    static SoilProfile parse(JsonNode root) {
        JsonNode props = root.path("properties");
        if (props.isMissingNode() || !props.isObject()) {
            return SoilProfile.unavailable();
        }
        Double clay = meanFor(props, "clay");
        Double sand = meanFor(props, "sand");
        Double silt = meanFor(props, "silt");
        Double soc = meanFor(props, "soc");
        Double ph = meanFor(props, "phh2o");
        if (clay == null && sand == null && silt == null && soc == null && ph == null) {
            return SoilProfile.unavailable();
        }
        return new SoilProfile(clay, sand, silt, soc, ph, true,
                "SoilGrids 0–5 cm point query (ISRIC), context only — not a measurement");
    }

    /** Mean for one property at 0–5 cm, scaled to bundle scale; {@code null} when absent. */
    static Double meanFor(JsonNode props, String property) {
        JsonNode node = props.path(property);
        if (node.isMissingNode() || node.isNull()) return null;
        // Primary: depths["0-5cm"].values.mean
        JsonNode depths = node.path("depths");
        if (depths.isObject()) {
            Double v = scaledMean(depths.path(DEPTH), property);
            if (v != null) return v;
            // Fallback: first depth whose label contains "0-5".
            var fields = depths.fields();
            while (fields.hasNext()) {
                var e = fields.next();
                if (e.getKey().contains("0-5")) {
                    Double alt = scaledMean(e.getValue(), property);
                    if (alt != null) return alt;
                }
            }
            return null;
        }
        // Flatter shapes: prop["0-5cm"].{mean,Q0.5} or prop.mean
        Double v = scaledMean(node.path(DEPTH), property);
        if (v != null) return v;
        return scaledMean(node, property);
    }

    private static Double scaledMean(JsonNode depthNode, String property) {
        if (depthNode == null || depthNode.isMissingNode() || depthNode.isNull()) return null;
        JsonNode values = depthNode.has("values") ? depthNode.path("values") : depthNode;
        Double mean = null;
        if (values.has("mean") && !values.path("mean").isNull()) {
            mean = values.path("mean").asDouble();
        } else if (values.has("Q0.5") && !values.path("Q0.5").isNull()) {
            mean = values.path("Q0.5").asDouble(); // median fallback, still observed
        }
        if (mean == null) return null;
        return mean / DIVISORS.getOrDefault(property, 1.0);
    }

    private record CachedProfile(SoilProfile profile, Instant cachedAt) {
    }
}
