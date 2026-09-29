package com.saarthi.cropatlas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A crop candidate as presented for one block: the crop's identity, its
 * {@link CropSuitabilityResult}, and the measured block values it was compared
 * against (so the UI can show "this crop's demand vs this block's supply"
 * without a second request).
 *
 * <p>Identical to the suitability result plus block-side context; kept separate
 * so the recommendations payload can be a flat, ordered list.
 */
public record CropCandidate(
        CropSuitabilityResult result,
        EnvironmentalFingerprint.Climate blockClimate,
        EnvironmentalFingerprint.Soil blockSoil) {

    public String band() {
        return switch (result.suitability()) {
            case "excellent_match", "good_match" -> "strong_matches";
            case "moderate_match" -> "potential_matches";
            case "limited_match", "poor_match" -> "limited_matches";
            default -> "insufficient_data";
        };
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("band", band());
        m.put("crop", result.toMap());
        m.put("block_context", blockContext());
        return m;
    }

    private Map<String, Object> blockContext() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (blockClimate != null) {
            m.put("water_balance_7d_mm", blockClimate.waterBalance7dMm());
            m.put("rain_7d_mm", blockClimate.rain7dMm());
            m.put("et0_7d_mm", blockClimate.et0_7dMm());
            m.put("dry_days_d1_d7", blockClimate.dryDays7());
            m.put("temp_max_mean_c", blockClimate.tempMaxMeanC());
            m.put("temp_min_mean_c", blockClimate.tempMinMeanC());
        }
        if (blockSoil != null && blockSoil.available()) {
            m.put("soil_clay_g_kg", blockSoil.clayGkg());
            m.put("soil_sand_g_kg", blockSoil.sandGkg());
            m.put("soil_silt_g_kg", blockSoil.siltGkg());
            m.put("soil_ph", blockSoil.ph());
        }
        Map<String, Object> demand = new LinkedHashMap<>();
        CropRequirements.Requirement r = result.requirement();
        demand.put("water_need_class", r == null ? null : r.waterNeedClass());
        demand.put("waterlogging_tolerance", r == null ? null : r.waterloggingTolerance());
        demand.put("drought_sensitivity", r == null ? null : r.droughtSensitivity());
        demand.put("duration_days", r == null ? null : r.durationDays());
        m.put("crop_demand", demand);
        return m;
    }

    /** Deterministic ordering: band, then descending score, then crop name. */
    public static int compare(CropCandidate a, CropCandidate b) {
        int byBand = Integer.compare(rank(a.band()), rank(b.band()));
        if (byBand != 0) return byBand;
        double sa = a.result().compatibilityScore() == null ? -1 : a.result().compatibilityScore();
        double sb = b.result().compatibilityScore() == null ? -1 : b.result().compatibilityScore();
        int byScore = Double.compare(sb, sa);
        if (byScore != 0) return byScore;
        return a.result().cropName().compareToIgnoreCase(b.result().cropName());
    }

    private static int rank(String band) {
        return switch (band) {
            case "strong_matches" -> 0;
            case "potential_matches" -> 1;
            case "limited_matches" -> 2;
            default -> 3;
        };
    }

    /** The three presented bands, in display order. */
    public static List<String> bandKeys() {
        return List.of("strong_matches", "potential_matches", "limited_matches");
    }
}
