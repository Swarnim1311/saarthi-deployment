package com.saarthi.risks;

import com.saarthi.risks.FieldWorkService.InvalidWindowException;
import com.saarthi.risks.FieldWorkService.RiskUnavailableException;
import com.saarthi.service.RealForecastService;
import com.saarthi.service.RealForecastService.BlockNotFoundException;
import com.saarthi.shadow.DrySpellRule;
import com.saarthi.weather.LiveWeatherService;
import com.saarthi.weather.RecentRainfallService;
import com.saarthi.weather.WeatherProviderException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Composite agricultural risk (composite_v1): deterministic rule-based
 * priority, NOT a numerical score.
 *
 * <p>Precedence, first match wins (frozen):
 * <ol>
 *   <li>D+1..D+3 incomplete → UNAVAILABLE (NO DATA != NO RISK).</li>
 *   <li>FIELD_HIGH → HIGH, primary FIELD_WORK_DISRUPTION.</li>
 *   <li>Dry-spell watch ACTIVE → MODERATE, primary DRY_SPELL_WATCH
 *       (provisional, pending IFS validation — NEVER drives HIGH).</li>
 *   <li>Forecast stale → MODERATE, reason STALE_FORECAST.</li>
 *   <li>Otherwise → LOW.</li>
 * </ol>
 *
 * <p>Component sources (all frozen, none tuned here): FIELD_HIGH via
 * {@link FieldWorkRule} (field_work_v1); dry watch via the SAME
 * {@link DrySpellRule} (phase4.0-frozen) used by the shadow ledger;
 * heavy-rain display evidence via train-frozen p95; context (recent CHIRPS,
 * climatology, soil) is display-only and can never affect severity,
 * confidence, or overall state.
 *
 * <p>Consumes the already-retrieved {@link LiveWeatherService.LiveForecast}
 * — never a second upstream request. Confidence is structural: MODERATE
 * normally (GEFS-validated rule, IFS transfer pending), LOW when stale,
 * unvalidated, or unavailable. Pre-registered (not yet applied) upgrade:
 * field-shadow ledger ≥30 issues with recall ≥0.60 and precision ≥0.40.
 */
@Service
public class CompositeRiskService {

    public static final String COMPOSITE_METHOD_VERSION = "composite_v1";

    static final String FIELD_VALIDATION = "GEFS_VALIDATED_IFS_PENDING";
    static final String WATCH_VALIDATION = "PROVISIONAL_IFS_PENDING";
    static final String HEAVY_VALIDATION = "DISPLAY_ONLY";
    static final String PENDING_NOTE =
            "Dry-spell watch (provisional): IFS transfer validation is pending "
            + "(live shadow needs >=30 resolved issues). Never drives HIGH.";

    private final LiveWeatherService live;
    private final RealForecastService blocks;
    private final RecentRainfallService recent;
    private final HeavyRainThresholds heavyThresholds;
    private final SoilContext soil;
    private final ClimatologyContext climatology;

    @Autowired
    public CompositeRiskService(LiveWeatherService live, RealForecastService blocks,
            RecentRainfallService recent, HeavyRainThresholds heavyThresholds,
            SoilContext soil, ClimatologyContext climatology) {
        this.live = live;
        this.blocks = blocks;
        this.recent = recent;
        this.heavyThresholds = heavyThresholds;
        this.soil = soil;
        this.climatology = climatology;
    }

    /** Test seam: collaborators may be null (context degrades gracefully). */
    CompositeRiskService(LiveWeatherService live, RealForecastService blocks,
            RecentRainfallService recent, HeavyRainThresholds heavyThresholds,
            SoilContext soil, ClimatologyContext climatology, boolean test) {
        this(live, blocks, recent, heavyThresholds, soil, climatology);
    }

    public enum Overall {
        HIGH, MODERATE, LOW, UNAVAILABLE
    }

    public enum WatchState {
        ACTIVE, QUIET, UNKNOWN
    }

    /** Dry-spell watch outcome. UNKNOWN = cannot evaluate (missing antecedent/forecast). */
    public record DryWatch(WatchState state, Integer fDry, Integer dryRun,
            List<String> reasons) {
    }

    /** Pure precedence outcome (truth-table testable, no I/O). */
    public record CompositeAssessment(Overall overall, String primaryConcern,
            List<String> reasons, FieldWorkRule.Assessment field, DryWatch watch,
            Boolean heavy, boolean stale) {
    }

