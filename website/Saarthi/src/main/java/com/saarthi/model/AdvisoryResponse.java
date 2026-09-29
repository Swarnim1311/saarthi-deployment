package com.saarthi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Advisory payload for one block — NB06 prototype guidance plus live category/probabilities. */
public class AdvisoryResponse {
    @JsonProperty("block_id")
    private String blockId;
    @JsonProperty("block_name")
    private String blockName;
    private String category;
    @JsonProperty("prob_low")
    private double probLow;
    @JsonProperty("prob_normal")
    private double probNormal;
    @JsonProperty("prob_high")
    private double probHigh;
    @JsonProperty("forecast_7d_total_rainfall_mm")
    private double totalMm;
    private List<Map<String, Object>> advisories;
    @JsonProperty("guidance_label")
    private String guidanceLabel = "Prototype decision-support guidance (not agronomic validation)";

    public String getBlockId() { return blockId; }
    public void setBlockId(String blockId) { this.blockId = blockId; }
    public String getBlockName() { return blockName; }
    public void setBlockName(String blockName) { this.blockName = blockName; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public double getProbLow() { return probLow; }
    public void setProbLow(double probLow) { this.probLow = probLow; }
    public double getProbNormal() { return probNormal; }
    public void setProbNormal(double probNormal) { this.probNormal = probNormal; }
    public double getProbHigh() { return probHigh; }
    public void setProbHigh(double probHigh) { this.probHigh = probHigh; }
    public double getTotalMm() { return totalMm; }
    public void setTotalMm(double totalMm) { this.totalMm = totalMm; }
    public List<Map<String, Object>> getAdvisories() { return advisories; }
    public void setAdvisories(List<Map<String, Object>> advisories) { this.advisories = advisories; }
    public String getGuidanceLabel() { return guidanceLabel; }
    public void setGuidanceLabel(String guidanceLabel) { this.guidanceLabel = guidanceLabel; }
}
