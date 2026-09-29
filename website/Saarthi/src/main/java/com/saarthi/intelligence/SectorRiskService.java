package com.saarthi.intelligence;

import com.saarthi.risks.FieldWorkRule;
import com.saarthi.risks.HeavyRainThresholds;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BUILD 1 cross-sector rule engine (additive).
 *
 * <p>Consumes ONE {@link ForecastContext} per call — never a weather provider.
 * Frozen rules are reused, never modified: wet-day definition
 * ({@code FieldWorkRule.WET_MM}) and heavy-rain display evidence
 * ({@code HeavyRainThresholds.dailyP95}). New logistics/warehouse/energy
 * cutoffs live in {@code intelligence/sector-thresholds.json} and are
 * labelled rule-based (IFS validation pending).
 */
@Service
public class SectorRiskService {

    static final String ASSUME_WEATHER =
            "Weather risk only — actual road, warehouse, grid and groundwater status are not measured.";
    static final String ASSUME_ROAD =
            "No road or traffic data is used; weather risk must not be read as a road closure.";
    static final String ASSUME_WAREHOUSE =
            "No warehouse damage is measured; guidance covers moisture-exposure precautions only.";
    static final String ASSUME_GRID =
            "Irrigation pressure is inferred from weather and soil-moisture conditions; "
            + "grid load and groundwater levels are not directly measured.";

    final SectorThresholds thresholds;
    final HeavyRainThresholds heavy;

    @Autowired
    public SectorRiskService(SectorThresholds thresholds, HeavyRainThresholds heavy) {
        this.thresholds = thresholds;
        this.heavy = heavy;
    }

    /** All four sectors from one shared context. */
    public Map<String, SectorResult> assessAll(ForecastContext ctx,
            SectorResult energy) {
        Map<String, SectorResult> out = new LinkedHashMap<>();
        out.put("agriculture", agriculture(ctx));
        out.put("logistics", logistics(ctx));
        out.put("warehouse", warehouse(ctx));
        out.put("energy_groundwater", energy);
        return out;
    }

    // ---- Agriculture (interprets frozen FIELD_HIGH concepts) ----