    /**
     * Frozen precedence. {@code heavy} is accepted ONLY to document that
     * heavy-rain evidence never affects severity — it is intentionally unused.
     */
    static CompositeAssessment assess(FieldWorkRule.Assessment field, DryWatch watch,
            boolean stale, @SuppressWarnings("unused") Boolean heavy) {
        if (field == null || field.category() == FieldWorkRule.Category.UNAVAILABLE) {
            return new CompositeAssessment(Overall.UNAVAILABLE, "FIELD_WORK_DISRUPTION",
                    withStale(List.of("FIELD_UNAVAILABLE"), stale), field, watch, heavy, stale);
        }
        if (field.category() == FieldWorkRule.Category.HIGH) {
            return new CompositeAssessment(Overall.HIGH, "FIELD_WORK_DISRUPTION",
                    withStale(field.reasonCodes(), stale), field, watch, heavy, stale);
        }
        if (watch != null && watch.state() == WatchState.ACTIVE) {
            List<String> reasons = new ArrayList<>(watch.reasons());
            reasons.add("PENDING_IFS_VALIDATION");
            return new CompositeAssessment(Overall.MODERATE, "DRY_SPELL_WATCH",
                    withStale(reasons, stale), field, watch, heavy, stale);
        }
        if (stale) {
            List<String> reasons = new ArrayList<>(field.reasonCodes());
            reasons.add("STALE_FORECAST");
            return new CompositeAssessment(Overall.MODERATE, "FIELD_WORK_DISRUPTION",
                    reasons, field, watch, heavy, stale);
        }
        return new CompositeAssessment(Overall.LOW, "NONE",
                withStale(field.reasonCodes(), stale), field, watch, heavy, stale);
    }

    private static List<String> withStale(List<String> reasons, boolean stale) {
        if (!stale) return List.copyOf(reasons);
        List<String> out = new ArrayList<>(reasons);
        out.add("STALE_FORECAST");
        return List.copyOf(out);
    }

    /** Dry-spell watch from forecast D+1..D+7 + CHIRPS D-30..D-3 (null-safe). */
    static DryWatch evaluateWatch(List<Double> f7, List<Double> ant28) {
        if (f7 == null || f7.size() != 7 || hasNull(f7)
                || ant28 == null || ant28.size() != DrySpellRule.ANTECEDENT_DAYS
                || hasNull(ant28)) {
            return new DryWatch(WatchState.UNKNOWN, null, null, List.of("WATCH_UNKNOWN"));
        }
        Integer fDry = DrySpellRule.countDry(f7);
        Integer dryRun = DrySpellRule.trailingDryRun(ant28);
        DrySpellRule.Prediction p = DrySpellRule.evaluate(dryRun, fDry);
        if (p.warn() == null) {
            return new DryWatch(WatchState.UNKNOWN, fDry, dryRun, List.of("WATCH_UNKNOWN"));
        }
        if (p.warn()) {
            return new DryWatch(WatchState.ACTIVE, fDry, dryRun, p.reasonCodes());
        }
        return new DryWatch(WatchState.QUIET, fDry, dryRun, p.reasonCodes());
    }

    /**
     * Heavy-rain display evidence: window max strictly above the train-frozen
     * block p95. Returns null when incomputable. NEVER severity (see assess).
     */
    static Boolean heavyEvidence(List<Double> d1d3, Double p95) {
        if (p95 == null || d1d3 == null || d1d3.size() != 3 || hasNull(d1d3)) return null;
        double max = Double.NEGATIVE_INFINITY;
        for (Double v : d1d3) {
            if (v > max) max = v;
        }
        return max > p95;
    }

    /** Structural confidence: MODERATE unless stale/unvalidated/unavailable. */
    static String confidenceOf(CompositeAssessment a) {
        if (a.overall() == Overall.UNAVAILABLE) return "LOW";
        if (a.stale()) return "LOW";
        if (a.overall() == Overall.MODERATE
                && "DRY_SPELL_WATCH".equals(a.primaryConcern())) return "LOW";
        return "MODERATE";
    }

    private static boolean hasNull(List<Double> vals) {
        for (Double v : vals) {
            if (v == null) return true;
        }
        return false;
    }

    // ---- Service wiring (I/O; precedence itself stays in assess) ----

    /** Composite risk for one block (name or Bhuvan id, case-insensitive). */
    public Map<String, Object> getBlockComposite(String blockId, String window) {
        String w = FieldWorkService.normaliseWindow(window);
        String canonical = blocks.findBlock(blockId)
                .map(b -> java.util.Objects.toString(b.get("block_name"), null))
                .orElseThrow(() -> new BlockNotFoundException(blockId));
        LiveWeatherService.LiveForecast fc = currentForecast();
        LiveWeatherService.BlockForecast b = fc.blocks().get(canonical);
        if (b == null) {
            throw new RiskUnavailableException(
                    "No live data for block '" + canonical + "'");
        }
        return toCompositeMap(canonical, b, fc, w);
    }

