package com.saarthi.cropatlas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Block-SIDE classification bands and the overall combination rule for
 * CropAtlas, loaded from the versioned {@code classpath:/cropatlas/atlas-method.json}.
 *
 * <p><b>This is the rule side, not the data side.</b> Crop-side requirements come
 * from the source-cited agronomy reference ({@link CropRequirements}); the bands
 * here describe only how an OBSERVED block is classified so the two can be
 * compared. They are CropAtlas rule-based planning defaults — deliberately kept
 * separate from crop science, and explicitly not calibrated against any yield
 * outcome (see the file's {@code _provenance} and {@code validation_note}).
 *
 * <p>Fail-soft: a missing or malformed resource falls back to compiled-in
 * defaults identical to the shipped file, so the service still works.
 */
@Component
public class CropAtlasMethod {

    private static final Logger log = LoggerFactory.getLogger(CropAtlasMethod.class);
    static final String RESOURCE = "cropatlas/atlas-method.json";

    /** One observed-water-supply band. */
    public record SupplyBand(String key, double minBalanceMm, int ordinal, String label) {}

    public record LabelBand(String key, double minScore) {}

    public record ConfidenceBand(String key, int minAvailableComponents,
                                 boolean requiresSoil, boolean requiresClimatology) {}

    int waterBalanceWindowDays = 7;
    int dryRunDryingStepDays = 5;
    List<SupplyBand> supplyBands = new ArrayList<>();
    int waterExcellentStep = 1;
    int waterGoodStep = 0;
    int waterModerateStep = -1;
    int waterLimitedStep = -2;
    int waterPoorStep = -3;
    int seasonUpcomingDays = 30;
    int seasonRecentlyClosedDays = 30;
    int minComponentsForOverall = 2;
    List<LabelBand> overallBands = new ArrayList<>();
    int highConfidenceComponents = 3;
    int moderateConfidenceComponents = 2;
    int lowConfidenceComponents = 1;
    boolean highConfidenceRequiresSoil = true;
    String methodVersion = "cropatlas-method-v1";
    String validationNote = "Rule-based compatibility assessment. Not calibrated against any "
            + "yield outcome and not a probability of success.";

    public CropAtlasMethod() {
        this(RESOURCE);
    }

    /** Test seam: load from an alternate classpath resource. */
    CropAtlasMethod(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            methodVersion = root.path("method_version").asText(methodVersion);
            waterBalanceWindowDays = root.path("water_balance_window_days").asInt(waterBalanceWindowDays);
            dryRunDryingStepDays = root.path("dry_run_drying_step_days").asInt(dryRunDryingStepDays);
            JsonNode b = root.path("block_water_supply_bands");
            if (b.isObject()) {
                List<SupplyBand> bands = new ArrayList<>();
                b.fields().forEachRemaining(e -> {
                    if (e.getKey().startsWith("_")) return;
                    JsonNode v = e.getValue();
                    bands.add(new SupplyBand(e.getKey(),
                            v.path("min_balance_mm").asDouble(Double.NEGATIVE_INFINITY),
                            v.path("ordinal").asInt(0),
                            v.path("label").asText(e.getKey())));
                });
                if (!bands.isEmpty()) supplyBands = bands;
            }
            JsonNode w = root.path("water_compatibility_ordinal_step");
            waterExcellentStep = w.path("excellent").asInt(waterExcellentStep);
            waterGoodStep = w.path("good").asInt(waterGoodStep);
            waterModerateStep = w.path("moderate").asInt(waterModerateStep);
            waterLimitedStep = w.path("limited").asInt(waterLimitedStep);
            waterPoorStep = w.path("poor").asInt(waterPoorStep);
            seasonUpcomingDays = root.path("season_window_days_upcoming").asInt(seasonUpcomingDays);
            seasonRecentlyClosedDays =
                    root.path("season_window_days_recently_closed").asInt(seasonRecentlyClosedDays);
            minComponentsForOverall = root.path("min_components_for_overall").asInt(minComponentsForOverall);
            JsonNode ob = root.path("overall_label_bands");
            if (ob.isObject()) {
                List<LabelBand> labels = new ArrayList<>();
                ob.fields().forEachRemaining(e -> {
                    if (e.getKey().startsWith("_")) return;
                    labels.add(new LabelBand(e.getKey(), e.getValue().path("min_score").asDouble(0)));
                });
                if (!labels.isEmpty()) overallBands = labels;
            }
            JsonNode cb = root.path("confidence_bands");
            JsonNode hi = cb.path("high");
            JsonNode mo = cb.path("moderate");
            JsonNode lo = cb.path("low");
            highConfidenceComponents = hi.path("min_available_components").asInt(highConfidenceComponents);
            highConfidenceRequiresSoil = hi.path("requires_soil").asBoolean(highConfidenceRequiresSoil);
            moderateConfidenceComponents = mo.path("min_available_components").asInt(moderateConfidenceComponents);
            lowConfidenceComponents = lo.path("min_available_components").asInt(lowConfidenceComponents);
        } catch (Exception e) {
            log.warn("CropAtlas method config unavailable ({}); compiled defaults apply", e.getMessage());
        }
        if (supplyBands.isEmpty()) supplyBands = defaultSupplyBands();
        if (overallBands.isEmpty()) overallBands = defaultOverallBands();
        // Deterministic ordering: strongest band first for both dimensions.
        supplyBands.sort((a, b) -> Double.compare(b.minBalanceMm(), a.minBalanceMm()));
        overallBands.sort((a, b) -> Double.compare(b.minScore(), a.minScore()));
        this.supplyBands = Collections.unmodifiableList(supplyBands);
        this.overallBands = Collections.unmodifiableList(overallBands);
    }

    private static List<SupplyBand> defaultSupplyBands() {
        List<SupplyBand> out = new ArrayList<>();
        out.add(new SupplyBand("abundant", 15.0, 3, "Water supply abundant"));
        out.add(new SupplyBand("adequate", 0.0, 2, "Water supply adequate"));
        out.add(new SupplyBand("marginal", -20.0, 1, "Water supply marginal"));
        out.add(new SupplyBand("deficit", Double.NEGATIVE_INFINITY, 0, "Water supply deficit"));
        return out;
    }

    private static List<LabelBand> defaultOverallBands() {
        List<LabelBand> out = new ArrayList<>();
        out.add(new LabelBand("excellent_match", 0.85));
        out.add(new LabelBand("good_match", 0.70));
        out.add(new LabelBand("moderate_match", 0.50));
        out.add(new LabelBand("limited_match", 0.30));
        out.add(new LabelBand("poor_match", 0.0));
        return out;
    }

    /**
     * Classify an observed 7-day water balance (mm) into a supply band.
     * Returns {@code null} when the balance is not actually known.
     */
    public SupplyBand classifyWaterBalance(Double balanceMm) {
        if (balanceMm == null || !Double.isFinite(balanceMm)) return null;
        for (SupplyBand band : supplyBands) {
            if (balanceMm >= band.minBalanceMm()) return band;
        }
        return supplyBands.get(supplyBands.size() - 1);
    }

    /** Supply band one step drier (used when a dry run is observed). */
    public SupplyBand stepDrier(SupplyBand band) {
        if (band == null) return null;
        for (int i = 0; i < supplyBands.size(); i++) {
            if (supplyBands.get(i).key().equals(band.key()) && i + 1 < supplyBands.size()) {
                return supplyBands.get(i + 1);
            }
        }
        return band;
    }

    /** Overall compatibility label for a mean component score. */
    public LabelBand classifyOverall(Double meanScore) {
        if (meanScore == null || !Double.isFinite(meanScore)) return null;
        for (LabelBand band : overallBands) {
            if (meanScore >= band.minScore()) return band;
        }
        return overallBands.get(overallBands.size() - 1);
    }

    public List<SupplyBand> supplyBands() { return supplyBands; }
    public List<LabelBand> overallBands() { return overallBands; }
    public int waterBalanceWindowDays() { return waterBalanceWindowDays; }
    public int dryRunDryingStepDays() { return dryRunDryingStepDays; }
    public int seasonUpcomingDays() { return seasonUpcomingDays; }
    public int seasonRecentlyClosedDays() { return seasonRecentlyClosedDays; }
    public int minComponentsForOverall() { return minComponentsForOverall; }
    public int waterExcellentStep() { return waterExcellentStep; }
    public int waterGoodStep() { return waterGoodStep; }
    public int waterModerateStep() { return waterModerateStep; }
    public int waterLimitedStep() { return waterLimitedStep; }
    public int waterPoorStep() { return waterPoorStep; }
    public String methodVersion() { return methodVersion; }
    public String validationNote() { return validationNote; }
}
