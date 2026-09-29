package com.saarthi.cropatlas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live query client for the official FAO ECOCROP environmental search.
 *
 * <p><b>What this is.</b> ECOCROP publishes no bulk dataset and no JSON API
 * (verified against the official FAO catalog record, which lists only the web
 * tool itself). The only supported machine path is the tool's own
 * environment-search form: {@code GET cropSearchForm} to obtain a fresh
 * {@code JSESSIONID}, then {@code POST cropSearch} with that session cookie.
 * This client performs exactly that two-step flow — one search per call —
 * and parses the server-rendered result list. It never walks the 2,568-crop
 * database, never scrapes per-crop datasheets in bulk, and never consults any
 * third-party mirror.
 *
 * <p><b>What this returns.</b> Each search result row carries only what the
 * result list itself states: the scientific name, the EcoPort code, and the
 * {@code cropView} identifier. No requirement values are inferred from the
 * list; anything beyond name/code/identifier is fetched per displayed
 * candidate only ({@link #fetchCommonNames(String)}) or left unknown.
 *
 * <p><b>Failure contract.</b> Any transport problem, non-2xx status, missing
 * session, ECOCROP {@code Error} page, or structurally unrecognised result
 * page yields {@link EcocropUnavailableException} — never an empty guess
 * dressed as an answer, and never a fallback crop list. Callers translate
 * that into an honest unavailable state.
 *
 * <p><b>Volume contract.</b> Successful searches are cached for a short TTL
 * keyed by the exact query, so repeated requests for the same block and
 * environment reuse one upstream answer instead of re-querying FAO.
 */
@Component
public class EcocropSearchClient {

    private static final Logger log = LoggerFactory.getLogger(EcocropSearchClient.class);

    static final String DEFAULT_BASE_URL = "https://ecocrop.apps.fao.org/ecocrop/srv/en";
    static final String SOURCE_NAME = "FAO ECOCROP";
    static final String SOURCE_TITLE = "FAO ECOCROP — Crop Ecological Requirements Database";
    static final String SOURCE_URL = "https://ecocrop.apps.fao.org/ecocrop/srv/en/home";

    /** Marker present on every genuine result page, including zero-hit pages. */
    static final String RESULT_MARKER = "Plants were found";
    private static final Pattern FOUND_COUNT = Pattern.compile("(\\d+)\\s+Plants were found");
    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<td[^>]*>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern CROP_VIEW_LINK =
            Pattern.compile("cropView\\?id=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMON_NAMES_ROW = Pattern.compile(
            "[Cc]ommon names</th>\\s*<td[^>]*>(.*?)</td>", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    /** Next-section marker inside DESCRIPTION prose (colon optional on live pages). */
    private static final Pattern NEXT_SECTION = Pattern.compile("\\s[A-Z][A-Z .\\-/]{2,30}:?\\s");

    /**
     * One result-list row, exactly as ECOCROP states it. No requirement data
     * is attached: the list does not publish any.
     */
    public record EcocropHit(String scientificName, String ecoportId, String cropViewPath) {
        /** Absolute {@code cropView} URL for this hit. */
        public String cropViewUrl(String baseUrl) {
            String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL
                    : baseUrl.replaceAll("/+$", "");
            return base + cropViewPath;
        }
    }

    /** One completed search: hits in ECOCROP's own result order. */
    public record SearchResult(List<EcocropHit> hits, int totalFound, boolean cached,
                               Instant retrievedAt) {}

    /** Upstream could not serve a trustworthy answer. Never a fallback trigger. */
    public static class EcocropUnavailableException extends RuntimeException {
        private final String reason;

        public EcocropUnavailableException(String reason, String detail) {
            super("FAO ECOCROP unavailable (" + reason + ")"
                    + (detail == null ? "" : ": " + detail));
            this.reason = reason;
        }

        public EcocropUnavailableException(String reason, Throwable cause) {
            super("FAO ECOCROP unavailable (" + reason + "): " + cause.getMessage(), cause);
            this.reason = reason;
        }

        /** Machine-readable cause: session_failed, http_error, error_page, malformed, timeout. */
        public String reason() { return reason; }
    }

    @Value("${saarthi.ecocrop.base-url:https://ecocrop.apps.fao.org/ecocrop/srv/en}")
    private String baseUrl = DEFAULT_BASE_URL;

    @Value("${saarthi.ecocrop.timeout-seconds:25}")
    private int timeoutSeconds = 25;

    @Value("${saarthi.ecocrop.cache-ttl-minutes:120}")
    private long cacheTtlMinutes = 120;

    private final Supplier<HttpClient> httpClientFactory;
    private final Map<String, CachedSearch> cache = new ConcurrentHashMap<>();

    public EcocropSearchClient() {
        this.httpClientFactory = () -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();
    }

    /**
     * Test seam: fixed base URL / timeouts / client factory without Spring, so
     * tests can point at a local stub server with recorded responses.
     */
    EcocropSearchClient(String baseUrl, int timeoutSeconds, long cacheTtlMinutes,
            Supplier<HttpClient> httpClientFactory) {
        this.baseUrl = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.cacheTtlMinutes = cacheTtlMinutes;
        this.httpClientFactory = httpClientFactory;
    }

    /**
     * Run one environment search against official FAO ECOCROP.
     *
     * @param envParams environment subset (minTemperature, maxTemperature,
     *                  minRainfall, maxRainfall, minSoilPh, maxSoilPh,
     *                  latitude, altitude); every other ECOCROP field is sent
     *                  unrestricted. Must carry at least one dimension — a
     *                  blank query would enumerate the database, which this
     *                  client refuses to do.
     * @param absolute  {@code true} for Absolute mode, {@code false} for Optimal.
     * @param quantity  ECOCROP result cap (e.g. "25") or "" for all results.
     * @throws IllegalArgumentException      on malformed numeric input (no HTTP call made).
     * @throws EcocropUnavailableException   whenever no trustworthy answer exists.
     */
    public SearchResult search(Map<String, String> envParams, boolean absolute, String quantity) {
        Map<String, String> form = buildForm(envParams, absolute, quantity);
        String key = cacheKey(form);
        CachedSearch hit = cache.get(key);
        if (hit != null
                && Duration.between(hit.cachedAt(), Instant.now()).toMinutes() < cacheTtlMinutes) {
            return new SearchResult(hit.result().hits(), hit.result().totalFound(), true,
                    hit.result().retrievedAt());
        }
        SearchResult fresh = execute(form);
        cache.put(key, new CachedSearch(fresh, Instant.now()));
        return fresh;
    }

    /**
     * Common names for one displayed candidate, fetched from its
     * {@code cropView} page. Stateless GET (verified to need no session).
     * Fail-soft: {@code null} when the page cannot serve names — never a guess.
     */
    public String fetchCommonNames(String ecoportId) {
        List<String> all = fetchCommonNameList(ecoportId);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Per-crop detail from one {@code cropView} page: common names plus the
     * crop-specific DESCRIPTION notes ECOCROP itself publishes (cold
     * tolerance, growing period). Only segments ECOCROP actually prints are
     * returned; anything absent stays {@code null} — never inferred.
     */
    public record CropDetail(List<String> names, String killingTemp, String growingPeriod) {
        public CropDetail {
            names = names == null ? List.of() : List.copyOf(names);
        }

        static CropDetail empty() {
            return new CropDetail(List.of(), null, null);
        }
    }

    /**
     * All common names for one displayed candidate, in ECOCROP's own order,
     * cleaned and de-duplicated. The cell can hold a long synonym wall, so
     * callers show the first entry as the title and at most two more as
     * alternates — the rest never reach the UI. Fail-soft: empty when the
     * page cannot serve names — never a guess.
     */
    public List<String> fetchCommonNameList(String ecoportId) {
        return fetchCropDetail(ecoportId).names();
    }

    /**
     * One HTTP GET for names + notes together (same page, no extra request).
     * Fail-soft: empty detail when the page cannot be served — never a guess.
     */
    public CropDetail fetchCropDetail(String ecoportId) {
        if (ecoportId == null || !ecoportId.matches("\\d+")) {
            throw new IllegalArgumentException(
                    "EcoPort id must be numeric, got '" + ecoportId + "'");
        }
        try {
            HttpClient client = httpClientFactory.get();
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create(base() + "/cropView?id=" + ecoportId))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "text/html")
                    .header("User-Agent", "SAARTHI-CropAtlas/1.0 (+https://www.fao.org/)")
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300 || resp.body() == null) {
                log.debug("ECOCROP cropView {} unavailable (HTTP {})", ecoportId,
                        resp.statusCode());
                return CropDetail.empty();
            }
            return parseCropDetail(resp.body());
        } catch (Exception e) {
            log.debug("ECOCROP cropView {} failed ({}); detail omitted", ecoportId,
                    e.getMessage());
            return CropDetail.empty();
        }
    }

    /** Number of cached successful searches (for the catalog endpoint). */
    public int cacheSize() { return cache.size(); }

    /** Absolute URL for a {@code cropView} path against the configured base. */
    public String resolveCropViewUrl(String cropViewPath) {
        return base() + (cropViewPath == null ? "" : cropViewPath);
    }

    public long cacheTtlMinutes() { return cacheTtlMinutes; }

    /** Test seam: drop all cached searches. */
    void clearCache() { cache.clear(); }

    // ---- execution ----

    private SearchResult execute(Map<String, String> form) {
        HttpClient client = httpClientFactory.get();
        // Step 1: fresh session. A stateless POST is rejected by ECOCROP, so
        // the form GET (which sets JSESSIONID) is mandatory, not optional.
        // The session cookie is carried manually (read from Set-Cookie, sent
        // back verbatim) so the flow is explicit and testable.
        String sessionCookie;
        try {
            HttpRequest get = HttpRequest.newBuilder(URI.create(base() + "/cropSearchForm"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "text/html")
                    .header("User-Agent", "SAARTHI-CropAtlas/1.0 (+https://www.fao.org/)")
                    .GET()
                    .build();
            HttpResponse<String> got = client.send(get, HttpResponse.BodyHandlers.ofString());
            if (got.statusCode() < 200 || got.statusCode() >= 300) {
                throw new EcocropUnavailableException("session_failed",
                        "form GET returned HTTP " + got.statusCode());
            }
            sessionCookie = sessionCookie(got);
            if (sessionCookie == null) {
                throw new EcocropUnavailableException("session_failed",
                        "form GET set no JSESSIONID cookie");
            }
        } catch (EcocropUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EcocropUnavailableException(isTimeout(e) ? "timeout" : "session_failed", e);
        }
        // Step 2: the environment search on that session.
        String body;
        try {
            HttpRequest post = HttpRequest.newBuilder(URI.create(base() + "/cropSearch"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "text/html")
                    .header("Cookie", sessionCookie)
                    .header("User-Agent", "SAARTHI-CropAtlas/1.0 (+https://www.fao.org/)")
                    .POST(HttpRequest.BodyPublishers.ofString(encode(form)))
                    .build();
            HttpResponse<String> resp = client.send(post, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300 || resp.body() == null) {
                throw new EcocropUnavailableException("http_error",
                        "search returned HTTP " + resp.statusCode());
            }
            body = resp.body();
        } catch (EcocropUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new EcocropUnavailableException(isTimeout(e) ? "timeout" : "http_error", e);
        }
        if (!body.contains(RESULT_MARKER)) {
            throw new EcocropUnavailableException("error_page",
                    "response carries no result marker (ECOCROP Error page or changed layout)");
        }
        return parse(body);
    }

    // ---- form ----

    private Map<String, String> buildForm(Map<String, String> env, boolean absolute,
            String quantity) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("lifeForm", "0");
        form.put("habit", "0");
        form.put("category", "0");
        form.put("lifeSpan", "0");
        form.put("plantAttribute", "0");
        form.put("opt", absolute ? "0" : "1");
        // Environment subset: validated numerics or omitted (never blank-guessed).
        putNumeric(form, "minTemperature", env, -60, 60);
        putNumeric(form, "maxTemperature", env, -60, 60);
        putNumeric(form, "minRainfall", env, 0, 15000);
        putNumeric(form, "maxRainfall", env, 0, 15000);
        putNumeric(form, "minSoilPh", env, 0, 14);
        putNumeric(form, "maxSoilPh", env, 0, 14);
        form.put("minLightIntensity", "0");
        form.put("maxLightIntensity", "0");
        form.put("climateZone", "0");
        form.put("photoperiod", "0");
        putNumeric(form, "latitude", env, -90, 90);
        putNumeric(form, "altitude", env, -500, 9000);
        form.put("availableFieldDays", "");
        form.put("soilDepth", "0");
        form.put("soilTexture", "0");
        form.put("soilFertility", "0");
        form.put("soilSalinity", "0");
        form.put("soilDrainage", "0");
        form.put("mainUse", "0");
        form.put("detailedUse", "");
        form.put("usedPart", "0");
        form.put("quantity", quantity == null ? "" : quantity);
        long dims = form.keySet().stream()
                .filter(k -> env.containsKey(k) && !env.get(k).isBlank())
                .count();
        if (dims == 0) {
            throw new IllegalArgumentException(
                    "Refusing a blank ECOCROP query: at least one environmental dimension is "
                            + "required (a blank query would enumerate the database)");
        }
        return form;
    }

    private static void putNumeric(Map<String, String> form, String key, Map<String, String> env,
            double lo, double hi) {
        String raw = env == null ? null : env.get(key);
        if (raw == null || raw.isBlank()) return;
        double v;
        try {
            v = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "ECOCROP parameter '" + key + "' is not numeric: '" + raw + "'");
        }
        if (!Double.isFinite(v) || v < lo || v > hi) {
            throw new IllegalArgumentException("ECOCROP parameter '" + key + "' out of range ["
                    + lo + ", " + hi + "]: '" + raw + "'");
        }
        form.put(key, raw.trim());
    }

    private static String encode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (!sb.isEmpty()) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(),
                    StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String cacheKey(Map<String, String> form) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            StringBuilder canonical = new StringBuilder();
            form.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> canonical.append(e.getKey()).append('=')
                            .append(e.getValue()).append('&'));
            byte[] digest = sha.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return form.toString();
        }
    }

    // ---- parsing (result-list rows only; no datasheet walking) ----

    static SearchResult parse(String html) {
        Matcher count = FOUND_COUNT.matcher(html);
        if (!count.find()) {
            throw new EcocropUnavailableException("malformed",
                    "result marker present but hit count unreadable");
        }
        int total;
        try {
            total = Integer.parseInt(count.group(1));
        } catch (NumberFormatException e) {
            throw new EcocropUnavailableException("malformed", "hit count not numeric");
        }
        List<EcocropHit> hits = new ArrayList<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = CELL.matcher(rows.group(1));
            while (cell.find()) cells.add(cell.group(1));
            if (cells.size() < 2) continue;
            String name = unescape(stripTags(cells.get(0))).trim();
            String code = unescape(stripTags(cells.get(1))).trim();
            Matcher link = CROP_VIEW_LINK.matcher(rows.group(1));
            if (name.isEmpty() || !code.matches("\\d+") || !link.find()) continue;
            hits.add(new EcocropHit(name, code, "/cropView?id=" + link.group(1)));
        }
        if (total > 0 && hits.isEmpty()) {
            throw new EcocropUnavailableException("malformed",
                    "ECOCROP reports " + total + " hits but no result rows parsed");
        }
        return new SearchResult(List.copyOf(hits), total, false, Instant.now());
    }

    static String parseCommonNames(String html) {
        List<String> all = parseCommonNameList(html);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Names + crop-specific DESCRIPTION notes from one {@code cropView} page.
     * "KILLING T." (cold tolerance) and "GROWING PERIOD" are ECOCROP's own
     * per-species statements, so they genuinely differ between crops. Either
     * may be absent — absent stays {@code null}.
     */
    static CropDetail parseCropDetail(String html) {
        List<String> names = parseCommonNameList(html);
        return new CropDetail(names, parseNoteSegment(html, "KILLING T."),
                parseNoteSegment(html, "GROWING PERIOD"));
    }

    /**
     * One DESCRIPTION segment (e.g. cold tolerance, growing period), cut at
     * the next all-caps section marker or a length cap (trimmed to the last
     * full sentence). Plain ECOCROP prose, quoted verbatim. Live pages vary:
     * "KILLING T.:" vs "KILLING T  " — the marker tolerates a missing period
     * and a missing colon.
     */
    static String parseNoteSegment(String html, String marker) {
        if (html == null || marker == null) return null;
        String text = unescape(stripTags(html)).replaceAll("\\s+", " ");
        String core = marker.endsWith(".") ? marker.substring(0, marker.length() - 1) : marker;
        Matcher head = Pattern.compile(Pattern.quote(core) + "\\.?\\s*:?\\s*").matcher(text);
        if (!head.find()) return null;
        String rest = text.substring(head.end()).trim();
        if (rest.isEmpty()) return null;
        Matcher end = NEXT_SECTION.matcher(rest);
        String seg;
        if (end.find()) {
            seg = rest.substring(0, end.start()).trim();
        } else if (rest.length() > 320) {
            String cut = rest.substring(0, 320);
            int dot = cut.lastIndexOf(". ");
            seg = (dot > 120 ? cut.substring(0, dot + 1) : cut).trim();
        } else {
            seg = rest;
        }
        return seg.isEmpty() ? null : seg;
    }

    /**
     * Split the cropView "Common names" cell into individual names in ECOCROP
     * order: parenthetical notes skipped, blanks and case-insensitive
     * duplicates dropped, whitespace collapsed. The first entry is the card
     * title; callers may show at most two more as alternates.
     */
    static List<String> parseCommonNameList(String html) {
        if (html == null) return List.of();
        Matcher m = COMMON_NAMES_ROW.matcher(html);
        if (!m.find()) return List.of();
        String names = unescape(stripTags(m.group(1))).trim();
        names = names.replaceAll("\\s+", " ");
        if (names.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : names.split("[,;/|]")) {
            String p = part.trim().replaceAll("\\s+", " ");
            if (p.isEmpty() || p.startsWith("(")) continue;
            boolean dup = false;
            for (String e : out) {
                if (e.equalsIgnoreCase(p)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) out.add(p);
        }
        return List.copyOf(out);
    }

    private static String stripTags(String s) {
        return TAG.matcher(s).replaceAll(" ");
    }

    /** Minimal entity decoding for result text (Latin names rarely need it; notes do). */
    static String unescape(String s) {
        if (s == null) return null;
        // Mojibake first: some ECOCROP pages arrive UTF-8 but read as Latin-1
        // ("5Â°C" for "5°C"). Only the unambiguous pairs are repaired.
        if (s.contains("Â") || s.contains("Ã")) {
            s = s.replace("Â°", "°").replace("Ã©", "é").replace("Ã¨", "è")
                    .replace("Ã´", "ô").replace("Ã®", "î").replace("Ã±", "ñ")
                    .replace("Ã§", "ç").replace("Ã¢", "â");
        }
        if (!s.contains("&")) return s;
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replace("&deg;", "°").replace("&#176;", "°")
                .replace("&ocirc;", "ô").replace("&Ocirc;", "Ô")
                .replace("&eacute;", "é").replace("&egrave;", "è")
                .replace("&iacute;", "í").replace("&aacute;", "á")
                .replace("&uacute;", "ú").replace("&ntilde;", "ñ");
    }

    private String base() {
        String b = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        return b.replaceAll("/+$", "");
    }

    /** The {@code JSESSIONID} session cookie from a form response, if any. */
    static String sessionCookie(HttpResponse<String> formResponse) {
        if (formResponse == null) return null;
        for (String header : formResponse.headers().allValues("Set-Cookie")) {
            try {
                for (HttpCookie c : HttpCookie.parse(header)) {
                    if ("JSESSIONID".equalsIgnoreCase(c.getName())
                            && c.getValue() != null && !c.getValue().isBlank()) {
                        return "JSESSIONID=" + c.getValue().trim();
                    }
                }
            } catch (IllegalArgumentException e) {
                log.debug("Ignoring unparsable Set-Cookie header");
            }
        }
        return null;
    }

    private static boolean isTimeout(Exception e) {
        return e instanceof java.net.http.HttpTimeoutException
                || e instanceof java.net.ConnectException
                || (e.getMessage() != null && e.getMessage().toLowerCase(Locale.ROOT)
                        .contains("timed out"));
    }

    private record CachedSearch(SearchResult result, Instant cachedAt) {
    }
}
