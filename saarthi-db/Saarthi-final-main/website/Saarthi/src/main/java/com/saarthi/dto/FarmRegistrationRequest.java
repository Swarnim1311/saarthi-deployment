package com.saarthi.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

public class FarmRegistrationRequest {

    @Size(max = 80)
    private String plotLabel;

    private Double areaAcres;

    @Size(max = 120)
    private String village;

    @Size(max = 60)
    private String soilTexture;

    @Size(max = 60)
    private String irrigationSource;

    @AssertTrue(message = "areaAcres must be greater than 0 when provided")
    public boolean isAreaValid() {
        return areaAcres == null || areaAcres > 0;
    }

    public String getPlotLabel() {
        return plotLabel;
    }

    public void setPlotLabel(String plotLabel) {
        this.plotLabel = plotLabel;
    }

    public Double getAreaAcres() {
        return areaAcres;
    }

    public void setAreaAcres(Double areaAcres) {
        this.areaAcres = areaAcres;
    }

    public String getVillage() {
        return village;
    }

    public void setVillage(String village) {
        this.village = village;
    }

    public String getSoilTexture() {
        return soilTexture;
    }

    public void setSoilTexture(String soilTexture) {
        this.soilTexture = soilTexture;
    }

    public String getIrrigationSource() {
        return irrigationSource;
    }

    public void setIrrigationSource(String irrigationSource) {
        this.irrigationSource = irrigationSource;
    }
}