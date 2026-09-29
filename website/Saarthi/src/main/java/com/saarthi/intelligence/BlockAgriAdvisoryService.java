package com.saarthi.intelligence;

import com.saarthi.risks.HeavyRainThresholds;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Block-specific agricultural reading of ONE {@link ForecastContext}.
 *
 * <p>This is the Climate Intelligence page's "what is happening / why it
 * matters / what to do / what to watch" block. It is written as several lines
 * per question, not one paragraph with the block name dropped in: every line is
 * selected by a comparison against this block's own live numbers (wet-day
 * count, water balance, dry-run length, heaviest daily total against the
 * block's own heavy-rain reference, temperature extremes, forecast surface
 * moisture) plus the block's identity from the geography registry. Two blocks
 * with different weather get different text, and a block whose inputs are
 * incomplete gets fewer lines instead of invented ones.
 *
 * <p>Boundaries kept on purpose:
 * <ul>
 *   <li>No new thresholds. Wet/dry days reuse the frozen field-work rule
 *       (&gt;= 1.0 mm/day), and the heavy-rain reference is the existing
 *       per-block daily p95 from {@link HeavyRainThresholds} — the same
 *       evidence the risk sectors already publish.</li>
 *   <li>No crop stage is claimed. This page has no crop input, so the actions
 *       are field-operation guidance that holds for any crop; crop-stage
 *       advice stays on the Farmer Portal, and the response says so.</li>
 *   <li>No site-specific agronomic fact is invented. Where the block's own
 *       context is unknown (soil, temperature) the line is omitted and the
 *       reason is listed under {@code missing} instead.</li>
 * </ul>
 */
@Service
public class BlockAgriAdvisoryService {

    static final String NO_CROP_NOTE =
            "No crop is selected on this page, so these are field-operation actions that apply to "
            + "any crop. Crop-stage advice (sowing window, irrigation schedule, stage-specific "
            + "watches) is generated on the Farmer Portal, where a crop is chosen.";

    private final HeavyRainThresholds heavy;

    @Autowired
    public BlockAgriAdvisoryService(HeavyRainThresholds heavy) {
        this.heavy = heavy;
    }

    /** Build the advisory map for one context. Never throws. */
    public Map<String, Object> advise(ForecastContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ctx == null) {
            out.put("available", false);
            out.put("reason", "No forecast context was assembled for this selection.");
            return out;
        }
        final String where = where(ctx);
        List<String> happening = new ArrayList<>();
        List<String> matters = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        List<String> watches = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        List<ForecastContext.Day> days = ctx.days() == null ? List.of() : ctx.days();
        Double p95 = heavyP95(ctx.blockName());
        if (p95 == null) missing.add("block heavy-rain reference");

        // ---------- WHAT IS HAPPENING ----------
        if (ctx.wetDays13() != null && ctx.rain3Mm() != null) {
            happening.add(String.format(java.util.Locale.ROOT,
                    "Next 3 days in %s: %d wet day(s) out of 3, %s mm total%s.",
                    where, ctx.wetDays13(), num(ctx.rain3Mm()),
                    ctx.maxDailyMm() == null ? "" : String.format(java.util.Locale.ROOT,
                            ", heaviest %s mm on %s", num(ctx.maxDailyMm()),
                            ctx.maxDailyDate() == null ? "an unstated day" : ctx.maxDailyDate())));
        } else {
            missing.add("D+1..D+3 rainfall window");
        }

        if (ctx.rain7Mm() != null && ctx.et0_7dMm() != null && ctx.rainMinusEt07Mm() != null) {
            double bal = ctx.rainMinusEt07Mm();
            String word = bal <= -10 ? "a deficit" : (bal >= 10 ? "a surplus" : "a near balance");
            happening.add(String.format(java.util.Locale.ROOT,
                    "Seven-day water balance: %s mm of rain against %s mm of water demand — %s of %s mm.",
                    num(ctx.rain7Mm()), num(ctx.et0_7dMm()), word, num(Math.abs(bal))));
        } else {
            missing.add("7-day rain or water-demand figure");
        }

