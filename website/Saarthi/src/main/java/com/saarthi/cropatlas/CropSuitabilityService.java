package com.saarthi.cropatlas;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CropAtlas explainable rule/requirements engine.
 *
 * <p><b>Not an ML model and not a prediction.</b> For every crop with sourced
 * requirements it compares the block's measured fingerprint against the crop's
 * reference requirements across four independent components — climate, soil,
 * water, growing season — and combines ONLY the components that could actually
 * be evaluated. A component with no sourced crop requirement (soil: the consulted
 * reference states no per-crop pH or texture range) or no observed block value
 * stays unavailable, lowers confidence, and is reported as missing data.
 *
 * <p>Every component emits human-readable reasons and constraints, and every
 * candidate reports why it was assessed and what to watch. Missing data is never
 * converted to zero, and the combined label is only produced when at least
 * {@code min_components_for_overall} components are available — otherwise the
 * candidate is "insufficient_data".
 *
 * <p>Deterministic: identical inputs always produce identical output.
 */
@Service
public class CropSuitabilityService {

    private final CropRequirements requirements;
    private final CropAtlasMethod method;
    private final CropAtlasService atlas;

    @Autowired
    public CropSuitabilityService(CropRequirements requirements, CropAtlasMethod method,
            CropAtlasService atlas) {
        this.requirements = requirements;
        this.method = method;
        this.atlas = atlas;
    }

    /**
     * Assess every catalogued crop against one block fingerprint.
     * Returned list is deterministically ordered (see {@link CropCandidate#compare}).
     */
    public List<CropCandidate> assess(EnvironmentalFingerprint fingerprint, LocalDate asOf) {
        List<CropCandidate> out = new ArrayList<>();
        if (fingerprint == null) return out;
        LocalDate today = asOf == null ? LocalDate.now() : asOf;
        for (CropRequirements.Requirement r : requirements.all()) {
            out.add(new CropCandidate(assessOne(r, fingerprint, today),
                    fingerprint.climate(), fingerprint.soil()));
        }
        out.sort(CropCandidate::compare);
        return out;
    }

    /** Assess a single crop by id; {@code null} when the id is unknown. */
    public CropSuitabilityResult assessById(String cropId, EnvironmentalFingerprint fingerprint,
            LocalDate asOf) {
        CropRequirements.Requirement r = requirements.byId(cropId);
        if (r == null) return null;
        LocalDate today = asOf == null ? LocalDate.now() : asOf;
        return assessOne(r, fingerprint, today);
    }

    // ---- one crop ----

    CropSuitabilityResult assessOne(CropRequirements.Requirement r,
            EnvironmentalFingerprint fp, LocalDate today) {
        List<CropSuitabilityResult.Component> components = new ArrayList<>();
        components.add(waterComponent(r, fp));
        components.add(seasonComponent(r, today));
        components.add(climateComponent(r, fp, today));
        components.add(soilComponent(r, fp));

        List<CropSuitabilityResult.Component> available = CropSuitabilityResult.available(components);
        Double score = CropSuitabilityResult.overallScore(components, method.minComponentsForOverall());
        String label = score == null ? "insufficient_data" : method.classifyOverall(score).key();
        String confidence = confidence(available.size(), fp);

        List<String> why = new ArrayList<>();
        List<String> watch = new ArrayList<>();
        for (CropSuitabilityResult.Component c : available) {
            why.addAll(c.reasons());
            watch.addAll(c.constraints());
        }
        List<String> missing = new ArrayList<>();
        for (CropSuitabilityResult.Component c : CropSuitabilityResult.all(components)) {
            if (!c.available() && c.unavailableReason() != null) {
                missing.add(c.key() + ": " + c.unavailableReason());
            }
        }

        List<String> agronomic = new ArrayList<>();
        if (r.irrigationRuleHint() != null) agronomic.add(r.irrigationRuleHint());
        agronomic.add("Reference waterlogging tolerance: " + r.waterloggingTolerance()
                + "; drought sensitivity: " + r.droughtSensitivity() + ".");

        if (label.equals("insufficient_data")) {
            watch.add("Not enough evaluated components to call this block a match. "
                    + "Treat as a research question, not a recommendation.");
        }
        if (fp.climate() != null && fp.climate().stale()) {
            watch.add("Underlying live forecast is stale; treat the water signal with caution.");
        }

        return new CropSuitabilityResult(r, r.cropId(), r.displayName(), r.aliases(), r.group(),
                r.season(), label, score, score != null, available.size(), components.size(),
                confidence, components, why, watch, agronomic, missing,
                r.referenceIds(), r.sourceIds());
    }

