package com.saarthi.intelligence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * BUILD 1 shared forecast context (additive intelligence layer).
 *
 * <p>Assembled ONCE per request from the already-retrieved live forecast
 * ({@code LiveWeatherService} — cached bulk path for the 6 Sangrur blocks,
 * single-point centroid path for generic registry blocks). All four sector
 * evaluators consume this object — no sector ever issues its own weather
 * provider call.
 *
 * <p>Null-honest: missing rainfall/ET0/moisture stays {@code null} through
 * every derived aggregate (never zero-filled). Derived totals require all
 * contributing days present.
 */
public record ForecastContext(
        String blockName,
        String displayName,
        String stateCode,
        String stateName,
        String districtCode,
        String districtName,
        String blockCode,
        Double latitude,
        Double longitude,
        String locationMethod,
        String provider,
        String model,
        LocalDate issueDate,
        Instant retrievedAt,
        boolean stale,
        String staleWarning,
        String spatialMethod,
        List<Day> days,
        // derived aggregates (null when inputs incomplete)
        Double rain3Mm,
        Double rain7Mm,
        Double et0_7dMm,
        Double rainMinusEt07Mm,
        Double maxDailyMm,
        String maxDailyDate,
        Integer wetDays13,
        Integer wetDays17,
        Integer dryDays7,
        Integer longestDryRun7,
        Integer trailingDryRun,
        Double soilMoistureDay0Vwc,
        // soil context (fail-soft, display only)
        boolean soilAvailable,
        String soilLine,
        String soilReason,
        List<String> missing) {

    /** One forecast day (D+1..D+16 as served). Nulls preserved. */
    public record Day(
            LocalDate date,
            int horizonDay,
            Double rainfallMm,
            Double rainProbabilityPct,
            Double temperatureMaxC,
            Double temperatureMinC,
            Double et0Mm,
            Double soilMoisture0To7CmVwc,
            Double rainMm,
            Double showersMm,
            Integer weatherCode) {}

    /** Dry day per the frozen rule: strictly &lt; 1.0 mm. Null is never dry. */
    public static boolean isDry(Double v) {
        return v != null && v < 1.0;
    }

    /** Wet day per the frozen rule: &gt;= 1.0 mm. Null is never wet. */
    public static boolean isWet(Double v) {
        return v != null && v >= 1.0;
    }

    /** Serialise for API responses (Jackson-friendly maps). */
    public Map<String, Object> toMap() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("block_name", blockName);
        out.put("display_name", displayName);
        out.put("state_code", stateCode);
        out.put("state_name", stateName);
        out.put("district_code", districtCode);
        out.put("district_name", districtName);
        out.put("block_code", blockCode);
        out.put("latitude", latitude);
        out.put("longitude", longitude);
        out.put("location_method", locationMethod);
        out.put("provider", provider);
        out.put("model", model);
        out.put("issue_date", issueDate == null ? null : issueDate.toString());
        out.put("retrieved_at", retrievedAt == null ? null : retrievedAt.toString());
        out.put("stale", stale);
        if (staleWarning != null) out.put("stale_warning", staleWarning);
        out.put("spatial_method", spatialMethod);
        java.util.List<Map<String, Object>> dl = new java.util.ArrayList<>();
        if (days != null) {
            for (Day d : days) {
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("date", d.date() == null ? null : d.date().toString());
                m.put("horizon_day", d.horizonDay());
                m.put("rainfall_mm", d.rainfallMm());
                m.put("rain_probability_pct", d.rainProbabilityPct());
                m.put("temperature_max_c", d.temperatureMaxC());
                m.put("temperature_min_c", d.temperatureMinC());
                m.put("et0_mm", d.et0Mm());
                m.put("soil_moisture_0_to_7cm_vwc", d.soilMoisture0To7CmVwc());
                m.put("rain_mm", d.rainMm());
                m.put("showers_mm", d.showersMm());
                m.put("weather_code", d.weatherCode());
                dl.add(m);
            }
        }
        out.put("days", dl);
        out.put("horizon_days", dl.size());
        java.util.Map<String, Object> agg = new java.util.LinkedHashMap<>();
        agg.put("rain_3d_mm", rain3Mm);
        agg.put("rain_7d_mm", rain7Mm);
        agg.put("et0_7d_mm", et0_7dMm);
        agg.put("rain_minus_et0_7d_mm", rainMinusEt07Mm);
        agg.put("max_daily_mm", maxDailyMm);
        agg.put("max_daily_date", maxDailyDate);
        agg.put("wet_days_d1_d3", wetDays13);
        agg.put("wet_days_d1_d7", wetDays17);
        agg.put("dry_days_d1_d7", dryDays7);
        agg.put("longest_dry_run_d1_d7", longestDryRun7);
        agg.put("trailing_dry_run", trailingDryRun);
        agg.put("soil_moisture_day0_vwc", soilMoistureDay0Vwc);
        out.put("aggregates", agg);
        java.util.Map<String, Object> soil = new java.util.LinkedHashMap<>();
        soil.put("available", soilAvailable);
        if (soilAvailable) {
            soil.put("line", soilLine);
            soil.put("source", "SoilGrids 0–5 cm (bundled block means for Sangrur; point query for generic blocks) — context only, not a measurement");
        } else {
            soil.put("reason", soilReason == null ? "Soil data unavailable for this block" : soilReason);
        }
        out.put("soil", soil);
        out.put("missing", missing == null ? List.of() : missing);
        return out;
    }
}
