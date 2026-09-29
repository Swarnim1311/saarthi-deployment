package com.saarthi.cropatlas;

import com.saarthi.geo.GeographyController;
import com.saarthi.weather.WeatherController;
import com.saarthi.weather.WeatherProviderException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CropAtlas API — Global Crop Discovery.
 *
 * <p>Four endpoints over ONE measured context per request:
 * <ul>
 *   <li>{@code /api/cropatlas/context} — the environmental fingerprint alone</li>
 *   <li>{@code /api/cropatlas/crops} — the dynamic-source descriptor (no static list)</li>
 *   <li>{@code /api/cropatlas/catalog} — dynamic-source metadata and cache state</li>
 *   <li>{@code /api/cropatlas/recommendations} — fingerprint + dynamic FAO
 *       ECOCROP candidates + global comparison + methodology and sources</li>
 * </ul>
 *
 * <p>Location identity follows the existing SAARTHI convention: the registry
 * triple {@code state}/{@code district}/{@code code} for any Indian block, or
 * {@code ?block=} for the six legacy Sangrur blocks (which keeps their richer
 * polygon sampling and bundled soil means). There is no default block: an absent
 * selection is HTTP 400, never a silent Sangrur substitution.
 *
 * <p>Errors: 400 invalid/missing parameters, 404 unknown geography, 502
 * upstream provider failure, 503 live forecast unavailable. No endpoint ever
 * returns synthetic fallback data.
 */
@RestController
@RequestMapping("/api/cropatlas")
public class CropAtlasController {

    private final CropAtlasService atlas;
    // Retained for constructor compatibility and the engine's own unit tests.
    // The serving path is dynamic FAO ECOCROP discovery (see {@link #discover});
    // no static crop list is served as candidates anywhere.
    @SuppressWarnings("unused")
    private final CropSuitabilityService suitability;
    @SuppressWarnings("unused")
    private final CropRequirements requirements;
    private final CropAtlasMethod method;
    private final GlobalRegionMatcher regions;
    private volatile EcocropDiscoveryService discovery;
    /** Condition-match scorer for dynamic candidates (Task 8). */
    private final CropConditionMatchService conditionMatch;

    @Autowired
    public CropAtlasController(CropAtlasService atlas, CropSuitabilityService suitability,
            CropRequirements requirements, CropAtlasMethod method, GlobalRegionMatcher regions,
            CropConditionMatchService conditionMatch) {
        this.atlas = atlas;
        this.suitability = suitability;
        this.requirements = requirements;
        this.method = method;
        this.regions = regions;
        this.conditionMatch = conditionMatch;
    }

    /**
     * Dynamic discovery is optional-wired so the API stays honest when the
     * upstream is unwired: recommendations then report ECOCROP unavailable
     * with zero candidates instead of falling back to any static crop list.
     */
    @Autowired(required = false)
    public void setDiscovery(EcocropDiscoveryService discovery) {
        this.discovery = discovery;
    }

