package com.saarthi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class FarmerAnalysisResponse {
    private String status = "success";
    private Map<String, Object> inputs;

    /** 1. Location & provenance (block-level resolution, no panchayat weather). */
    @JsonProperty("location")
    private Map<String, Object> location;

    /** 2. Crop identity from the cited PAU/ICAR reference. */
    @JsonProperty("crop")
    private Map<String, Object> cropSection;

    /** 3. Stage — days_since_sowing only where the cited reference supports it. */
    @JsonProperty("stage")
    private Map<String, Object> stage;

    /** 4. Live weather (ECMWF IFS via /api/weather/forecast/{block}). */
    @JsonProperty("weather")
    private Map<String, Object> weatherSection;

    /** 5. Soil — forecast surface moisture + SoilGrids display context. */
    @JsonProperty("soil")
    private Map<String, Object> soilSection;

    /** 6. Water demand — 7-day rain vs ET0 climate balance. */
    @JsonProperty("water_demand")
    private Map<String, Object> waterDemand;

    /** 7. Risks — worded watches, no fabricated percentages. */
    @JsonProperty("risks")
    private Map<String, Object> risks;

    /** 8. Advisory actions — deterministic rules over the live feed. */
    @JsonProperty("advisory")
    private Map<String, Object> advisory;

    /** 9. Sources — every dataset the advisory relied on. */
    @JsonProperty("sources")
    private Map<String, Object> sources;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Map<String, Object> getInputs() { return inputs; }
    public void setInputs(Map<String, Object> inputs) { this.inputs = inputs; }

    public Map<String, Object> getLocation() { return location; }
    public void setLocation(Map<String, Object> location) { this.location = location; }

    public Map<String, Object> getCropSection() { return cropSection; }
    public void setCropSection(Map<String, Object> cropSection) { this.cropSection = cropSection; }

    public Map<String, Object> getStage() { return stage; }
    public void setStage(Map<String, Object> stage) { this.stage = stage; }

    public Map<String, Object> getWeatherSection() { return weatherSection; }
    public void setWeatherSection(Map<String, Object> weatherSection) { this.weatherSection = weatherSection; }

    public Map<String, Object> getSoilSection() { return soilSection; }
    public void setSoilSection(Map<String, Object> soilSection) { this.soilSection = soilSection; }

    public Map<String, Object> getWaterDemand() { return waterDemand; }
    public void setWaterDemand(Map<String, Object> waterDemand) { this.waterDemand = waterDemand; }

    public Map<String, Object> getRisks() { return risks; }
    public void setRisks(Map<String, Object> risks) { this.risks = risks; }

    public Map<String, Object> getAdvisory() { return advisory; }
    public void setAdvisory(Map<String, Object> advisory) { this.advisory = advisory; }

    public Map<String, Object> getSources() { return sources; }
    public void setSources(Map<String, Object> sources) { this.sources = sources; }
}
