package com.saarthi.market;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Current official mandi prices (CropAtlas Task 12).
 *
 * <p>Source: the data.gov.in Agmarknet daily-price record set (Ministry of
 * Agriculture and Farmers Welfare, via the data.gov.in open-government API).
 * That API is keyed: without an API key ({@code saarthi.market.api-key}, or the
 * {@code DATA_GOV_IN_API_KEY} environment variable) this service does not call
 * the network at all and every card simply gets the honest
 * {@code available:false} state — never a fabricated number, never a stale one
 * dressed as current.
 */
// __REST__
// __PART_FIELDS__
@Service
public class MandiPriceService {

    private static final Logger log = Logger.getLogger(MandiPriceService.class.getName());

    /**
     * data.gov.in Agmarknet daily-price resource id (the "Current Daily Price
     * of Various Commodities from Various Markets (Mandi)" record set).
     * Override with {@code saarthi.market.resource-id} if the portal rotates it.
     */
    static final String DEFAULT_RESOURCE_ID = "9ef84268-d588-465a-a308-a864a43d0070";

    static final String API_BASE = "https://api.data.gov.in/resource/";

    /** Records older than this are labelled honestly by their real date. */
    static final int FRESHNESS_DAYS = 14;

    /** Short-TTL query cache so one page render does not re-query. */
    static final long CACHE_TTL_MINUTES = 360;

    /**
     * Crop → Agmarknet commodity-name candidates, in lookup order. Only
     * entries on this list are ever sent as the {@code commodity} filter, so a
     * typo or an unknown variety name can never accidentally match the wrong
     * commodity: it simply returns no match. Standard Agmarknet commodity
     * spellings for the CropAtlas requirement-set crops.
     */
    static final Map<String, List<String>> COMMODITY_ALIASES = Map.ofEntries(
            Map.entry("paddy", List.of("Paddy(Dhan)(Common)", "Paddy(Dhan)(Fine)")),
            Map.entry("basmati", List.of("Basmati Rice", "Paddy(Dhan)(Fine)")),
            Map.entry("cotton", List.of("Cotton", "Cotton(Hybrid)")),
            Map.entry("maize", List.of("Maize")),
            Map.entry("wheat", List.of("Wheat")),
            Map.entry("sugarcane", List.of("Sugarcane")));

    @Value("${saarthi.market.api-key:}")
    private String configuredApiKey = "";

    @Value("${saarthi.market.resource-id:" + DEFAULT_RESOURCE_ID + "}")
    private String resourceId = DEFAULT_RESOURCE_ID;

    @Value("${saarthi.market.timeout-seconds:20}")
    private int timeoutSeconds = 20;

    /** Test seam: API key without Spring. */
    void setConfiguredApiKey(String v) { this.configuredApiKey = v == null ? "" : v; }

    /** Test seam: resource id without Spring. */
    void setResourceId(String v) { this.resourceId = v == null ? DEFAULT_RESOURCE_ID : v; }