    /** Environmental fingerprint for one selection; no crop assessment. */
    @GetMapping("/context")
    public ResponseEntity<Map<String, Object>> context(
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "district", required = false) String district,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "block", required = false) String block) {
        CropAtlasService.Context ctx = atlas.context(state, district, code, block);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("location", ctx.location());
        out.put("fingerprint", ctx.fingerprint().toMap());
        out.put("methodology", methodology());
        return ResponseEntity.ok(out);
    }

    /**
     * The crop catalogue descriptor. There is deliberately NO static crop
     * list: candidates are discovered per block from FAO ECOCROP (see
     * {@code /recommendations} and {@code /catalog}). This endpoint describes
     * the dynamic source so the UI can show what is being queried.
     */
    @GetMapping("/crops")
    public ResponseEntity<Map<String, Object>> crops() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dynamic", true);
        out.put("source", EcocropSearchClient.SOURCE_NAME);
        out.put("source_title", EcocropSearchClient.SOURCE_TITLE);
        out.put("source_url", EcocropSearchClient.SOURCE_URL);
        out.put("method_version", method.methodVersion());
        out.put("catalogue", "per-block; candidates are retrieved live per selection via "
                + "/api/cropatlas/recommendations. No static crop list is served.");
        out.put("catalog_endpoint", "/api/cropatlas/catalog");
        out.put("not_scored_dimensions", List.copyOf(EcocropQueryMapper.NEVER_SENT));
        out.put("validation_note", method.validationNote());
        return ResponseEntity.ok(out);
    }

    /**
     * Dynamic-source metadata: what backs candidate discovery, and the live
     * query-cache state. No crop data itself — that is per block.
     */
    @GetMapping("/catalog")
    public ResponseEntity<Map<String, Object>> catalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", EcocropSearchClient.SOURCE_NAME);
        out.put("source_title", EcocropSearchClient.SOURCE_TITLE);
        out.put("source_url", EcocropSearchClient.SOURCE_URL);
        out.put("dynamic", true);
        out.put("mode", "Absolute-mode environmental containment search, one live query per "
                + "block/environment (short-TTL cached)");
        out.put("display_cap", EcocropDiscoveryService.MAX_DISPLAY);
        EcocropDiscoveryService d = discovery;
        out.put("discovery_wired", d != null);
        out.put("cache_size", d == null ? null : d.cacheSize());
        out.put("cache_ttl_minutes", d == null ? null : d.cacheTtlMinutes());
        out.put("retrieved_at", java.time.Instant.now().toString());
        return ResponseEntity.ok(out);
    }

    /** Full CropAtlas payload: fingerprint + dynamic candidates + regions + methodology. */
    @GetMapping("/recommendations")
    public ResponseEntity<Map<String, Object>> recommendations(
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "district", required = false) String district,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "block", required = false) String block) {
        CropAtlasService.Context ctx = atlas.context(state, district, code, block);
        EcocropDiscoveryService.Discovery disc = discover(ctx.fingerprint());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("location", ctx.location());
        out.put("fingerprint", ctx.fingerprint().toMap());
        List<Map<String, Object>> ordered = new java.util.ArrayList<>();
        java.time.LocalDate today = java.time.LocalDate.now();
        for (EcocropDiscoveryService.EcocropCandidate c : disc.candidates()) {
            Map<String, Object> cm = c.toMap();
            // Task 8–11: per-crop CONDITION MATCH from the shared suitability
            // engine, scored for THIS block's fingerprint. A species with no
            // sourced requirement set carries the honest unavailable state with
            // NO percentage — never a borrowed or mid-range score.
            cm.put("condition_match", conditionMatch.match(
                    c.scientificName(), c.commonName(), ctx.fingerprint(), today));
            ordered.add(cm);
        }
        Map<String, Object> groups = new LinkedHashMap<>();
        groups.put("ecocrop_matches", ordered);
        groups.put("insufficient_data", List.of());
        out.put("groups", groups);
        out.put("candidate_count", ordered.size());
        out.put("candidates", ordered);
        out.put("ecocrop", disc.toMap());
        out.put("global_comparison", regions.compare(ctx.fingerprint()));
        out.put("methodology", methodology());
        out.put("sources", ctx.fingerprint().sources());
        return ResponseEntity.ok(out);
    }

    /**
     * Dynamic discovery for one fingerprint. With no discovery service wired
     * (or a fingerprint that cannot be queried) this is an honest unavailable
     * state with zero candidates — the old static crop list is never a fallback.
     */
    EcocropDiscoveryService.Discovery discover(EnvironmentalFingerprint fp) {
        EcocropDiscoveryService d = discovery;
        if (d == null) {
            return new EcocropDiscoveryService.Discovery(List.of(), 0, false,
                    "Dynamic crop discovery is not wired in this deployment. No substitute "
                            + "candidates are shown.",
                    List.of(), List.copyOf(EcocropQueryMapper.NEVER_SENT), 0, false,
                    java.time.Instant.now());
        }
        return d.discover(fp);
    }

    /**
     * Methodology, honestly describing what the assessment is and is not.
     * Shared by every endpoint so the UI never has to restate it.
     */
    Map<String, Object> methodology() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("method_version", method.methodVersion());
        m.put("candidate_source", EcocropSearchClient.SOURCE_NAME);
        m.put("candidate_source_title", EcocropSearchClient.SOURCE_TITLE);
        m.put("candidate_source_url", EcocropSearchClient.SOURCE_URL);
        m.put("kind", "Dynamic environmental crop discovery over FAO ECOCROP");
        m.put("is_ml_prediction", false);
        m.put("is_probability", false);
        m.put("is_yield_guarantee", false);
        m.put("statement", "CropAtlas sends this block's measured environmental conditions to "
                + "the official FAO ECOCROP environment search and shows the species ECOCROP "
                + "itself returns. Each candidate is an FAO ECOCROP environmental match — "
                + "a species-level compatibility signal, not a machine-learning prediction, "
                + "not a probability of success, not a yield forecast, and not a "
                + "variety/cultivar recommendation.");
        m.put("query_dimensions", List.of("temperature", "rainfall_annual_normal", "soil_ph",
                "latitude", "elevation"));
        m.put("query_note", "Only dimensions actually measured for the block are sent. "
                + "Forecast-horizon temperature extremes are used as observed (not annual "
                + "normals); annual rainfall only where deployment reference normals exist — "
                + "live forecast rainfall is never substituted. Categorical soil properties "
                + "are never guessed and stay unrestricted.");
        m.put("fallback_strategy", "Queries run strongest-first and only shed dimensions "
                + "(elevation, latitude, soil pH, rainfall normal, in that order); measured "
                + "values are never widened or edited. An empty answer stays empty.");
        m.put("not_scored_dimensions", List.copyOf(EcocropQueryMapper.NEVER_SENT));
        m.put("not_scored_reason", "These dimensions are never sent to ECOCROP because no "
                + "trustworthy block-side mapping exists; they stay unrestricted instead of "
                + "being guessed.");
        m.put("score_interpretation", "ECOCROP candidates carry no SAARTHI compatibility "
                + "score: ECOCROP result lists publish no per-crop requirement values to "
                + "score against. Candidates report matched and unavailable dimensions instead.");
        m.put("display_cap", EcocropDiscoveryService.MAX_DISPLAY);
        m.put("validation_note", method.validationNote());
        return m;
    }

    // ---- error handling ----

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return err(HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage(), null);
    }

    @ExceptionHandler(GeographyController.UnknownGeographyException.class)
    public ResponseEntity<Map<String, Object>> unknownGeography(
            GeographyController.UnknownGeographyException ex) {
        return err(HttpStatus.NOT_FOUND, ex.getError(), ex.getMessage(), null);
    }

    @ExceptionHandler(WeatherController.WeatherUnavailableException.class)
    public ResponseEntity<Map<String, Object>> forecastUnavailable(
            WeatherController.WeatherUnavailableException ex) {
        return err(HttpStatus.SERVICE_UNAVAILABLE, "forecast_unavailable", ex.getMessage(), null);
    }

    @ExceptionHandler(WeatherProviderException.class)
    public ResponseEntity<Map<String, Object>> providerError(WeatherProviderException ex) {
        return err(HttpStatus.BAD_GATEWAY, "provider_error", ex.getMessage(), null);
    }

    private static ResponseEntity<Map<String, Object>> err(HttpStatus status, String code,
            String message, String extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        body.put("no_fallback", true);
        body.put("note", "CropAtlas returns no synthetic or substitute data when a source is "
                + "unavailable.");
        if (extra != null) body.put("detail", extra);
        return ResponseEntity.status(status).body(body);
    }
}
