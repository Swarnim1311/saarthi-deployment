package com.saarthi.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class FarmerAnalysisRequest {
    // P0: no silent block default. A missing/blank block must yield HTTP 404
    // (unknown_block) via AgronomyService, never another block's rainfall.
    // Callers (portal.js/app.js forms) always send an explicit block.
    private String block;
    private String panchayat = "Suler Gherat";
    private String crop = "Paddy (PR-126)";
    private String soil = "Clay Loam";

    @JsonProperty("sowing_date")
    private String sowingDate;

    private String irrigation = "Canals";

    @JsonProperty("rain_3d")
    private Double rain3d;
    @JsonProperty("rain_7d")
    private Double rain7d;
    @JsonProperty("rain_14d")
    private Double rain14d;
    @JsonProperty("rain_30d")
    private Double rain30d;
    @JsonProperty("dry_days_7d")
    private Double dryDays7d;
    @JsonProperty("dry_days_14d")
    private Double dryDays14d;

    public String getBlock() { return block; }
    public void setBlock(String block) { this.block = block; }

    public String getPanchayat() { return panchayat != null ? panchayat : "Suler Gherat"; }
    public void setPanchayat(String panchayat) { this.panchayat = panchayat; }

    public String getCrop() { return crop != null ? crop : "Paddy (PR-126)"; }
    public void setCrop(String crop) { this.crop = crop; }

    public String getSoil() { return soil != null ? soil : "Clay Loam"; }
    public void setSoil(String soil) { this.soil = soil; }

    public String getSowingDate() { return sowingDate; }
    public void setSowingDate(String sowingDate) { this.sowingDate = sowingDate; }

    public String getIrrigation() { return irrigation != null ? irrigation : "Canals"; }
    public void setIrrigation(String irrigation) { this.irrigation = irrigation; }

    public Double getRain3d() { return rain3d; }
    public void setRain3d(Double rain3d) { this.rain3d = rain3d; }

    public Double getRain7d() { return rain7d; }
    public void setRain7d(Double rain7d) { this.rain7d = rain7d; }

    public Double getRain14d() { return rain14d; }
    public void setRain14d(Double rain14d) { this.rain14d = rain14d; }

    public Double getRain30d() { return rain30d; }
    public void setRain30d(Double rain30d) { this.rain30d = rain30d; }

    public Double getDryDays7d() { return dryDays7d; }
    public void setDryDays7d(Double dryDays7d) { this.dryDays7d = dryDays7d; }

    public Double getDryDays14d() { return dryDays14d; }
    public void setDryDays14d(Double dryDays14d) { this.dryDays14d = dryDays14d; }
}
