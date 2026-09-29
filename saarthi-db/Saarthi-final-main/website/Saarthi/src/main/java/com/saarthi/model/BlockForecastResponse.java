package com.saarthi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Single-block 7-day outlook, mapped from the validated application contract (no recomputation). */
public class BlockForecastResponse {
    @JsonProperty("block_id")
    private String blockId;
    @JsonProperty("block_name")
    private String blockName;
    @JsonProperty("issue_date")
    private String issueDate;
    @JsonProperty("valid_from")
    private String validFrom;
    @JsonProperty("valid_to")
    private String validTo;
    @JsonProperty("forecast_7d_total_rainfall_mm")
    private double totalMm;
    private String category;
    @JsonProperty("prob_low")
    private double probLow;
    @JsonProperty("prob_normal")
    private double probNormal;
    @JsonProperty("prob_high")
    private double probHigh;
    @JsonProperty("daily_forecast")
    private List<Map<String, Object>> daily;
    private Map<String, Object> indicators;
    private List<Map<String, Object>> advisories;
    @JsonProperty("model_source")
    private String modelSource = "Raw CHIRPS-GEFS (7-day block outlook, NB04/NB05 validated pipeline)";

    public String getBlockId() { return blockId; }
    public void setBlockId(String blockId) { this.blockId = blockId; }
    public String getBlockName() { return blockName; }
    public void setBlockName(String blockName) { this.blockName = blockName; }
    public String getIssueDate() { return issueDate; }
    public void setIssueDate(String issueDate) { this.issueDate = issueDate; }
    public String getValidFrom() { return validFrom; }
    public void setValidFrom(String validFrom) { this.validFrom = validFrom; }
    public String getValidTo() { return validTo; }
    public void setValidTo(String validTo) { this.validTo = validTo; }
    public double getTotalMm() { return totalMm; }
    public void setTotalMm(double totalMm) { this.totalMm = totalMm; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public double getProbLow() { return probLow; }
    public void setProbLow(double probLow) { this.probLow = probLow; }
    public double getProbNormal() { return probNormal; }
    public void setProbNormal(double probNormal) { this.probNormal = probNormal; }
    public double getProbHigh() { return probHigh; }
    public void setProbHigh(double probHigh) { this.probHigh = probHigh; }
    public List<Map<String, Object>> getDaily() { return daily; }
    public void setDaily(List<Map<String, Object>> daily) { this.daily = daily; }
    public Map<String, Object> getIndicators() { return indicators; }
    public void setIndicators(Map<String, Object> indicators) { this.indicators = indicators; }
    public List<Map<String, Object>> getAdvisories() { return advisories; }
    public void setAdvisories(List<Map<String, Object>> advisories) { this.advisories = advisories; }
    public String getModelSource() { return modelSource; }
    public void setModelSource(String modelSource) { this.modelSource = modelSource; }
}
