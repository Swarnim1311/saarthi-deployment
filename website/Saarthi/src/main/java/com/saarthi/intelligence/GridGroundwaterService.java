package com.saarthi.intelligence;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BUILD 1 irrigation-pressure indicator (additive).
 *
 * <p>Uses dry-run duration, 7-day rainfall vs 7-day ET0 deficit, and forecast
 * surface soil moisture from the shared {@link ForecastContext}. This is NOT
 * an electricity-load forecast and NOT measured groundwater data — every
 * response carries the explicit assumption.
 */
@Service
public class GridGroundwaterService {

    final SectorThresholds thresholds;

    public GridGroundwaterService(SectorThresholds thresholds) {
        this.thresholds = thresholds;
    }

    /** Irrigation/grid-pressure indicator from one shared context. */
    public SectorResult assess(ForecastContext ctx) {
        List<String> assume = List.of(SectorRiskService.ASSUME_WEATHER,
                SectorRiskService.ASSUME_GRID);
        if (ctx.dryDays7() == null || ctx.rain7Mm() == null
                || ctx.et0_7dMm() == null || ctx.rainMinusEt07Mm() == null) {
            Map<String, Object> evU = new LinkedHashMap<>();
            evU.put("dry_days_d1_d7", ctx.dryDays7());
            evU.put("rain_7d_mm", ctx.rain7Mm());
            evU.put("et0_7d_mm", ctx.et0_7dMm());
            List<String> r = new ArrayList<>();
            r.add("Dryness window incomplete (missing days are never zero-filled).");
            return new SectorResult("energy_groundwater", "UNAVAILABLE", r, evU,
                    List.of("Retry when the live forecast is complete."),
                    assume, thresholds.validationNote, false,
                    "7-day rainfall/ET0 window incomplete.");
        }
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("dry_days_d1_d7", ctx.dryDays7());
        ev.put("longest_dry_run_d1_d7", ctx.longestDryRun7());
        ev.put("rain_7d_mm", ctx.rain7Mm());
        ev.put("et0_7d_mm", ctx.et0_7dMm());
        ev.put("rain_minus_et0_7d_mm", ctx.rainMinusEt07Mm());
        ev.put("soil_moisture_day0_vwc", ctx.soilMoistureDay0Vwc());
        boolean drySoil = ctx.soilMoistureDay0Vwc() != null
                && ctx.soilMoistureDay0Vwc() < thresholds.gridDrySoil;
        ev.put("dry_surface_soil", drySoil);
        List<String> reasons = new ArrayList<>();
        List<String> actions = new ArrayList<>();
        String state;
        if (ctx.dryDays7() >= thresholds.gridHighDry
                || ctx.rainMinusEt07Mm() <= thresholds.gridHighDef) {
            state = "HIGH";
            reasons.add("Prolonged dry conditions with low rainfall relative to demand.");
            reasons.add("Irrigation demand may increase; pumping pressure likely higher.");
            actions.add("Plan irrigation scheduling; prioritise critical crop stages.");
            actions.add("Monitor surface soil moisture before each irrigation round.");
        } else if (ctx.dryDays7() >= thresholds.gridModDry
                || ctx.rainMinusEt07Mm() <= thresholds.gridModDef || drySoil) {
            state = "MODERATE";
            reasons.add("Developing dryness: rainfall is below atmospheric demand.");
            actions.add("Prepare irrigation; watch soil-moisture trends.");
        } else {
            state = "LOW";
            reasons.add("No strong irrigation-pressure signal: rainfall roughly meets demand.");
            actions.add("Normal irrigation planning; recheck the daily outlook.");
        }
        return SectorResult.of("energy_groundwater", state, reasons, ev, actions,
                assume, thresholds.validationNote);
    }
}
