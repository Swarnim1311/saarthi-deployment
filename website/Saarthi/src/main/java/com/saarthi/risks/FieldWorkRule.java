package com.saarthi.risks;

import java.util.List;

/**
 * Frozen Phase 4.1 FIELD_HIGH rule port (deterministic, no ML, no tuning).
 *
 * <p>Python source of truth: {@code src/risk/field_work.py}
 * ({@code classify_3d}, method {@code field-v1}). The validated binary
 * condition is reproduced exactly:
 *
 * <pre>
 *   wet day  iff precipitation &gt;= 1.0 mm/day (exact 1.00 mm IS wet)
 *   HIGH     iff &gt;= 2 wet days in forecast D+1..D+3
 * </pre>
 *
 * <p>Python's three-tier MODERATE (exactly 1 wet day) collapses to LOW here:
 * the historically validated claim is the HIGH condition only
 * (GEFS: precision 0.721, recall 0.809), so the production signal is binary.
 * The 1-wet-day evidence is preserved in the reason code
 * ({@code FIELD_WET_DAY_1OF3}), never discarded.
 *
 * <p>Missing ({@code null}) is never zero-filled: any missing day in D+1..D+3
 * yields {@code UNAVAILABLE} — NO DATA != NO RISK, so incomplete data is
 * never represented as LOW.
 *
 * <p><b>FROZEN — do not tune.</b> Parity with Python is locked by
 * {@code FieldWorkRuleTest} (including the exact vectors from the Phase 4.2
 * checkpoint: [0,0,0], [1,0,0], [1,1,0], [1.0,1.0,0], [0.99,1.0,0],
 * [10,20,0], nulls).
 */
public final class FieldWorkRule {

    /** Production method version (implements Python {@code field-v1} semantics). */
    public static final String METHOD_VERSION = "field_work_v1";

    /** Wet-day threshold in mm/day (exact 1.00 mm IS wet). */
    public static final double WET_MM = 1.0;

    /** Wet days in D+1..D+3 required for HIGH. */
    public static final int HIGH_MIN_WET_DAYS = 2;

    private FieldWorkRule() {
    }

    public enum Category {
        HIGH, LOW, UNAVAILABLE
    }

    /** Wet iff rainfall &gt;= {@link #WET_MM}. {@code null} is never wet. */
    public static boolean isWet(Double v) {
        return v != null && v >= WET_MM;
    }

    /**
     * Assess exactly three forecast values (D+1, D+2, D+3).
     *
     * @return assessment; {@code UNAVAILABLE} when the input is not exactly
     *         three present values (wrong size or any {@code null})
     */
    public static Assessment assess(List<Double> d1d3) {
        if (d1d3 == null || d1d3.size() != 3 || hasNull(d1d3)) {
            return new Assessment(Category.UNAVAILABLE, null, null, null,
                    List.of("FIELD_UNAVAILABLE"));
        }
        int wet = 0;
        double max = Double.NEGATIVE_INFINITY;
        for (Double v : d1d3) {
            if (isWet(v)) wet++;
            if (v > max) max = v;
        }
        if (wet >= HIGH_MIN_WET_DAYS) {
            return new Assessment(Category.HIGH, wet, max, List.copyOf(d1d3),
                    List.of("FIELD_WET_DAYS_2OF3"));
        }
        if (wet == 1) {
            return new Assessment(Category.LOW, wet, max, List.copyOf(d1d3),
                    List.of("FIELD_WET_DAY_1OF3"));
        }
        return new Assessment(Category.LOW, wet, max, List.copyOf(d1d3),
                List.of("FIELD_NO_WET_DAYS"));
    }

    /** Null-safe missing check ({@code List.of(...).contains(null)} throws NPE). */
    private static boolean hasNull(List<Double> vals) {
        for (Double v : vals) {
            if (v == null) return true;
        }
        return false;
    }

    /**
     * Frozen verdict. {@code wetDays}/{@code maxMm}/{@code dailyMm} are
     * {@code null} exactly when {@code category} is {@code UNAVAILABLE}.
     */
    public record Assessment(Category category, Integer wetDays, Double maxMm,
            List<Double> dailyMm, List<String> reasonCodes) {
    }
}