    // ---- water component ----

    private CropSuitabilityResult.Component waterComponent(CropRequirements.Requirement r,
            EnvironmentalFingerprint fp) {
        EnvironmentalFingerprint.Climate c = fp.climate();
        if (c == null) {
            return CropSuitabilityResult.Component.unavailable("water", "Water compatibility",
                    "Live forecast unavailable for this block");
        }
        Double balance = c.waterBalance7dMm();
        if (balance == null) {
            return CropSuitabilityResult.Component.unavailable("water", "Water compatibility",
                    "7-day rainfall/ET0 window incomplete (missing days are never zero-filled)");
        }
        CropAtlasMethod.SupplyBand band = method.classifyWaterBalance(balance);
        if (band == null) {
            return CropSuitabilityResult.Component.unavailable("water", "Water compatibility",
                    "Water balance could not be classified");
        }
        // A sustained dry run makes the observed supply one band drier.
        Integer dry = c.dryDays7();
        boolean dried = dry != null && dry >= method.dryRunDryingStepDays();
        CropAtlasMethod.SupplyBand effective = dried ? method.stepDrier(band) : band;
        if (effective == null) effective = band;

        int step = effective.ordinal() - r.waterNeedOrdinal();
        double score = CropSuitabilityResult.stepScore(step);

        List<String> reasons = new ArrayList<>();
        List<String> constraints = new ArrayList<>();
        reasons.add(String.format(java.util.Locale.ROOT,
                "Crop water demand (%s) against block water supply (%s): "
                        + "forecast rainfall %s mm vs ET0 %s mm over %d days (balance %s mm).",
                r.waterNeedClass(), effective.label(),
                fmt(c.rain7dMm()), fmt(c.et0_7dMm()), method.waterBalanceWindowDays(),
                fmt(balance)));
        if (dried) {
            reasons.add(String.format(java.util.Locale.ROOT,
                    "%d of the first %d forecast days are below 1 mm, so the supply class was "
                            + "stepped one band drier.", dry, method.waterBalanceWindowDays()));
        }
        if (step >= 1) {
            reasons.add("Observed water supply currently exceeds this crop's demand class.");
        } else if (step == 0) {
            reasons.add("Observed water supply broadly matches this crop's demand class.");
        } else {
            reasons.add("Observed water supply is below this crop's typical demand class.");
        }
        if (effective.ordinal() >= 3 && r.waterloggingOrdinal() <= 1) {
            constraints.add("Wet conditions with low reference waterlogging tolerance: "
                    + "waterlogging is the main risk, not drought.");
        }
        if (effective.ordinal() <= 1 && r.droughtOrdinal() >= 2) {
            constraints.add("Drying conditions with " + r.droughtSensitivity()
                    + " drought sensitivity: moisture stress is the main risk.");
        }

        return CropSuitabilityResult.Component.of("water", "Water compatibility", score,
                reasons, constraints);
    }

    // ---- growing season component ----

