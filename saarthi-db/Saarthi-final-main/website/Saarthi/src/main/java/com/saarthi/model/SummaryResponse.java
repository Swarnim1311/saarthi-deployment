package com.saarthi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** District-level summary, computed from the validated forecast (never hardcoded). */
public class SummaryResponse {
    private String district;
    @JsonProperty("issue_date")
    private String issueDate;
    @JsonProperty("valid_from")
    private String validFrom;
    @JsonProperty("valid_to")
    private String validTo;
    @JsonProperty("forecast_horizon_days")
    private int horizonDays;
    @JsonProperty("blocks_count")
    private int blocksCount;
    @JsonProperty("highest_rainfall_block")
    private String highestBlock;
    @JsonProperty("highest_rainfall_mm")
    private double highestMm;
    @JsonProperty("lowest_rainfall_block")
    private String lowestBlock;
    @JsonProperty("lowest_rainfall_mm")
    private double lowestMm;
    @JsonProperty("high_risk_blocks")
    private List<String> highBlocks;
    @JsonProperty("normal_blocks")
    private List<String> normalBlocks;
    @JsonProperty("low_risk_blocks")
    private List<String> lowBlocks;
    @JsonProperty("model_source")
    private String modelSource = "Raw CHIRPS-GEFS (7-day block outlook, NB04/NB05 validated pipeline)";

    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }
    public String getIssueDate() { return issueDate; }
    public void setIssueDate(String issueDate) { this.issueDate = issueDate; }
    public String getValidFrom() { return validFrom; }
    public void setValidFrom(String validFrom) { this.validFrom = validFrom; }
    public String getValidTo() { return validTo; }
    public void setValidTo(String validTo) { this.validTo = validTo; }
    public int getHorizonDays() { return horizonDays; }
    public void setHorizonDays(int horizonDays) { this.horizonDays = horizonDays; }
    public int getBlocksCount() { return blocksCount; }
    public void setBlocksCount(int blocksCount) { this.blocksCount = blocksCount; }
    public String getHighestBlock() { return highestBlock; }
    public void setHighestBlock(String highestBlock) { this.highestBlock = highestBlock; }
    public double getHighestMm() { return highestMm; }
    public void setHighestMm(double highestMm) { this.highestMm = highestMm; }
    public String getLowestBlock() { return lowestBlock; }
    public void setLowestBlock(String lowestBlock) { this.lowestBlock = lowestBlock; }
    public double getLowestMm() { return lowestMm; }
    public void setLowestMm(double lowestMm) { this.lowestMm = lowestMm; }
    public List<String> getHighBlocks() { return highBlocks; }
    public void setHighBlocks(List<String> highBlocks) { this.highBlocks = highBlocks; }
    public List<String> getNormalBlocks() { return normalBlocks; }
    public void setNormalBlocks(List<String> normalBlocks) { this.normalBlocks = normalBlocks; }
    public List<String> getLowBlocks() { return lowBlocks; }
    public void setLowBlocks(List<String> lowBlocks) { this.lowBlocks = lowBlocks; }
    public String getModelSource() { return modelSource; }
    public void setModelSource(String modelSource) { this.modelSource = modelSource; }
}
