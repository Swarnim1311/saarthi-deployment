package com.saarthi.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class HealthResponse {
    private String status;
    @JsonProperty("forecast_available")
    private boolean forecastAvailable;
    @JsonProperty("issue_date")
    private String issueDate;
    @JsonProperty("valid_from")
    private String validFrom;
    @JsonProperty("valid_to")
    private String validTo;
    @JsonProperty("blocks_available")
    private int blocksAvailable;
    @JsonProperty("forecast_horizon_days")
    private int forecastHorizonDays;
    private String model;
    @JsonProperty("generated_at")
    private String generatedAt;
    private String source;
    private boolean stale;
    @JsonProperty("age_days")
    private int ageDays;
    @JsonProperty("expires_at")
    private String expiresAt;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isForecastAvailable() { return forecastAvailable; }
    public void setForecastAvailable(boolean forecastAvailable) { this.forecastAvailable = forecastAvailable; }
    public String getIssueDate() { return issueDate; }
    public void setIssueDate(String issueDate) { this.issueDate = issueDate; }
    public String getValidFrom() { return validFrom; }
    public void setValidFrom(String validFrom) { this.validFrom = validFrom; }
    public String getValidTo() { return validTo; }
    public void setValidTo(String validTo) { this.validTo = validTo; }
    public int getBlocksAvailable() { return blocksAvailable; }
    public void setBlocksAvailable(int blocksAvailable) { this.blocksAvailable = blocksAvailable; }
    public int getForecastHorizonDays() { return forecastHorizonDays; }
    public void setForecastHorizonDays(int forecastHorizonDays) { this.forecastHorizonDays = forecastHorizonDays; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(String generatedAt) { this.generatedAt = generatedAt; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public boolean isStale() { return stale; }
    public void setStale(boolean stale) { this.stale = stale; }
    public int getAgeDays() { return ageDays; }
    public void setAgeDays(int ageDays) { this.ageDays = ageDays; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }
}