    private CropSuitabilityResult.Component seasonComponent(CropRequirements.Requirement r,
            LocalDate today) {
        int todayDoy = CropRequirements.mmDdToDoy(
                String.format(java.util.Locale.ROOT, "%02d-%02d",
                        today.getMonthValue(), today.getDayOfMonth()));
        if (todayDoy < 0) {
            return CropSuitabilityResult.Component.unavailable("growing_season",
                    "Growing-season compatibility", "Today's calendar position could not be resolved");
        }
        int start = r.sowStartDoy();
        int end = r.sowEndDoy();
        if (start < 0 || end < 0) {
            return CropSuitabilityResult.Component.unavailable("growing_season",
                    "Growing-season compatibility", "Crop sowing window is not defined");
        }
        int len = r.sowWindowLengthDays();
        int daysSinceStart = mod(todayDoy - start, 365);
        int daysToNextStart = mod(start - todayDoy, 365);
        int daysSinceEnd = mod(todayDoy - end, 365);

        List<String> reasons = new ArrayList<>();
        List<String> constraints = new ArrayList<>();
        double score;

        if (daysSinceStart < len) {
            int left = len - daysSinceStart;
            score = left <= 15 ? 1.0 : (left <= 30 ? 0.92 : 0.85);
            reasons.add(String.format(java.util.Locale.ROOT,
                    "The reference %s window (%s–%s) is open now; about %d days remain.",
                    r.season(), r.sowStartMmDd(), r.sowEndMmDd(), left));
            if (r.durationDays() != null) {
                reasons.add("Reference growing duration is " + r.durationDays() + " days ("
                        + r.durationBasis() + ").");
            }
        } else if (daysToNextStart <= method.seasonUpcomingDays()) {
            score = 0.9;
            reasons.add(String.format(java.util.Locale.ROOT,
                    "The reference %s window (%s–%s) opens in about %d days.",
                    r.season(), r.sowStartMmDd(), r.sowEndMmDd(), daysToNextStart));
        } else if (daysSinceEnd <= method.seasonRecentlyClosedDays()) {
            score = 0.5;
            reasons.add(String.format(java.util.Locale.ROOT,
                    "The reference %s window closed about %d days ago.",
                    r.season(), daysSinceEnd));
            constraints.add("Late sowing compresses the season and shifts every later growth "
                    + "stage; the reference advises against sowing outside the window.");
        } else {
            score = 0.2;
            reasons.add(String.format(java.util.Locale.ROOT,
                    "Today is outside the reference %s window (%s–%s); the next window opens in "
                            + "about %d days.", r.season(), r.sowStartMmDd(), r.sowEndMmDd(),
                    daysToNextStart));
            constraints.add("Outside the reference sowing-transplanting window; CropAtlas is not "
                    + "advising against the crop, only reporting the calendar position.");
        }
        reasons.add("Calendar position only — this says nothing about the weather during the season.");

        return CropSuitabilityResult.Component.of("growing_season",
                "Growing-season compatibility", score, reasons, constraints);
    }

    // ---- climate component (growing-window reference rainfall) ----

    private CropSuitabilityResult.Component climateComponent(CropRequirements.Requirement r,
            EnvironmentalFingerprint fp, LocalDate today) {
        String blockName = String.valueOf(fp.location().get("block_name"));
        if (r.durationDays() == null || r.durationDays() <= 0) {
            return CropSuitabilityResult.Component.unavailable("climate", "Climate compatibility",
                    "Crop growing duration is not stated in the reference");
        }
        if (!atlas.hasClimatologyBlock(blockName)) {
            return CropSuitabilityResult.Component.unavailable("climate", "Climate compatibility",
                    "Reference rainfall normals are not published for this block");
        }
        List<LocalDate> window = growingWindow(today, r);
        if (window == null) {
            return CropSuitabilityResult.Component.unavailable("climate", "Climate compatibility",
                    "Growing window could not be resolved on the calendar");
        }
        Double normal = atlas.climatologySumMm(blockName, window);
        if (normal == null) {
            return CropSuitabilityResult.Component.unavailable("climate", "Climate compatibility",
                    "Reference rainfall normals incomplete for this growing window");
        }

        List<String> reasons = new ArrayList<>();
        List<String> constraints = new ArrayList<>();
        reasons.add(String.format(java.util.Locale.ROOT,
                "Reference normal rainfall over this crop's %d-day growing window is %s mm (%s).",
                r.durationDays(), fmt(normal), atlas.climatologyVintage()));
        reasons.add("Reference normals describe typical conditions, not this season's weather.");
        if (r.season().equalsIgnoreCase("annual")) {
            reasons.add("Annual crop: the reference compares whole-season demand, so a single "
                    + "window is indicative rather than decisive.");
        }

        // Ordinal only: the reference states no per-crop mm requirement, so the
        // normal is compared against this crop's own demand class using the
        // CropAtlas supply bands. No mm threshold is invented for any crop.
        CropAtlasMethod.SupplyBand band = method.classifyWaterBalance(normal);
        double score;
        if (band == null) {
            score = 0.5;
        } else {
            int step = band.ordinal() - r.waterNeedOrdinal();
            score = CropSuitabilityResult.stepScore(step);
            if (step >= 1) {
                reasons.add("Reference rainfall over the growing window is generous relative to "
                        + "this crop's demand class.");
            } else if (step == 0) {
                reasons.add("Reference rainfall over the growing window broadly matches this "
                        + "crop's demand class.");
            } else {
                reasons.add("Reference rainfall over the growing window is low relative to this "
                        + "crop's demand class.");
            }
        }
        if (r.season().equalsIgnoreCase("kharif") && normal < 100) {
            constraints.add("Low reference monsoon rainfall over a kharif growing window: "
                    + "water availability during the season is the key uncertainty.");
        }
        if (r.season().equalsIgnoreCase("rabi") && normal < 40) {
            constraints.add("Low reference winter rainfall over a rabi growing window: the crop "
                    + "relies substantially on irrigation or residual moisture.");
        }

        return CropSuitabilityResult.Component.of("climate", "Climate compatibility", score,
                reasons, constraints);
    }

