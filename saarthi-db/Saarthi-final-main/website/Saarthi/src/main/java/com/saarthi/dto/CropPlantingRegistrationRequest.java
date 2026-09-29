package com.saarthi.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class CropPlantingRegistrationRequest {

    @NotBlank
    @Size(max = 120)
    private String cropName;

    @Size(max = 120)
    private String variety;

    @NotBlank
    @Pattern(regexp = "KHARIF|RABI|ZAID")
    private String season;

    @NotNull
    @Min(2000)
    @Max(2100)
    private Integer cropYear;

    private LocalDate sowingDate;

    private LocalDate harvestDate;

    @AssertTrue(message = "harvestDate must not precede sowingDate")
    public boolean isHarvestDateValid() {
        return harvestDate == null
                || sowingDate == null
                || !harvestDate.isBefore(sowingDate);
    }

    public String getCropName() {
        return cropName;
    }

    public void setCropName(String cropName) {
        this.cropName = cropName;
    }

    public String getVariety() {
        return variety;
    }

    public void setVariety(String variety) {
        this.variety = variety;
    }

    public String getSeason() {
        return season;
    }

    public void setSeason(String season) {
        this.season = season;
    }

    public Integer getCropYear() {
        return cropYear;
    }

    public void setCropYear(Integer cropYear) {
        this.cropYear = cropYear;
    }

    public LocalDate getSowingDate() {
        return sowingDate;
    }

    public void setSowingDate(LocalDate sowingDate) {
        this.sowingDate = sowingDate;
    }

    public LocalDate getHarvestDate() {
        return harvestDate;
    }

    public void setHarvestDate(LocalDate harvestDate) {
        this.harvestDate = harvestDate;
    }
}