    /** Test seam: timeout without Spring. */
    void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }

    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();

    private record CachedEntry(Map<String, Object> payload, Instant cachedAt) {}

    /**
     * Resolve the API key: explicit configuration first, then the well-known
     * environment variable. Never logs the key — only whether one is set.
     */
    String resolveApiKey() {
        if (configuredApiKey != null && !configuredApiKey.isBlank()) {
            return configuredApiKey.trim();
        }
        String env = System.getenv("DATA_GOV_IN_API_KEY");
        return env == null || env.isBlank() ? null : env.trim();
    }

    // __METHOD_LATEST__

    /**
     * Latest verified official price for one crop in one state (optionally one
     * district). Keys: available, commodity, market, district, state, date,
     * min_price, modal_price, max_price, unit, source, source_url, note,
     * or reason/message when unavailable.
     */
    public Map<String, Object> latest(String cropId, String state, String district) {
        String key = resolveApiKey();
        if (key == null) {
            return unavailable("not_configured",
                    "No verified price source is configured for this deployment "
                    + "(data.gov.in API key absent). Verify mandi prices locally before acting.");
        }
        List<String> commodities = COMMODITY_ALIASES.get(
                cropId == null ? "" : cropId.trim().toLowerCase(Locale.ROOT));
        if (commodities == null || commodities.isEmpty()) {
            return unavailable("no_commodity_mapping",
                    "This crop is not covered by the configured official price record. "
                    + "Verify mandi prices locally before acting.");
        }
        String stateName = state == null ? "" : state.trim();
        String districtName = district == null ? "" : district.trim();
        String cacheKey = cropId + "|" + stateName + "|" + districtName;
        CachedEntry hit = cache.get(cacheKey);
        if (hit != null
                && Duration.between(hit.cachedAt(), Instant.now()).toMinutes() < CACHE_TTL_MINUTES) {
            return hit.payload();
        }
        for (String commodity : commodities) {
            Map<String, Object> found = query(key, commodity, stateName);
            if (found == null) continue;
            Map<String, Object> picked = prefer(found, districtName);
            cache.put(cacheKey, new CachedEntry(picked, Instant.now()));
            return picked;
        }
        Map<String, Object> out = unavailable("no_recent_record",
                "No recent record for this crop was returned by the official price feed. "
                + "Verify mandi prices locally before acting.");
        cache.put(cacheKey, new CachedEntry(out, Instant.now()));
        return out;
    }

    // __METHOD_QUERY__

    /**
     * One keyed query against data.gov.in. Returns the raw record list wrapped
     * in a map, an honest unavailable wrapper on transport trouble, or
     * {@code null} when this commodity simply has no records (the caller then
     * tries the next alias).
     */
    Map<String, Object> query(String apiKey, String commodity, String state) {
        try {
            StringBuilder url = new StringBuilder(API_BASE).append(resourceId)
                    .append("?api-key=").append(encode(apiKey))
                    .append("&format=json&limit=7&offset=0")
                    .append("&filters%5Bcommodity%5D=").append(encode(commodity));
            if (state != null && !state.isBlank()) {
                url.append("&filters%5Bstate%5D=").append(encode(state));
            }
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8)).build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(Math.max(5, timeoutSeconds)))
                    .header("Accept", "application/json")
                    .header("User-Agent", "SAARTHI-CropAtlas/1.0")
                    .GET().build();
            java.net.http.HttpResponse<String> resp =
                    client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || resp.body() == null) {
                log.warning("Mandi price feed unavailable (HTTP " + resp.statusCode()
                        + "); honest unavailable state is served instead.");
                return keyedUnavailable();
            }
            return parse(resp.body(), commodity);
        } catch (Exception e) {
            log.warning("Mandi price query failed (" + e.getMessage()
                    + "); honest unavailable state is served instead.");
            return keyedUnavailable();
        }
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static Map<String, Object> keyedUnavailable() {
        return unavailable("feed_error",
                "The official price feed is currently unreachable. "
                + "Verify mandi prices locally before acting.");
    }

    // __METHOD_PARSE__

    /** Parse one data.gov.in response into records, or null = no records. */
    Map<String, Object> parse(String body, String commodity) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode records = root.path("records");
            if (!records.isArray() || records.isEmpty()) {
                return null;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode r : records) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("commodity", textOf(r, "commodity"));
                row.put("market", textOf(r, "market"));
                row.put("district", textOf(r, "district"));
                row.put("state", textOf(r, "state"));
                row.put("date", textOf(r, "arrival_date"));
                row.put("min_price", textOf(r, "min_price"));
                row.put("modal_price", textOf(r, "modal_price"));
                row.put("max_price", textOf(r, "max_price"));
                row.put("unit", "quintal");
                rows.add(row);
            }
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("total", textOf(root, "count"));
            wrap.put("rows", rows);
            wrap.put("commodity", commodity);
            return wrap;
        } catch (Exception e) {
            log.warning("Mandi price response unparseable: " + e.getMessage());
            return keyedUnavailable();
        }
    }

    // __METHOD_PREFER__

    /**
     * Pick the record for the requested district when present, else the first
     * record. Fresh/stale is decided from the REAL arrival date only: a record
     * older than the freshness window keeps its date and is still shown with
     * that date, labelled as old; a record with no date is not presented as
     * current.
     */
    Map<String, Object> prefer(Map<String, Object> found, String district) {
        if (found.containsKey("available") && Boolean.FALSE.equals(found.get("available"))) {
            return found;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) found.getOrDefault("rows", List.of());
        Map<String, Object> chosen = null;
        if (district != null && !district.isBlank()) {
            for (Map<String, Object> row : rows) {
                if (district.equalsIgnoreCase(String.valueOf(row.get("district")))) {
                    chosen = row;
                    break;
                }
            }
        }
        if (chosen == null && !rows.isEmpty()) chosen = rows.get(0);
        if (chosen == null) {
            return unavailable("no_recent_record",
                    "No recent record for this crop was returned by the official price feed. "
                    + "Verify mandi prices locally before acting.");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", true);
        out.put("commodity", chosen.get("commodity"));
        out.put("market", chosen.get("market"));
        out.put("district", chosen.get("district"));
        out.put("state", chosen.get("state"));
        out.put("date", chosen.get("date"));
        out.put("min_price", chosen.get("min_price"));
        out.put("modal_price", chosen.get("modal_price"));
        out.put("max_price", chosen.get("max_price"));
        out.put("unit", "quintal");
        out.put("source", "Agmarknet via data.gov.in (Ministry of Agriculture and Farmers Welfare)");
        out.put("source_url", "https://www.data.gov.in/resource/"
                + "current-daily-price-various-commodities-various-markets-mandi");
        String freshness = freshnessNote(String.valueOf(chosen.get("date")));
        out.put("note", freshness == null
                ? "Latest record served with its real date."
                : freshness);
        return out;
    }



    /**
     * Honesty label for one record date: null inside the freshness window, a
     * real-date warning when older, and a caution when it cannot be read.
     */
    static String freshnessNote(String iso) {
        if (iso == null || iso.isBlank() || "null".equalsIgnoreCase(iso)) {
            return "The record carries no readable date, so treat it as unverified - verify the "
                    + "mandi price locally before acting.";
        }
        try {
            String day = iso.length() >= 10 ? iso.substring(0, 10) : iso;
            java.time.LocalDate date = java.time.LocalDate.parse(day);
            long age = java.time.temporal.ChronoUnit.DAYS.between(
                    date, java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
            if (age <= FRESHNESS_DAYS) return null;
            return "The latest official record is dated " + day + " (about " + age
                    + " days old) - verify the mandi price locally before acting.";
        } catch (RuntimeException e) {
            return "The record date (" + iso + ") could not be read, so treat it as unverified - "
                    + "verify the mandi price locally before acting.";
        }
    }

    private static String textOf(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText().trim() : null;
    }

    static Map<String, Object> unavailable(String reason, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", false);
        out.put("reason", reason);
        out.put("message", message);
        out.put("source", "Agmarknet via data.gov.in (Ministry of Agriculture and Farmers Welfare)");
        return out;
    }
}