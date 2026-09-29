package com.saarthi.risks;

import com.saarthi.service.RealForecastService;
import com.saarthi.shadow.ShadowLedger;
import com.saarthi.weather.LiveWeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * FIELD_HIGH live shadow capture (validation/data-collection only).
 *
 * <p>Separate ledger from the dry-spell shadow
 * ({@code data/processed/shadow/field_shadow.jsonl}, schema
 * {@code field-shadow/v1}, issue {@code field:<date>}) — the dry-spell
 * ledger file and schema are never touched. Same first-write-wins
 * immutability mechanics, reused via {@link ShadowLedger}.
 *
 * <p>Hooked at the same live-forecast retrieval boundary, fail-soft: any
 * failure is logged and weather serving continues. Field capture needs no
 * CHIRPS (D+1..D+3 forecast + packaged p95 only), so records are always
 * complete predictions; truth (CHIRPS D+1..D+3 wet count ≥ 2) is attached
 * later by {@code python -m src.shadow.field_shadow}.
 */
@Service
public class FieldShadowService {

    private static final Logger log = LoggerFactory.getLogger(FieldShadowService.class);

    public static final String SCHEMA_VERSION = "field-shadow/v1";
    public static final String SOURCE_TAG = "live_ifs_field";

    // Pre-registered gate (reported only; needs >= 30 resolved issues).
    public static final double RECALL_GATE = 0.60;
    public static final double PRECISION_GATE = 0.40;
    public static final int MIN_ISSUES_FOR_GATE = 30;

    private final ShadowLedger ledger;
    private final HeavyRainThresholds heavyThresholds;

    @Autowired
    public FieldShadowService(
            @Value("${saarthi.fieldshadow.path:}") String shadowPath,
            HeavyRainThresholds heavyThresholds) {
        this(resolveLedger(shadowPath), heavyThresholds);
        log.info("Field shadow ledger: {}", ledger.path().toAbsolutePath());
    }

    /** Test seam: inject ledger + thresholds directly (either may be {@code null}). */
    public FieldShadowService(ShadowLedger ledger, HeavyRainThresholds heavyThresholds) {
        this.ledger = ledger;
        this.heavyThresholds = heavyThresholds;
    }

    /**
     * Capture one forecast issue. Never throws: any failure is logged and
     * reported as {@code skipped_error} so weather serving is unaffected.
     */
    public Map<String, String> tryCapture(LiveWeatherService.LiveForecast fc) {
        try {
            return capture(fc);
        } catch (Exception e) {
            log.warn("Field shadow capture failed (weather serving unaffected): {}",
                    e.getMessage());
            return Map.of("status", "skipped_error", "reason", String.valueOf(e.getMessage()));
        }
    }

    Map<String, String> capture(LiveWeatherService.LiveForecast fc) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        if (ledger == null) {
            out.put("status", "disabled_no_ledger");
            return out;
        }
        if (!"Open-Meteo".equals(fc.provider()) || !fc.model().contains("ecmwf_ifs")) {
            out.put("status", "skipped_not_ifs");
            return out;
        }
        LocalDate issue = fc.issueDate();
        String issueId = "field:" + issue;
        for (String block : RealForecastService.BLOCKS) {
            LiveWeatherService.BlockForecast b = fc.blocks().get(block);
            if (b == null) {
                out.put(block, "skipped_no_block_data");
                continue;
            }
            out.put(block, ledger.appendIfAbsent(issueId, block,
                    buildRecord(issue, fc.retrievedAt().toString(), block, b,
                            fc.horizonDays())));
        }
        return out;
    }

    private Map<String, Object> buildRecord(LocalDate issue, String retrievedAt,
            String block, LiveWeatherService.BlockForecast b, int horizonDays) {
        Map<LocalDate, LiveWeatherService.BlockDaily> byDate = new TreeMap<>();
        for (LiveWeatherService.BlockDaily d : b.days()) byDate.put(d.date(), d);
        List<String> window = new ArrayList<>();
        List<Double> d1d3 = new ArrayList<>();
        for (int k = 1; k <= 3; k++) {
            LocalDate d = issue.plusDays(k);
            window.add(d.toString());
            LiveWeatherService.BlockDaily day = byDate.get(d);
            d1d3.add(day == null ? null : day.rainfallMm());
        }
        FieldWorkRule.Assessment a = FieldWorkRule.assess(d1d3);
        Double p95 = (heavyThresholds == null) ? null : heavyThresholds.dailyP95(block);
        Boolean heavy = CompositeRiskService.heavyEvidence(d1d3, p95);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("schema_version", SCHEMA_VERSION);
        r.put("record_kind", "forecast_issue");
        r.put("issue_id", "field:" + issue);
        r.put("issue_date", issue.toString());
        r.put("forecast_retrieved_at", retrievedAt);
        r.put("block", block);
        r.put("provider", "Open-Meteo");
        r.put("model", "ECMWF IFS (ecmwf_ifs)");
        r.put("model_selector", "ecmwf_ifs");
        r.put("horizon_days", horizonDays);
        r.put("spatial_method", b.spatialMethod());
        r.put("truth_window", window);
        r.put("forecast_d1_d3_mm", d1d3);
        r.put("forecast_complete", a.category() != FieldWorkRule.Category.UNAVAILABLE);
        int wet = 0;
        boolean wetKnown = a.wetDays() != null;
        if (wetKnown) wet = a.wetDays();
        r.put("wet_days", wetKnown ? wet : null);
        r.put("predicted_high", a.category() == FieldWorkRule.Category.UNAVAILABLE ? null
                : (a.category() == FieldWorkRule.Category.HIGH ? 1 : 0));
        r.put("heavy_present", heavy);
        r.put("heavy_threshold_mm", p95);
        r.put("method_version", FieldWorkRule.METHOD_VERSION);
        r.put("reason_codes", a.reasonCodes());
        r.put("truth_status", "pending");
        r.put("observed_wet_days", null);
        r.put("observed_disrupted", null);
        r.put("observed_window_mm", null);
        r.put("chirps_vintage", null);
        r.put("truth_attached_at", null);
        r.put("validation_result", null);
        return r;
    }

    static ShadowLedger resolveLedger(String shadowPath) {
        if (shadowPath != null && !shadowPath.isBlank()) {
            return new ShadowLedger(Paths.get(shadowPath.trim()), SCHEMA_VERSION);
        }
        return new ShadowLedger(
                ShadowLedger.defaultPath("field_shadow.jsonl"), SCHEMA_VERSION);
    }
}