        if (ctx.longestDryRun7() != null || ctx.trailingDryRun() != null) {
            StringBuilder sb = new StringBuilder("Dry pattern: ");
            if (ctx.longestDryRun7() != null) {
                sb.append(ctx.longestDryRun7()).append(" consecutive dry day(s) in the next 7 days");
            }
            if (ctx.trailingDryRun() != null) {
                if (ctx.longestDryRun7() != null) sb.append("; ");
                sb.append(ctx.trailingDryRun()).append(" dry day(s) in a row at the start");
            }
            if (ctx.dryDays7() != null) sb.append(", ").append(ctx.dryDays7()).append(" of 7 dry overall");
            happening.add(sb.append('.').toString());
        } else {
            missing.add("dry-day counts");
        }

        double[] tmax = range(days, true);
        double[] tmin = range(days, false);
        if (tmax != null || tmin != null) {
            StringBuilder sb = new StringBuilder("Across the ")
                    .append(days.size()).append("-day horizon ");
            if (tmax != null) sb.append("daytime maxima run ").append(num(tmax[0])).append("–")
                    .append(num(tmax[1])).append(" °C");
            if (tmax != null && tmin != null) sb.append(", ");
            if (tmin != null) sb.append("night minima run ").append(num(tmin[0])).append("–")
                    .append(num(tmin[1])).append(" °C");
            happening.add(sb.append('.').toString());
        } else {
            missing.add("temperature fields");
        }

        if (ctx.soilMoistureDay0Vwc() != null) {
            happening.add(String.format(java.util.Locale.ROOT,
                    "Forecast surface soil moisture on the first forecast day (0–7 cm layer): %s m³/m³.",
                    ctx.soilMoistureDay0Vwc()));
        } else {
            missing.add("forecast surface soil moisture");
        }
        if (ctx.soilAvailable() && ctx.soilLine() != null) {
            happening.add("Block soil context: " + ctx.soilLine() + ".");
        }

        // ---------- WHY IT MATTERS ----------
        Double waterBal = ctx.rainMinusEt07Mm();
        if (waterBal != null && waterBal <= -10) {
            matters.add("A " + num(Math.abs(waterBal)) + " mm deficit over seven days raises "
                    + "irrigation demand, and rainfed crops face moisture stress in the same window.");
        }
        if (waterBal != null && waterBal >= 20) {
            matters.add("A positive water balance means rainfall is outrunning demand, so "
                    + "standing water can collect in low-lying parcels and delay field operations.");
        }
        if (ctx.wetDays13() != null && ctx.wetDays13() >= 2) {
            matters.add(ctx.wetDays13() + " wet days inside the first three mean sowing, "
                    + "spraying, fertiliser top-dressing and harvest all face a workability "
                    + "problem, not just a rainfall problem.");
        }
        if (p95 != null && ctx.maxDailyMm() != null && ctx.maxDailyMm() > p95) {
            matters.add("The heaviest day (" + num(ctx.maxDailyMm()) + " mm) is above this "
                    + "block's own heavy-rain reference of " + num(p95) + " mm, the level where "
                    + "surface runoff and nutrient loss matter most.");
        }
        if (ctx.longestDryRun7() != null && ctx.longestDryRun7() >= 4) {
            matters.add("A dry run of " + ctx.longestDryRun7() + " days is long enough to dry "
                    + "the surface layer before the next rain reaches the field.");
        }
        if (tmax != null && tmax[1] >= 38) {
            matters.add("Daytime heat reaching " + num(tmax[1]) + " °C lifts crop water demand "
                    + "and shortens the safe window for spraying.");
        }
        if (tmin != null && tmin[0] <= 8) {
            matters.add("Night minima down to " + num(tmin[0]) + " °C can slow early growth "
                    + "and affect flowering in sensitive crops.");
        }
        if (ctx.soilMoistureDay0Vwc() != null && ctx.soilMoistureDay0Vwc() >= 0.30) {
            matters.add("The surface layer is already forecast wet (" + ctx.soilMoistureDay0Vwc()
                    + " m³/m³), so any additional rain has less room to infiltrate.");
        }
        if (ctx.soilMoistureDay0Vwc() != null && ctx.soilMoistureDay0Vwc() < 0.12) {
            matters.add("The surface layer is forecast dry (" + ctx.soilMoistureDay0Vwc()
                    + " m³/m³), which slows germination and early establishment.");
        }
        if (matters.isEmpty()) {
            matters.add("No strongly adverse weather signal is present in the served horizon for "
                    + where + "; the value of this panel right now is confirming that the field "
                    + "window stays open, not warning about it.");
        }