    SectorResult agriculture(ForecastContext ctx) {
        List<String> assume = List.of(ASSUME_WEATHER,
                "Field-work disruption uses the frozen wet-day rule (>=1.0 mm/day).");
        if (ctx.wetDays13() == null) {
            return SectorResult.unavailable("agriculture",
                    "D+1..D+3 rainfall incomplete (missing days are never zero-filled).",
                    assume, thresholds.validationNote);
        }
        List<Double> d3 = rainWindow(ctx, 3);
        FieldWorkRule.Assessment a = FieldWorkRule.assess(d3);
        if (a.category() == FieldWorkRule.Category.UNAVAILABLE) {
            return SectorResult.unavailable("agriculture",
                    "D+1..D+3 rainfall incomplete (missing days are never zero-filled).",
                    assume, thresholds.validationNote);
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("wet_days_d1_d3", a.wetDays());
        ev.put("max_daily_mm_d1_d3", a.maxMm());
        ev.put("daily_mm_d1_d3", a.dailyMm());
        Double p95 = heavyP95(ctx.blockName());
        ev.put("heavy_rain_p95_mm", p95);
        boolean heavyDay = p95 != null && a.maxMm() != null && a.maxMm() > p95;
        ev.put("heavy_day_present", heavyDay);
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        String state;
        if (a.wetDays() >= thresholds.agriHighWet || heavyDay) {
            state = "HIGH";
            reasons.add("Rain is expected on " + a.wetDays() + " of the next 3 days.");
            if (heavyDay) reasons.add("A heavy-rain day above the block p95 is expected.");
            reasons.add("Wet conditions may delay field operations.");
            actions.add("Defer soil-disturbing field work on wet days.");
            actions.add("Protect harvested produce from rain exposure.");
        } else if (a.wetDays() >= thresholds.agriModWet) {
            state = "MODERATE";
            reasons.add("Rain is expected on " + a.wetDays() + " of the next 3 days.");
            reasons.add("Some field operations may be slowed by wet soil.");
            actions.add("Plan field work around the wet day; keep drainage clear.");
        } else {
            state = "LOW";
            reasons.add("No major rainfall-related field-work disruption detected in D+1..D+3.");
            actions.add("Proceed with planned field operations; recheck the daily outlook.");
        }
        if (ctx.stale()) {
            reasons.add("The underlying forecast is stale — treat with extra caution.");
        }
        return SectorResult.of("agriculture", state, reasons, ev, actions, assume,
                thresholds.validationNote + " FIELD_HIGH validated on GEFS only; IFS pending.");
    }

    // ---- Logistics (wet persistence + totals; never road status) ----

    SectorResult logistics(ForecastContext ctx) {
        List<String> assume = List.of(ASSUME_WEATHER, ASSUME_ROAD);
        if (ctx.wetDays13() == null || ctx.rain3Mm() == null || ctx.maxDailyMm() == null) {
            return SectorResult.unavailable("logistics",
                    "Rainfall window incomplete (missing days are never zero-filled).",
                    assume, thresholds.validationNote);
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("wet_days_d1_d3", ctx.wetDays13());
        ev.put("rain_3d_mm", ctx.rain3Mm());
        ev.put("max_daily_mm", ctx.maxDailyMm());
        ev.put("max_daily_date", ctx.maxDailyDate());
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        String state;
        boolean heavyMax = ctx.maxDailyMm() >= thresholds.logHighMax;
        if (ctx.wetDays13() >= thresholds.logHighWet
                || ctx.rain3Mm() >= thresholds.logHigh3d || heavyMax) {
            state = "HIGH";
            reasons.add("Heavy rainfall may increase transport disruption risk.");
            reasons.add(ctx.wetDays13() + " of the next 3 days are wet; 3-day total "
                    + ctx.rain3Mm() + " mm.");
            actions.add("Allow additional transit time for time-sensitive shipments.");
            actions.add("Review vulnerable routes and verify local road conditions.");
            actions.add("Prioritise perishable consignments earlier in dry windows.");
        } else if (ctx.wetDays13() >= thresholds.logModWet
                || ctx.rain3Mm() >= thresholds.logMod3d) {
            state = "MODERATE";
            reasons.add("Some wet conditions may slow transport in D+1..D+3.");
            actions.add("Keep buffer time; confirm conditions with local transporters.");
        } else {
            state = "LOW";
            reasons.add("No major weather-driven transport disruption signal in D+1..D+3.");
            actions.add("Normal dispatch planning; monitor the daily outlook.");
        }
        return SectorResult.of("logistics", state, reasons, ev, actions, assume,
                thresholds.validationNote);
    }

    // ---- Warehouse (7-day wet persistence; never damage claims) ----

    SectorResult warehouse(ForecastContext ctx) {
        List<String> assume = List.of(ASSUME_WEATHER, ASSUME_WAREHOUSE);
        if (ctx.wetDays17() == null || ctx.rain7Mm() == null) {
            return SectorResult.unavailable("warehouse",
                    "7-day rainfall window incomplete (missing days are never zero-filled).",
                    assume, thresholds.validationNote);
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("wet_days_d1_d7", ctx.wetDays17());
        ev.put("rain_7d_mm", ctx.rain7Mm());
        ev.put("soil_moisture_day0_vwc", ctx.soilMoistureDay0Vwc());
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        String state;
        if (ctx.wetDays17() >= thresholds.whHighWet7 || ctx.rain7Mm() >= thresholds.whHigh7d) {
            state = "HIGH";
            reasons.add("Prolonged wet conditions increase moisture-exposure risk.");
            actions.add("Inspect drainage and roof seepage points.");
            actions.add("Improve ventilation; monitor moisture-sensitive inventory.");
        } else if (ctx.wetDays17() >= thresholds.whModWet7
                || ctx.rain7Mm() >= thresholds.whMod7d) {
            state = "MODERATE";
            reasons.add("Some moisture-exposure conditions are present this week.");
            actions.add("Check drainage and cover goods stored near openings.");
        } else {
            state = "LOW";
            reasons.add("No major weather-driven moisture-exposure signal this week.");
            actions.add("Routine storage checks are sufficient.");
        }
        return SectorResult.of("warehouse", state, reasons, ev, actions, assume,
                thresholds.validationNote);
    }

    // ---- helpers ----

    private List<Double> rainWindow(ForecastContext ctx, int n) {
        List<Double> out = new ArrayList<>();
        List<ForecastContext.Day> days = ctx.days();
        for (int i = 0; i < n && i < days.size(); i++) out.add(days.get(i).rainfallMm());
        return out;
    }

    private Double heavyP95(String blockName) {
        try {
            return heavy == null ? null : heavy.dailyP95(blockName);
        } catch (Exception e) {
            return null;
        }
    }
}
