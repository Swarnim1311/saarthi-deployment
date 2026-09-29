package com.saarthi.cropatlas;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CONDITION MATCH for dynamic FAO ECOCROP candidates (CropAtlas Tasks 8–12).
 *
 * <p>No invented agronomy. Each candidate is first mapped — strictly, by exact
 * taxon token — onto one requirement set from {@code CropRequirements}
 * (saarthi-crop-reference v1.0.0, PAU Package of Practices + ICAR-IARI). When a
 * mapped set exists, the candidate is scored with the SAME shared engine that
 * assesses every other crop ({@link CropSuitabilityService#assessById}), and
 * the card's MATCH DETAILS / CULTIVATION sections are the components of that
 * very assessment. Two different crops therefore report different percentages,
 * different reasons and different constraints, driven by this block's live
 * water balance, dry runs, season position and soil context.
 *
 * <p>When no sourced requirement set exists for a species — the common case for
 * ECOCROP's world database — no percentage and no progress bar is published at
 * all (<b>a missing requirement set never becomes a mid-range "safe"
 * score</b>). The card then carries the honest "requirement set not available"
 * state, plus the species' own ECOCROP description notes, which are genuinely
 * crop-specific and quoted verbatim.
 *
 * <p>Market prices: served by the dedicated MandiPriceService and merged by the
 * controller, never by this scorer.
 */
@Service
public class CropConditionMatchService {

    /**
     * Strict ECOCROP-name → requirement-id mapping. A mapping exists only when
     * the species unambiguously IS the same crop as the cited requirement set.
     * Ambiguous genera (e.g. a generic Oryza sativa row, which could be paddy
     * or basmati) are deliberately NOT mapped, so no card ever borrows another
     * crop's score. These are synonyms from the requirement's own aliases and
     * binomial names — not agronomic numbers.
     */
    static final Map<String, String> SCIENTIFIC_TO_REQUIREMENT = Map.ofEntries(
            Map.entry("zea mays", "maize"),
            Map.entry("zea mays subsp mays", "maize"),
            Map.entry("triticum aestivum", "wheat"),
            Map.entry("triticum turgidum subsp durum", "wheat"),
            Map.entry("gossypium hirsutum", "cotton"),
            Map.entry("gossypium arboreum", "cotton"),
            Map.entry("saccharum officinarum", "sugarcane"));

    /** Canonical requirement ids usable as common-name fallbacks. */
    static final Map<String, String> COMMON_TO_REQUIREMENT = Map.ofEntries(
            Map.entry("maize", "maize"),
            Map.entry("corn", "maize"),
            Map.entry("wheat", "wheat"),
            Map.entry("cotton", "cotton"),
            Map.entry("sugarcane", "sugarcane"));

    private final CropSuitabilityService suitability;
    private final CropRequirements requirements;

    @Autowired
    public CropConditionMatchService(CropSuitabilityService suitability,
            CropRequirements requirements) {
        this.suitability = suitability;
        this.requirements = requirements;
    }

    /**
     * Attempt the strict mapping for one candidate's names. Returns the
     * requirement id, or {@code null} when the species has no sourced set.
     */
    public String requirementIdFor(String scientificName, String commonName) {
        String sci = normalize(scientificName);
        if (sci != null) {
            String hit = SCIENTIFIC_TO_REQUIREMENT.get(sci);
            if (hit != null) return hit;
        }
        // Common names map only for unambiguous single-species words; never
        // for "rice", "paddy" or "basmati" (all map to the same genus).
        for (String token : alternateTokens(commonName)) {
            String hit = COMMON_TO_REQUIREMENT.get(token);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Human band for the suitability key; bands are display-only. */
    static String qualitativeLabel(String suitability) {
        return switch (suitability == null ? "" : suitability) {
            case "excellent_match" -> "Excellent match";
            case "good_match" -> "Good match";
            case "moderate_match" -> "Moderate match";
            case "limited_match" -> "Limited match";
            case "poor_match" -> "Poor match";
            default -> "Insufficient data";
        };
    }

    /**
     * Full condition-match payload for one displayed ECOCROP candidate against
     * one block fingerprint. Carries the requirement info (or the honest
     * unavailable flag), the engine's percentage/label/components/why/watch
     * lines, and the cultivation facts from the mapped requirement.
     */
    public Map<String, Object> match(String scientificName, String commonName,
            EnvironmentalFingerprint fingerprint, LocalDate today) {
        Map<String, Object> out = new LinkedHashMap<>();
        String reqId = requirementIdFor(scientificName, commonName);
        CropRequirements.Requirement req = reqId == null ? null : requirements.byId(reqId);
        if (req == null) {
            out.put("requirement_available", false);
            out.put("condition_match_available", false);
            out.put("reason", "No sourced requirement set covers this species "
                    + "(saarthi-crop-reference v1.0.0: paddy, basmati, cotton, maize, wheat, "
                    + "sugarcane). No compatibility score is computed — one is never invented.");
            return out;
        }
        out.put("requirement_available", true);
        out.put("requirement_id", reqId);
        out.put("requirement_name", req.displayName());
        out.put("requirement_season", req.season());
        out.put("requirement_group", req.group());
        out.put("requirement_sources",
                req.sourceIds() == null ? List.of() : req.sourceIds());

        CropSuitabilityResult res = suitability.assessById(reqId, fingerprint,
                today == null ? LocalDate.now() : today);
        if (res == null || !res.scoreAvailable() || res.compatibilityScore() == null) {
            out.put("condition_match_available", false);
            out.put("reason", "The sourced requirement set applies, but the block served too "
                    + "few measurable dimensions to evaluate it.");
            out.put("missing_data", res == null ? List.of() : res.missingData());
            return out;
        }
        int pct = (int) Math.round(res.compatibilityScore() * 100.0);
        out.put("condition_match_available", true);
        out.put("condition_match_percent", pct);
        out.put("condition_match_label", qualitativeLabel(res.suitability()));
        out.put("suitability", res.suitability());
        out.put("components_available", res.componentsAvailable());
        out.put("components_total", res.componentsTotal());
        out.put("data_confidence", res.dataConfidence());
        out.put("score_note", "Rule-based compatibility from the shared SAARTHI engine — "
                + "not a yield forecast, not a success probability.");
        List<Map<String, Object>> lines = new ArrayList<>();
        for (CropSuitabilityResult.Component c : CropSuitabilityResult.available(res.components())) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("component", c.label());
            cm.put("reasons", c.reasons());
            cm.put("constraints", c.constraints());
            lines.add(cm);
        }
        out.put("component_lines", lines);
        out.put("why", res.why() == null ? List.of() : res.why());
        out.put("watch", res.watch() == null ? List.of() : res.watch());
        out.put("agronomic_considerations",
                res.agronomicConsiderations() == null ? List.of() : res.agronomicConsiderations());
        out.put("missing_data", res.missingData() == null ? List.of() : res.missingData());
        Map<String, Object> cultivation = new LinkedHashMap<>();
        cultivation.put("crop_name", req.displayName());
        cultivation.put("season", req.season());
        cultivation.put("group", req.group());
        cultivation.put("sow_window_start", req.sowStartMmDd());
        cultivation.put("sow_window_end", req.sowEndMmDd());
        cultivation.put("sow_window_basis", req.sowWindowBasis());
        cultivation.put("duration_days", req.durationDays());
        cultivation.put("duration_basis", req.durationBasis());
        cultivation.put("water_need_class", req.waterNeedClass());
        cultivation.put("waterlogging_tolerance", req.waterloggingTolerance());
        cultivation.put("drought_sensitivity", req.droughtSensitivity());
        cultivation.put("irrigation_rule_hint", req.irrigationRuleHint());
        cultivation.put("reference_ids",
                req.referenceIds() == null ? List.of() : req.referenceIds());
        cultivation.put("source_ids",
                req.sourceIds() == null ? List.of() : req.sourceIds());
        out.put("cultivation", cultivation);
        return out;
    }



    // ---- helpers ----

    private static String normalize(String name) {
        if (name == null) return null;
        String t = name.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("[^a-z\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return t.isEmpty() ? null : t;
    }

    private static List<String> alternateTokens(String commonName) {
        List<String> out = new ArrayList<>();
        if (commonName == null) return out;
        for (String part : commonName.split("[,;/|]")) {
            String t = normalize(part);
            if (t != null) out.add(t);
        }
        return out;
    }
}
