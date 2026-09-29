package com.saarthi.shadow;

import java.util.List;

/**
 * Frozen Phase 4.0 agricultural dry-spell rule.
 *
 * <p>Python source of truth: {@code src/risk/backtest.py} (rule fixed a priori,
 * zero fitted parameters). This class is a behaviour-identical port for the
 * live IFS shadow path so the shadow evaluator calls the SAME rule.
 *
 * <pre>
 *   WARN = (dry_run_through_D-3 &gt;= 3 and f_dry_D+1..D+7 &gt;= 5) or (f_dry &gt;= 6)
 * </pre>
 *
 * <p>Conventions (identical to Python {@code src/risk/common.py}):
 * <ul>
 *   <li>Dry day: rainfall strictly &lt; 1.0 mm/day (1.00 mm is NOT dry).</li>
 *   <li>Missing ({@code null}) is never dry and never zero-filled: any missing
 *       value makes the count/run unavailable ({@code null}), it does not
 *       contribute zero.</li>
 *   <li>Antecedent indices end at D-3 ({@code FEAT_LAG}); the label window is
 *       exactly D+1..D+7.</li>
 * </ul>
 *
 * <p><b>FROZEN — do not tune.</b> Any change here must be accompanied by a
 * proof of identical behaviour to {@code src/risk/backtest.py}.
 */
public final class DrySpellRule {

    /** Shadow ledger schema + rule version stamp. Must match Python {@code src/shadow/__init__.py}. */
    public static final String RULE_VERSION = "phase4.0-frozen";

    /** Dry-day threshold in mm/day (1.00 mm is NOT dry). */
    public static final double DRY_MM = 1.0;

    /** Minimum trailing dry-run length through D-3 for the persistence arm. */
    public static final int DRY_RUN_MIN = 3;

    /** Minimum forecast dry days (with dry_run &gt;= 3) to warn. */
    public static final int F_DRY_WARN = 5;

    /** Forecast dry days that warn on their own. */
    public static final int F_DRY_STRONG = 6;

    /** Antecedent windows end at D-FEAT_LAG (Checkpoint 0 convention). */
    public static final int FEAT_LAG = 3;

    /** Antecedent lookback cap in days (D-30..D-3, matches backtest {@code a[i-30:i-2]}). */
    public static final int ANTECEDENT_DAYS = 28;

    private DrySpellRule() {
    }

    /** Strictly less than {@link #DRY_MM}. {@code null} is never dry (missing != zero). */
    public static boolean isDry(Double v) {
        return v != null && v < DRY_MM;
    }

    /**
     * Dry-day count over the 7-day forecast window.
     *
     * @return count, or {@code null} when ANY value is missing (never zero-filled)
     */
    public static Integer countDry(List<Double> values) {
        if (values == null) return null;
        int n = 0;
        for (Double v : values) {
            if (v == null) return null;
            if (isDry(v)) n++;
        }
        return n;
    }

    /**
     * Length of the trailing consecutive dry-day run.
     *
     * @return run length, or {@code null} when ANY value is missing (never zero-filled)
     */
    public static Integer trailingDryRun(List<Double> values) {
        if (values == null) return null;
        int n = 0;
        for (int i = values.size() - 1; i >= 0; i--) {
            Double v = values.get(i);
            if (v == null) return null;
            if (isDry(v)) n++;
            else break;
        }
        return n;
    }

    /**
     * Frozen WARN evaluation. {@code null} inputs yield a {@code null} verdict
     * with explicit reason codes (never a fabricated label).
     */
    public static Prediction evaluate(Integer dryRunThroughDminus3, Integer fDry) {
        if (dryRunThroughDminus3 == null || fDry == null) {
            java.util.List<String> reasons = new java.util.ArrayList<>();
            if (dryRunThroughDminus3 == null) reasons.add("antecedent_unavailable");
            if (fDry == null) reasons.add("incomplete_forecast");
            return new Prediction(null, List.copyOf(reasons));
        }
        boolean warn = (dryRunThroughDminus3 >= DRY_RUN_MIN && fDry >= F_DRY_WARN)
                || (fDry >= F_DRY_STRONG);
        if (warn) {
            if (fDry >= F_DRY_STRONG) return new Prediction(Boolean.TRUE, List.of("f_dry>=6"));
            return new Prediction(Boolean.TRUE, List.of("dry_run>=3&f_dry>=5"));
        }
        return new Prediction(Boolean.FALSE, List.of("no_dryspell_signal"));
    }

    /** Frozen verdict: {@code warn} is {@code null} when the rule cannot be evaluated. */
    public record Prediction(Boolean warn, List<String> reasonCodes) {
    }
}
