package com.saarthi.farmer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Farmer profile, seeded from the teammate's JSON store
 * ({@code SAARTHI-VOICE/backend/data/farmers.json}) as a starting point.
 *
 * <p>Only agronomic identity is carried: id, name, crop, crop location,
 * district, state, plus optional land size and sowing date. No standalone
 * Node server, no file store — an in-memory Spring repository backs the
 * {@code /api/farmers} endpoints.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Farmer {

    private String farmerId;
    private String farmerName;
    private String crop;
    private String cropLocation;
    private String district;
    private String state;
    private String landSize;
    private String sowingDate;
    private String updatedAt;

    public Farmer() {
    }

    public Farmer(String farmerId, String farmerName, String crop, String cropLocation,
            String district, String state, String landSize, String sowingDate,
            String updatedAt) {
        this.farmerId = farmerId;
        this.farmerName = farmerName;
        this.crop = crop;
        this.cropLocation = cropLocation;
        this.district = district;
        this.state = state;
        this.landSize = landSize;
        this.sowingDate = sowingDate;
        this.updatedAt = updatedAt;
    }

    public String getFarmerId() {
        return farmerId;
    }

    public void setFarmerId(String farmerId) {
        this.farmerId = farmerId;
    }

    public String getFarmerName() {
        return farmerName;
    }

    public void setFarmerName(String farmerName) {
        this.farmerName = farmerName;
    }

    public String getCrop() {
        return crop;
    }

    public void setCrop(String crop) {
        this.crop = crop;
    }

    public String getCropLocation() {
        return cropLocation;
    }

    public void setCropLocation(String cropLocation) {
        this.cropLocation = cropLocation;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getLandSize() {
        return landSize;
    }

    public void setLandSize(String landSize) {
        this.landSize = landSize;
    }

    public String getSowingDate() {
        return sowingDate;
    }

    public void setSowingDate(String sowingDate) {
        this.sowingDate = sowingDate;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * Agronomic-only summary for the chat context. Personal details beyond the
     * crop and location are deliberately excluded so the model — and the
     * {@code contextUsed} echo — never carry unnecessary PII.
     */
    public Map<String, Object> toChatContext(boolean contextUsed) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (farmerId != null) m.put("farmerId", farmerId);
        if (crop != null) m.put("crop", crop);
        if (cropLocation != null) m.put("cropLocation", cropLocation);
        if (district != null) m.put("district", district);
        if (state != null) m.put("state", state);
        m.put("contextUsed", contextUsed);
        return m;
    }
}