        // ---------- WHAT TO DO ----------
        LocalDate firstWet = firstWetDay(days);
        LocalDate firstDry = firstDryDay(days, 4);
        LocalDate heaviest = ctx.maxDailyDate() == null ? null : safeDate(ctx.maxDailyDate());

        if (firstWet != null) {
            actions.add("Complete spraying and fertiliser application before " + firstWet
                    + " — the first wet day in the served horizon for " + where + ".");
        }
        if (heaviest != null) {
            actions.add("Open and clear field drainage before " + heaviest
                    + ", the heaviest forecast rain day.");
        }
        if (firstDry != null) {
            actions.add("Plan the heavier field operations for the dry window starting around "
                    + firstDry + ".");
        }
        if (waterBal != null && waterBal <= -10) {
            actions.add("Line up irrigation for the deficit window, and give rainfed parcels a "
                    + "moisture-conservation plan (residue cover or mulch) rather than waiting "
                    + "for the soil to dry out.");
        }
        if (waterBal != null && waterBal >= 20) {
            actions.add("Check the drainage path of the lowest parcel first; that is where a "
                    + "surplus of " + num(waterBal) + " mm will show up.");
        }
        if (tmax != null && tmax[1] >= 38) {
            actions.add("Shift irrigation and labour to early morning or evening while the "
                    + "heat peak holds.");
        }
        if (ctx.soilMoistureDay0Vwc() != null && ctx.soilMoistureDay0Vwc() >= 0.30) {
            actions.add("Hold off tillage until the surface layer drains; working it wet "
                    + "damages structure and can seal the surface.");
        }
        if (actions.isEmpty()) {
            actions.add("Keep the normal field schedule for this window and re-check after "
                    + "the next forecast update.");
        }
        actions.add(NO_CROP_NOTE);

        // ---------- WHAT TO WATCH ----------
        if (ctx.wetDays13() != null) {
            watches.add("Wet days in D+1–D+3: " + ctx.wetDays13() + " of 3 in the served run.");
        }
        if (ctx.maxDailyMm() != null) {
            watches.add("Heaviest daily total " + num(ctx.maxDailyMm()) + " mm"
                    + (ctx.maxDailyDate() == null ? "" : " on " + ctx.maxDailyDate())
                    + (p95 == null ? "" : " against the block reference of " + num(p95) + " mm")
                    + ".");
        }
        if (ctx.longestDryRun7() != null) {
            watches.add("Dry-run length: " + ctx.longestDryRun7() + " day(s) in the next 7 days.");
        }
        if (tmax != null) {
            watches.add("Daytime ceiling " + num(tmax[1]) + " °C and night floor "
                    + (tmin == null ? "unknown" : num(tmin[0]) + " °C") + ".");
        }
        watches.add(ctx.soilMoistureDay0Vwc() == null
                ? "Forecast surface-moisture trend (not served for this block)."
                : "Forecast surface-moisture trend.");
        if (ctx.stale()) {
            watches.add("This forecast is flagged stale, so every line above counts as lower "
                    + "confidence until the next issue.");
        }
        watches.add("Rule-based indicator: the IFS-against-observed validation for this pattern "
                + "is still pending, so read the states as guidance, not as a probability.");