    /** Composite risk for all six blocks (per-block fail-soft to UNAVAILABLE entries). */
    public Map<String, Object> getAllComposites(String window) {
        String w = FieldWorkService.normaliseWindow(window);
        LiveWeatherService.LiveForecast fc = currentForecast();
        List<Map<String, Object>> list = new ArrayList<>();
        for (String canonical : RealForecastService.BLOCKS) {
            LiveWeatherService.BlockForecast b = fc.blocks().get(canonical);
            if (b == null) {
                list.add(unavailableEntry(canonical, fc, w,
                        "No live data for block '" + canonical + "'"));
            } else {
                list.add(toCompositeMap(canonical, b, fc, w));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("risk", "FIELD_WORK_DISRUPTION");
        out.put("window", FieldWorkService.WINDOW_LABEL);
        out.put("method_version", FieldWorkRule.METHOD_VERSION);
        out.put("composite_method_version", COMPOSITE_METHOD_VERSION);
        out.put("method_note", "Rule-based priority composite_v1 (no score, no weights): "
                + "FIELD_HIGH -> HIGH; provisional dry-spell watch -> MODERATE; "
                + "stale -> MODERATE; else LOW; incomplete -> UNAVAILABLE.");
        out.put("confidence", fc.stale() ? "LOW" : "MODERATE");
        out.put("validation_note", FieldWorkService.VALIDATION_NOTE);
        out.put("provider", fc.provider());
        out.put("model", fc.model());
        out.put("issue_date", fc.issueDate().toString());
        out.put("retrieved_at", fc.retrievedAt().toString());
        out.put("stale", fc.stale());
        if (fc.staleWarning() != null) out.put("stale_warning", fc.staleWarning());
        out.put("blocks", list);
        return out;
    }

    private LiveWeatherService.LiveForecast currentForecast() {
        try {
            return live.getForecast(false);
        } catch (WeatherProviderException e) {
            throw new RiskUnavailableException(
                    "Live forecast unavailable, risk cannot be assessed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> toCompositeMap(String canonical,
            LiveWeatherService.BlockForecast b, LiveWeatherService.LiveForecast fc, String w) {
        LocalDate issue = fc.issueDate();
        Map<LocalDate, LiveWeatherService.BlockDaily> byDate = new TreeMap<>();
        for (LiveWeatherService.BlockDaily d : b.days()) byDate.put(d.date(), d);
        List<Double> d1d3 = take(byDate, issue, 1, 3);
        List<Double> f7 = take(byDate, issue, 1, 7);
        List<Double> ant32 = (recent == null) ? null
                : recent.dailyWindow(canonical, issue.minusDays(32), issue.minusDays(1));
        List<Double> ant28 = slice(ant32, 2, 30); // D-30..D-3 (indices 2..29 of D-32..D-1)

        FieldWorkRule.Assessment field = FieldWorkRule.assess(d1d3);
        DryWatch watch = evaluateWatch(f7, ant28);
        Double p95 = (heavyThresholds == null) ? null : heavyThresholds.dailyP95(canonical);
        Boolean heavy = heavyEvidence(d1d3, p95);
        CompositeAssessment a = assess(field, watch, fc.stale(), heavy);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("block", canonical);
        out.put("issue_date", issue.toString());
        // Legacy FIELD_WORK_DISRUPTION keys (backward compatible).
        out.put("risk", "FIELD_WORK_DISRUPTION");
        out.put("category", field.category() == null ? null : field.category().name());
        out.put("confidence", confidenceOf(a));
        out.put("window", FieldWorkService.WINDOW_LABEL);
        out.put("window_dates", dates(issue, 1, 3));
        out.put("method_version", FieldWorkRule.METHOD_VERSION);
        out.put("validation_note", FieldWorkService.VALIDATION_NOTE);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("wet_days", field.wetDays());
        evidence.put("max_precipitation_mm", field.maxMm());
        evidence.put("daily_precipitation_mm", field.dailyMm());
        out.put("evidence", evidence);
        out.put("reasons", field.reasonCodes());
        out.put("advisory", primaryAdvisory(a, issue, fc));
        // Composite keys (additive).
        out.put("overall_risk", a.overall().name());
        out.put("primary_concern", a.primaryConcern());
        out.put("composite_method_version", COMPOSITE_METHOD_VERSION);
        out.put("risks", risksList(a, p95));
        out.put("context", contextMap(canonical, issue, ant32));
        List<String> advisories = new ArrayList<>();
        advisories.add(primaryAdvisory(a, issue, fc));
        if (a.overall() == Overall.MODERATE
                && "DRY_SPELL_WATCH".equals(a.primaryConcern())) {
            advisories.add(PENDING_NOTE);
        }
        if (fc.stale()) {
            advisories.add("Forecast is stale (" + fc.retrievedAt()
                    + "); confidence capped at LOW.");
        }
        out.put("advisories", advisories);
        out.put("provider", fc.provider());
        out.put("model", fc.model());
        out.put("retrieved_at", fc.retrievedAt().toString());
        out.put("stale", fc.stale());
        if (fc.staleWarning() != null) out.put("stale_warning", fc.staleWarning());
        if (a.overall() == Overall.UNAVAILABLE) {
            out.put("unavailable_reason",
                    "Incomplete D+1..D+3 forecast for block '" + canonical
                    + "' (missing days are never zero-filled)");
        }
        return out;
    }

    private Map<String, Object> unavailableEntry(String canonical,
            LiveWeatherService.LiveForecast fc, String w, String reason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("block", canonical);
        out.put("issue_date", fc.issueDate().toString());
        out.put("risk", "FIELD_WORK_DISRUPTION");
        out.put("category", FieldWorkRule.Category.UNAVAILABLE.name());
        out.put("confidence", "LOW");
        out.put("window", FieldWorkService.WINDOW_LABEL);
        out.put("window_dates", dates(fc.issueDate(), 1, 3));
        out.put("method_version", FieldWorkRule.METHOD_VERSION);
        out.put("validation_note", FieldWorkService.VALIDATION_NOTE);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("wet_days", null);
        evidence.put("max_precipitation_mm", null);
        evidence.put("daily_precipitation_mm", null);
        out.put("evidence", evidence);
        out.put("reasons", List.of("FIELD_UNAVAILABLE"));
        out.put("advisory",
                "Agricultural risk is unavailable because the required forecast data is incomplete.");
        out.put("overall_risk", Overall.UNAVAILABLE.name());
        out.put("primary_concern", "FIELD_WORK_DISRUPTION");
        out.put("composite_method_version", COMPOSITE_METHOD_VERSION);
        out.put("risks", risksList(
                new CompositeAssessment(Overall.UNAVAILABLE, "FIELD_WORK_DISRUPTION",
                        List.of("FIELD_UNAVAILABLE"), null,
                        new DryWatch(WatchState.UNKNOWN, null, null, List.of("WATCH_UNKNOWN")),
                        null, fc.stale()),
                null));
        out.put("context", contextMap(canonical, fc.issueDate(), null));
        out.put("advisories", List.of(
                "Agricultural risk is unavailable because the required forecast data is incomplete."));
        out.put("provider", fc.provider());
        out.put("model", fc.model());
        out.put("retrieved_at", fc.retrievedAt().toString());
        out.put("stale", fc.stale());
        if (fc.staleWarning() != null) out.put("stale_warning", fc.staleWarning());
        out.put("unavailable_reason", reason);
        return out;
    }

    private List<Map<String, Object>> risksList(CompositeAssessment a, Double p95) {
        List<Map<String, Object>> risks = new ArrayList<>();
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("name", "FIELD_WORK_DISRUPTION");
        field.put("state", a.field() == null ? "UNAVAILABLE" : a.field().category().name());
        field.put("reasons", a.field() == null
                ? List.of("FIELD_UNAVAILABLE") : a.field().reasonCodes());
        field.put("validation", FIELD_VALIDATION);
        risks.add(field);
        Map<String, Object> watch = new LinkedHashMap<>();
        watch.put("name", "DRY_SPELL_WATCH");
        watch.put("state", a.watch() == null ? "UNKNOWN" : a.watch().state().name());
        List<String> wreasons = new ArrayList<>(
                a.watch() == null ? List.of("WATCH_UNKNOWN") : a.watch().reasons());
        if (a.watch() != null && a.watch().state() == WatchState.ACTIVE) {
            wreasons.add("PENDING_IFS_VALIDATION");
        }
        watch.put("reasons", wreasons);
        watch.put("validation", WATCH_VALIDATION);
        watch.put("pending_ifs_validation", true);
        if (a.watch() != null) {
            watch.put("f_dry_d1_d7", a.watch().fDry());
            watch.put("dry_run_through_dminus3", a.watch().dryRun());
        }
        risks.add(watch);
        Map<String, Object> heavy = new LinkedHashMap<>();
        heavy.put("name", "HEAVY_RAIN_EVIDENCE");
        heavy.put("state", a.heavy() == null ? "UNKNOWN" : (a.heavy() ? "PRESENT" : "ABSENT"));
        heavy.put("reasons", a.heavy() == null ? List.of("HEAVY_UNKNOWN")
                : (a.heavy() ? List.of("FIELD_HEAVY_RAIN") : List.of("FIELD_NO_HEAVY_RAIN")));
        heavy.put("validation", HEAVY_VALIDATION);
        heavy.put("evidence_only", true);
        if (p95 != null) heavy.put("threshold_mm", p95);
        risks.add(heavy);
        return risks;
    }

    private Map<String, Object> contextMap(String canonical, LocalDate issue,
            List<Double> ant32) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        Map<String, Object> recentCtx = new LinkedHashMap<>();
        if (ant32 != null && ant32.size() == 32 && !hasNull(ant32)) {
            LocalDate through = issue.minusDays(1);
            recentCtx.put("available", true);
            recentCtx.put("through", through.toString());
            recentCtx.put("d7_mm", round(sum(ant32, 25, 32)));
            recentCtx.put("d14_mm", round(sum(ant32, 18, 32)));
            recentCtx.put("d30_mm", round(sum(ant32, 2, 32)));
            recentCtx.put("source", "CHIRPS observed (local file), context only");
        } else {
            recentCtx.put("available", false);
            recentCtx.put("reason", "CHIRPS file not configured or incomplete; "
                    + "no observed rainfall fabricated");
        }
        ctx.put("recent_rainfall", recentCtx);
        Map<String, Object> climCtx = new LinkedHashMap<>();
        List<LocalDate> wdates = List.of(issue.plusDays(1), issue.plusDays(2), issue.plusDays(3));
        Double normal = (climatology == null) ? null : climatology.normalSumMm(canonical, wdates);
        if (normal != null) {
            climCtx.put("available", true);
            climCtx.put("normal_d1_d3_mm", normal);
            climCtx.put("vintage", climatology.vintage());
            climCtx.put("note", "Train-based normal for these calendar days; "
                    + "reference context, not a forecast");
        } else {
            climCtx.put("available", false);
            climCtx.put("reason", "Climatology row unavailable for these dates");
        }
        ctx.put("climatology", climCtx);
        Map<String, Object> soilCtx = new LinkedHashMap<>();
        String line = (soil == null) ? null : soil.describe(canonical);
        if (line != null) {
            soilCtx.put("available", true);
            soilCtx.put("line", line);
        } else {
            soilCtx.put("available", false);
            soilCtx.put("reason", "Soil context resource unavailable");
        }
        ctx.put("soil", soilCtx);
        return ctx;
    }

    static String primaryAdvisory(CompositeAssessment a, LocalDate issue,
            LiveWeatherService.LiveForecast fc) {
        String tail = " (Issue " + issue + ", retrieved " + fc.retrievedAt() + ".)";
        switch (a.overall()) {
            case HIGH:
                return "Field-work disruption risk is HIGH for D+1–D+3 because "
                        + a.field().wetDays()
                        + " of 3 forecast days are expected to receive at least 1 mm rain."
                        + tail;
            case MODERATE:
                if ("DRY_SPELL_WATCH".equals(a.primaryConcern())) {
                    return "Dry-spell watch (provisional): a prolonged dry period is "
                            + "indicated in D+1–D+7. IFS transfer validation is pending."
                            + tail;
                }
                return "No strong agricultural weather risk signal is currently detected, "
                        + "but the forecast is stale — treat with caution." + tail;
            case UNAVAILABLE:
                return "Agricultural risk is unavailable because the required forecast "
                        + "data is incomplete.";
            default:
                return "No strong agricultural weather risk signal is currently detected."
                        + tail;
        }
    }

    private static List<Double> take(Map<LocalDate, LiveWeatherService.BlockDaily> byDate,
            LocalDate issue, int fromLead, int toLead) {
        List<Double> out = new ArrayList<>();
        for (int k = fromLead; k <= toLead; k++) {
            LiveWeatherService.BlockDaily d = byDate.get(issue.plusDays(k));
            out.add(d == null ? null : d.rainfallMm());
        }
        return out;
    }

    private static List<String> dates(LocalDate issue, int fromLead, int toLead) {
        List<String> out = new ArrayList<>();
        for (int k = fromLead; k <= toLead; k++) out.add(issue.plusDays(k).toString());
        return out;
    }

    private static List<Double> slice(List<Double> vals, int from, int to) {
        if (vals == null || vals.size() < to) return null;
        return new ArrayList<>(vals.subList(from, to));
    }

    private static double sum(List<Double> vals, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += vals.get(i);
        return s;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
