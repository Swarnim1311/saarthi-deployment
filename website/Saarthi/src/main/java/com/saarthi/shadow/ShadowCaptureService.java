package com.saarthi.shadow;

import com.saarthi.service.RealForecastService;
import com.saarthi.weather.LiveWeatherService;
import com.saarthi.weather.RecentRainfallService;
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
 * Live IFS shadow capture at the Open-Meteo/ECMWF-IFS retrieval boundary.
 *
 * <p>For every fresh six-block {@link LiveWeatherService.LiveForecast}, this
 * service preserves one immutable record per block holding everything needed
 * to later answer "did this exact IFS forecast predict a D+1..D+7 dry spell?":
 * the D+1..D+7 precipitation values, the CHIRPS antecedent window ending D-3,
 * the frozen Phase 4.0 prediction, and pending-truth placeholders.
 *
 * <p>Design constraints:
 * <ul>
 *   <li>Reuses the already-retrieved production forecast — never issues a
 *       second Open-Meteo request.</li>
 *   <li>Fail-soft: {@link #tryCapture} never throws. Storage failure, missing
 *       CHIRPS, or malformed data are logged and the weather response is
 *       served normally.</li>
 *   <li>Missing rainfall stays {@code null} through every field — never
 *       zero-filled. Incomplete D+1..D+7 or unavailable antecedent yields a
 *       {@code null} prediction with explicit reason codes.</li>
 *   <li>Future CHIRPS (D+1 onward) is never used as a prediction feature:
 *       antecedent values cover D-30..D-3 only.</li>
 * </ul>
 */
@Service
public class ShadowCaptureService {

    private static final Logger log = LoggerFactory.getLogger(ShadowCaptureService.class);

    public static final String PROVIDER = "Open-Meteo";
    public static final String MODEL = "ECMWF IFS (ecmwf_ifs)";
    public static final String MODEL_SELECTOR = "ecmwf_ifs";

    private final ShadowLedger ledger;
    private final RecentRainfallService chirps;

    @Autowired
    public ShadowCaptureService(
            @Value("${saarthi.shadow.path:}") String shadowPath,
            RecentRainfallService chirps) {
        this(resolveLedger(shadowPath), chirps);
        log.info("Dry-spell shadow ledger: {}", ledger.path().toAbsolutePath());
    }

    /** Test seam: inject ledger + CHIRPS service directly (either may be {@code null}). */
    public ShadowCaptureService(ShadowLedger ledger, RecentRainfallService chirps) {
        this.ledger = ledger;
        this.chirps = chirps;
    }

    /**
     * Capture one forecast issue. Never throws: any failure is logged and
     * reported as {@code skipped_error} so weather serving is unaffected.
     */
    public Map<String, String> tryCapture(LiveWeatherService.LiveForecast fc) {
        try {
            return capture(fc);
        } catch (Exception e) {
            log.warn("Shadow capture failed (weather serving unaffected): {}", e.getMessage());
            return Map.of("status", "skipped_error", "reason", String.valueOf(e.getMessage()));
        }
    }

    Map<String, String> capture(LiveWeatherService.LiveForecast fc) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        if (ledger == null) {
            out.put("status", "disabled_no_ledger");
            return out;
        }
        if (!PROVIDER.equals(fc.provider()) || !fc.model().contains(MODEL_SELECTOR)) {
            out.put("status", "skipped_not_ifs");
            return out;
        }
        LocalDate issue = fc.issueDate();
        String issueId = "ifs:" + issue;
        for (String block : RealForecastService.BLOCKS) {
            LiveWeatherService.BlockForecast b = fc.blocks().get(block);
            if (b == null) {
                out.put(block, "skipped_no_block_data");
                continue;
            }
            Map<String, Object> record = buildRecord(issue, fc.retrievedAt().toString(),
                    block, b, fc.horizonDays());
            out.put(block, ledger.appendIfAbsent(issueId, block, record));
        }
        return out;
    }

    private Map<String, Object> buildRecord(LocalDate issue, String retrievedAt,
            String block, LiveWeatherService.BlockForecast b, int horizonDays) {
        Map<LocalDate, LiveWeatherService.BlockDaily> byDate = new TreeMap<>();
        for (LiveWeatherService.BlockDaily d : b.days()) byDate.put(d.date(), d);

        List<String> window = new ArrayList<>();
        List<Double> fcMm = new ArrayList<>();
        List<Double> fcProb = new ArrayList<>();
        for (int k = 1; k <= 7; k++) {
            LocalDate d = issue.plusDays(k);
            window.add(d.toString());
            LiveWeatherService.BlockDaily day = byDate.get(d);
            fcMm.add(day == null ? null : day.rainfallMm());
            fcProb.add(day == null ? null : day.rainProbabilityPct());
        }

        LocalDate cutoff = issue.minusDays(DrySpellRule.FEAT_LAG);
        LocalDate antStart = cutoff.minusDays(DrySpellRule.ANTECEDENT_DAYS - 1);
        List<Double> antecedent = (chirps == null)
                ? null : chirps.dailyWindow(block, antStart, cutoff);
        boolean antecedentAvailable = antecedent != null
                && antecedent.size() == DrySpellRule.ANTECEDENT_DAYS
                && !antecedent.contains(null);

        Integer fDry = DrySpellRule.countDry(fcMm);
        Integer dryRun = antecedentAvailable
                ? DrySpellRule.trailingDryRun(antecedent) : null;
        DrySpellRule.Prediction pred = DrySpellRule.evaluate(dryRun, fDry);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("schema_version", ShadowLedger.SCHEMA_VERSION);
        r.put("record_kind", "forecast_issue");
        r.put("issue_id", "ifs:" + issue);
        r.put("issue_date", issue.toString());
        r.put("forecast_retrieved_at", retrievedAt);
        r.put("block", block);
        r.put("provider", PROVIDER);
        r.put("model", MODEL);
        r.put("model_selector", MODEL_SELECTOR);
        r.put("horizon_days", horizonDays);
        r.put("spatial_method", b.spatialMethod());
        r.put("truth_window", window);
        r.put("forecast_d1_d7_mm", fcMm);
        r.put("forecast_d1_d7_prob_pct", fcProb);
        r.put("forecast_complete", fDry != null);
        r.put("chirps_antecedent_cutoff", cutoff.toString());
        r.put("chirps_antecedent_start", antStart.toString());
        r.put("chirps_antecedent_mm", antecedentAvailable ? antecedent : null);
        r.put("antecedent_available", antecedentAvailable);
        r.put("antecedent_backfilled", false);
        r.put("dry_run_through_dminus3", dryRun);
        r.put("f_dry", fDry);
        r.put("rule_version", DrySpellRule.RULE_VERSION);
        r.put("predicted_dryspell",
                pred.warn() == null ? null : (pred.warn() ? 1 : 0));
        r.put("reason_codes", pred.reasonCodes());
        r.put("truth_status", "pending");
        r.put("observed_label", null);
        r.put("observed_window_mm", null);
        r.put("chirps_vintage", null);
        r.put("truth_attached_at", null);
        r.put("validation_result", null);
        return r;
    }

    /**
     * Resolve the ledger location: explicit {@code saarthi.shadow.path}, else
     * the repo-canonical {@code data/processed/shadow/ifs_shadow.jsonl}
     * anchored at the repository root (robust to the JVM working directory;
     * see {@link ShadowLedger#defaultPath}).
     */
    static ShadowLedger resolveLedger(String shadowPath) {
        if (shadowPath != null && !shadowPath.isBlank()) {
            return new ShadowLedger(Paths.get(shadowPath.trim()));
        }
        return new ShadowLedger(ShadowLedger.defaultPath("ifs_shadow.jsonl"));
    }
}