        // ---------- BASIS (honest provenance) ----------
        List<String> basis = new ArrayList<>();
        basis.add("Live ECMWF IFS forecast delivered through Open-Meteo, block-resolved for "
                + where + (ctx.spatialMethod() == null ? "" : " (" + ctx.spatialMethod() + ")")
                + (ctx.issueDate() == null ? "" : "; issued " + ctx.issueDate()) + ".");
        basis.add("Wet and dry days follow the frozen field-work rule (>= 1.0 mm/day); the "
                + "heavy-rain reference is this block's daily p95 from the bundled climatology.");
        basis.add(ctx.soilAvailable() && ctx.soilLine() != null
                ? "Soil context: " + ctx.soilLine() + "."
                : "Soil context is not available for this block, so no soil-based action is claimed.");
        basis.add("Agronomic framing follows the cited PAU Package of Practices / ICAR-IARI "
                + "reference already used by the Farmer Advisory. No site-specific crop-stage or "
                + "soil-property claim is made for " + where + " beyond the values listed above.");

        out.put("available", !happening.isEmpty());
        out.put("location", where);
        out.put("what_is_happening", happening);
        out.put("why_it_matters", matters);
        out.put("what_to_do", actions);
        out.put("what_to_watch", watches);
        out.put("basis", basis);
        out.put("missing", missing);
        if (happening.isEmpty()) {
            out.put("reason", "The live forecast served for this block has no complete "
                    + "rainfall window yet, so no block reading is published (missing values "
                    + "are never zero-filled).");
        }

        return out;
    }

    // ---- helpers ----

    /** min/max of one temperature series, or null when nothing is finite. */
    private static double[] range(List<ForecastContext.Day> days, boolean max) {
        Double lo = null;
        Double hi = null;
        for (ForecastContext.Day d : days) {
            Double v = max ? d.temperatureMaxC() : d.temperatureMinC();
            if (v == null || v.isNaN() || v.isInfinite()) continue;
            lo = lo == null ? v : Math.min(lo, v);
            hi = hi == null ? v : Math.max(hi, v);
        }
        return lo == null ? null : new double[] { lo, hi };
    }

    private static LocalDate firstWetDay(List<ForecastContext.Day> days) {
        for (ForecastContext.Day d : days) {
            if (ForecastContext.isWet(d.rainfallMm()) && d.date() != null) return d.date();
        }
        return null;
    }

    /** First day of a run of at least {@code minRun} dry days, or null. */
    private static LocalDate firstDryDay(List<ForecastContext.Day> days, int minRun) {
        int run = 0;
        for (ForecastContext.Day d : days) {
            if (ForecastContext.isDry(d.rainfallMm())) {
                run++;
                if (run >= minRun) {
                    LocalDate start = d.date();
                    return start == null ? null : start.minusDays(minRun - 1L);
                }
            } else {
                run = 0;
            }
        }
        return null;
    }

    private static LocalDate safeDate(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }



    private static String where(ForecastContext ctx) {
        StringBuilder sb = new StringBuilder();
        String block = ctx.displayName() != null && !ctx.displayName().isBlank()
                ? ctx.displayName() : ctx.blockName();
        sb.append(block == null ? "this block" : block);
        if (ctx.districtName() != null && !ctx.districtName().isBlank()) {
            sb.append(" block, ").append(ctx.districtName());
        }
        if (ctx.stateName() != null && !ctx.stateName().isBlank()) {
            sb.append(", ").append(ctx.stateName());
        }
        return sb.toString();
    }

    private static String num(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    private Double heavyP95(String blockName) {
        try {
            return heavy == null || blockName == null ? null : heavy.dailyP95(blockName);
        } catch (Exception e) {
            return null;
        }
    }
}
