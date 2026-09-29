package com.saarthi.cropatlas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One crop's explainable compatibility assessment for one block.
 *
 * <p><b>What this is not.</b> This is a rule/requirements-based COMPATIBILITY
 * assessment, not an ML prediction and not a probability of success. The
 * optional {@code compatibilityScore} is the mean of the available component
 * scores and is explicitly labelled as a rule-based compatibility score; it is
 * never a yield, a success chance, or a calibrated likelihood.
 *
 * <p>Every component independently reports whether it could be evaluated at
 * all. Unavailable components keep {@code available:false} with a reason — they
 * are never dropped, and they are never replaced by zero.
 */
public record CropSuitabilityResult(
        CropRequirements.Requirement requirement,
        String cropId,
        String cropName,
        List<String> aliases,
        String group,
        String season,
        String suitability,
        Double compatibilityScore,
        boolean scoreAvailable,
        int componentsAvailable,
        int componentsTotal,
        String dataConfidence,
        List<Component> components,
        List<String> why,
        List<String> watch,
        List<String> agronomicConsiderations,
        List<String> missingData,
        List<String> referenceIds,
        List<String> sourceIds) {

    /**
     * One compatibility dimension (climate / soil / water / growing season).
     * A component with no sourced crop requirement, or no observed block value,
     * reports {@code available:false} and contributes nothing to the score.
     */
    public record Component(
            String key,
            String label,
            boolean available,
            Double score,
            List<String> reasons,
            List<String> constraints,
            String unavailableReason) {

        public static Component unavailable(String key, String label, String reason) {
            return new Component(key, label, false, null, List.of(), List.of(), reason);
        }

        public static Component of(String key, String label, double score,
                List<String> reasons, List<String> constraints) {
            double s = clamp01(score);
            return new Component(key, label, true, round2(s), reasons, constraints, null);
        }

        private static double clamp01(double v) {
            if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
            return Math.max(0.0, Math.min(1.0, v));
        }

        private static Double round2(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }

    /** Score contribution of one ordinal step, mapped onto 0..1. */
    static double stepScore(int step) {
        // step >= 1 → 1.0 ; 0 → 0.85 ; -1 → 0.6 ; -2 → 0.35 ; <= -3 → 0.1
        return switch (step) {
            case 1, 2, 3 -> 1.0;
            case 0 -> 0.85;
            case -1 -> 0.6;
            case -2 -> 0.35;
            default -> 0.1;
        };
    }

    /** Mean of available component scores, or {@code null} when too few exist. */
    static Double overallScore(List<Component> components, int minComponents) {
        List<Component> available = available(components);
        if (available.size() < minComponents) return null;
        double sum = 0;
        for (Component c : available) sum += c.score();
        double mean = sum / available.size();
        return Math.round(mean * 100.0) / 100.0;
    }

    static List<Component> available(List<Component> components) {
        List<Component> out = new ArrayList<>();
        for (Component c : components) {
            if (c != null && c.available() && c.score() != null
                    && !Double.isNaN(c.score()) && !Double.isInfinite(c.score())) {
                out.add(c);
            }
        }
        return out;
    }

    static List<Component> all(List<Component> components) {
        List<Component> out = new ArrayList<>();
        for (Component c : components) {
            if (c != null) out.add(c);
        }
        return out;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("crop_id", cropId);
        m.put("crop_name", cropName);
        m.put("aliases", aliases == null ? List.of() : aliases);
        m.put("group", group);
        m.put("season", season);
        m.put("suitability", suitability);
        m.put("compatibility_score", compatibilityScore);
        m.put("score_label", "Rule-based compatibility score (0-1). Not a probability, "
                + "not a yield forecast, not validated against an outcome.");
        m.put("score_available", scoreAvailable);
        m.put("components_available", componentsAvailable);
        m.put("components_total", componentsTotal);
        m.put("data_confidence", dataConfidence);
        Map<String, Object> comps = new LinkedHashMap<>();
        for (Component c : all(components)) comps.put(c.key(), componentMap(c));
        m.put("components", comps);
        m.put("why", why == null ? List.of() : why);
        m.put("watch", watch == null ? List.of() : watch);
        m.put("agronomic_considerations", agronomicConsiderations == null ? List.of() : agronomicConsiderations);
        m.put("missing_data", missingData == null ? List.of() : missingData);
        m.put("reference_ids", referenceIds == null ? List.of() : referenceIds);
        m.put("source_ids", sourceIds == null ? List.of() : sourceIds);
        return m;
    }

    private static Map<String, Object> componentMap(Component c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", c.label());
        m.put("available", c.available());
        if (c.available()) {
            m.put("score", c.score());
        } else {
            m.put("score", null);
            m.put("unavailable_reason", c.unavailableReason());
        }
        m.put("reasons", c.reasons() == null ? List.of() : c.reasons());
        m.put("constraints", c.constraints() == null ? List.of() : c.constraints());
        return m;
    }
}