    /**
     * The crop's growing window as concrete dates, anchored at the next opening of
     * the reference sowing window. {@code null} only if the window is unresolvable.
     */
    static List<LocalDate> growingWindow(LocalDate today, CropRequirements.Requirement r) {
        int start = r.sowStartDoy();
        if (start < 0 || r.durationDays() == null) return null;
        int todayDoy = CropRequirements.mmDdToDoy(String.format(java.util.Locale.ROOT,
                "%02d-%02d", today.getMonthValue(), today.getDayOfMonth()));
        if (todayDoy < 0) return null;
        int daysToStart = mod(start - todayDoy, 365);
        // If the window is currently open, anchor inside it; else at its next opening.
        int len = r.sowWindowLengthDays();
        int daysSinceStart = mod(todayDoy - start, 365);
        int anchorOffset = daysSinceStart < len ? 0 : daysToStart;
        List<LocalDate> out = new ArrayList<>(r.durationDays());
        for (int i = 0; i < r.durationDays(); i++) {
            out.add(today.plusDays(anchorOffset + i));
        }
        return out;
    }

    // ---- soil component (always unavailable: no sourced per-crop soil range) ----

    private CropSuitabilityResult.Component soilComponent(CropRequirements.Requirement r,
            EnvironmentalFingerprint fp) {
        EnvironmentalFingerprint.Soil soil = fp.soil();
        String reason = "The consulted crop reference states no per-crop soil pH or texture "
                + "requirement, so soil compatibility is not scored";
        if (soil == null || !soil.available()) {
            return CropSuitabilityResult.Component.unavailable("soil", "Soil compatibility",
                    "Soil data unavailable for this block, and no per-crop soil requirement is "
                            + "stated in the reference");
        }
        return CropSuitabilityResult.Component.unavailable("soil", "Soil compatibility", reason);
    }

    // ---- confidence ----

    private String confidence(int availableComponents, EnvironmentalFingerprint fp) {
        boolean soil = fp.soil() != null && fp.soil().available();
        boolean clim = fp.climatology() != null && fp.climatology().available();
        if (availableComponents >= method.highConfidenceComponents
                && (!method.highConfidenceRequiresSoil || soil) && clim) {
            return "high";
        }
        if (availableComponents >= method.moderateConfidenceComponents) return "moderate";
        if (availableComponents >= method.lowConfidenceComponents) return "low";
        return "insufficient";
    }

    // ---- helpers ----

    private static int mod(int v, int m) {
        int r = v % m;
        return r < 0 ? r + m : r;
    }

    /** Display helper: a formatted number, or an em dash when genuinely unknown. */
    private static String fmt(Double v) {
        if (v == null || Double.isNaN(v) || Double.isInfinite(v)) return "—";
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Grouped, ordered payload for the recommendations endpoint. */
    public static Map<String, Object> group(List<CropCandidate> candidates) {
        Map<String, List<Map<String, Object>>> bands = new LinkedHashMap<>();
        for (String k : CropCandidate.bandKeys()) bands.put(k, new ArrayList<>());
        Map<String, Object> out = new LinkedHashMap<>();
        for (CropCandidate c : candidates) {
            bands.computeIfAbsent(c.band(), k -> new ArrayList<>()).add(c.toMap());
        }
        out.put("strong_matches", bands.getOrDefault("strong_matches", List.of()));
        out.put("potential_matches", bands.getOrDefault("potential_matches", List.of()));
        out.put("limited_matches", bands.getOrDefault("limited_matches", List.of()));
        List<Map<String, Object>> insufficient = new ArrayList<>();
        for (CropCandidate c : candidates) {
            if ("insufficient_data".equals(c.band())) insufficient.add(c.toMap());
        }
        out.put("insufficient_data", insufficient);
        return out;
    }
}
