package com.saarthi.cropatlas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The block's measured/observed environmental fingerprint for one CropAtlas
 * assessment.
 *
 * <p>Every field is an OBSERVED value from an existing SAARTHI service, or
 * {@code null} with the reason recorded in {@code missing}. Nothing here is
 * synthesised, zero-filled, defaulted, or carried over from another block.
 * Each dimension is tagged with its provenance class so the UI can distinguish
 * {@code live}, {@code historical}, {@code reference} and {@code unavailable}.
 *
 * @param location     geography identity actually used
 * @param climate      live ECMWF IFS climate summary (LIVE)
 * @param soil         SoilGrids / bundled block soil summary (REFERENCE or CACHED)
 * @param climatology  deployment rainfall normals (HISTORICAL, 6 legacy blocks only)
 * @param elevation    block elevation in metres, or {@code null} when the
 *                     elevation source could not serve this coordinate
 * @param elevationProvenance provenance for {@code elevationM}; {@code null}
 *                     falls back to REFERENCE/CACHED derivation in {@link #toMap}
 * @param cecCmolKg    SoilGrids CEC at 0–5 cm, or {@code null} when unavailable
 * @param cecProvenance provenance for {@code cecCmolKg}; same fallback rule
 * @param missing      which dimensions are unknown and why
 * @param sources      honest per-dimension provenance
 */
public record EnvironmentalFingerprint(
        Map<String, Object> location,
        Climate climate,
        Soil soil,
        Climatology climatology,
        Double elevationM,
        String elevationProvenance,
        Double cecCmolKg,
        String cecProvenance,
        List<String> missing,
        List<Map<String, Object>> sources) {

    /** Provenance classes used across the fingerprint. */
    public static final String LIVE = "live";
    public static final String CACHED = "cached";
    public static final String HISTORICAL = "historical";
    public static final String REFERENCE = "reference";
    public static final String UNAVAILABLE = "unavailable";

    /**
     * Live forecast-derived climate summary for the served horizon.
     * Every value is {@code null} unless actually present in the forecast;
     * aggregates require every contributing day to be present.
     */
    public record Climate(
            String provider,
            String model,
            String issueDate,
            String spatialMethod,
            boolean stale,
            Integer horizonDays,
            Double tempMinMeanC,
            Double tempMaxMeanC,
            Double tempMinLowC,
            Double tempMaxHighC,
            Double rain7dMm,
            Double rain16dMm,
            Double et0_7dMm,
            Double waterBalance7dMm,
            Integer wetDays7,
            Integer dryDays7,
            Double soilMoistureDay0Vwc) {}

    /**
     * Soil summary at 0–5 cm, plus the texture context the block actually has.
     *
     * <p>{@code cecCmolKg} is present only when the serving source returned a
     * usable CEC value (SoilGrids point query); the bundled block means carry
     * no CEC, so it stays {@code null} there rather than borrowed.
     * {@code cached} marks a coordinate-cache hit so the UI can show CACHED
     * instead of REFERENCE.
     */
    public record Soil(
            boolean available,
            Double clayGkg,
            Double sandGkg,
            Double siltGkg,
            Double socGkg,
            Double ph,
            Double cecCmolKg,
            boolean cached,
            String textureClass,
            String source,
            String depthNote) {}

    /**
     * Deployment rainfall normals (HISTORICAL/REFERENCE) for the block's growing
     * window. Present only for the six blocks that ship a DOY normal record.
     */
    public record Climatology(
            boolean available,
            String blockName,
            Double growingWindowNormalMm,
            Integer growingWindowDays,
            String vintage,
            String reason) {}

    // ---- serialisation ----

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("location", location);
        out.put("climate", climateMap());
        out.put("soil", soilMap());
        out.put("climatology", climatologyMap());
        out.put("elevation_m", elevationM);
        out.put("elevation_provenance", elevationM == null ? UNAVAILABLE
                : (elevationProvenance != null ? elevationProvenance : REFERENCE));
        out.put("cec_cmol_kg", cecCmolKg);
        out.put("cec_provenance", cecCmolKg == null ? UNAVAILABLE
                : (cecProvenance != null ? cecProvenance : REFERENCE));
        out.put("missing", missing == null ? List.of() : missing);
        out.put("sources", sources);
        return out;
    }

    private Map<String, Object> climateMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provenance", climate == null ? UNAVAILABLE : LIVE);
        if (climate == null) {
            m.put("available", false);
            m.put("reason", "Live forecast unavailable for this block");
            return m;
        }
        m.put("available", true);
        m.put("provider", climate.provider());
        m.put("model", climate.model());
        m.put("issue_date", climate.issueDate());
        m.put("spatial_method", climate.spatialMethod());
        m.put("stale", climate.stale());
        m.put("horizon_days", climate.horizonDays());
        m.put("temp_min_mean_c", climate.tempMinMeanC());
        m.put("temp_max_mean_c", climate.tempMaxMeanC());
        m.put("temp_min_low_c", climate.tempMinLowC());
        m.put("temp_max_high_c", climate.tempMaxHighC());
        m.put("rain_7d_mm", climate.rain7dMm());
        m.put("rain_16d_mm", climate.rain16dMm());
        m.put("et0_7d_mm", climate.et0_7dMm());
        m.put("water_balance_7d_mm", climate.waterBalance7dMm());
        m.put("wet_days_d1_d7", climate.wetDays7());
        m.put("dry_days_d1_d7", climate.dryDays7());
        m.put("soil_moisture_day0_vwc", climate.soilMoistureDay0Vwc());
        m.put("note", "Live forecast horizon only. A 16-day forecast alone does not determine "
                + "crop suitability; CropAtlas combines it with soil and reference climate data.");
        return m;
    }

    private Map<String, Object> soilMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provenance", soil != null && soil.available()
                ? (soil.cached() ? CACHED : REFERENCE) : UNAVAILABLE);
        if (soil == null || !soil.available()) {
            m.put("available", false);
            m.put("reason", soil == null || soil.source() == null
                    ? "Soil data unavailable" : soil.source());
            return m;
        }
        m.put("available", true);
        m.put("clay_g_kg", soil.clayGkg());
        m.put("sand_g_kg", soil.sandGkg());
        m.put("silt_g_kg", soil.siltGkg());
        m.put("soc_g_kg", soil.socGkg());
        m.put("ph", soil.ph());
        m.put("cec_cmol_kg", soil.cecCmolKg());
        m.put("texture_class", soil.textureClass());
        m.put("source", soil.source());
        m.put("depth", soil.depthNote());
        m.put("note", "Measured soil context only. CropAtlas does not score soil compatibility "
                + "because the consulted crop reference states no per-crop pH or texture requirement.");
        return m;
    }

    private Map<String, Object> climatologyMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provenance", climatology != null && climatology.available() ? HISTORICAL : UNAVAILABLE);
        if (climatology == null || !climatology.available()) {
            m.put("available", false);
            m.put("reason", climatology == null
                    ? "Reference rainfall normals unavailable for this block" : climatology.reason());
            return m;
        }
        m.put("available", true);
        m.put("block", climatology.blockName());
        m.put("growing_window_normal_mm", climatology.growingWindowNormalMm());
        m.put("growing_window_days", climatology.growingWindowDays());
        m.put("vintage", climatology.vintage());
        m.put("note", "Reference rainfall normals, not a forecast. Present only for blocks that "
                + "ship a day-of-year normal record.");
        return m;
    }
}
