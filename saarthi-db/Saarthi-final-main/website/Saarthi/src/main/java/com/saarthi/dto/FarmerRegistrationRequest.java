package com.saarthi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class FarmerRegistrationRequest {

    @NotBlank
    @Size(max = 120)
    private String fullName;

    @Size(max = 20)
    private String phone;

    @Size(max = 120)
    private String village;

    @NotNull
    private Long panchayatId;

    private Long registeredByOfficialId;

    @Size(max = 500)
    private String notes;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getVillage() {
        return village;
    }

    public void setVillage(String village) {
        this.village = village;
    }

    public Long getPanchayatId() {
        return panchayatId;
    }

    public void setPanchayatId(Long panchayatId) {
        this.panchayatId = panchayatId;
    }

    public Long getRegisteredByOfficialId() {
        return registeredByOfficialId;
    }

    public void setRegisteredByOfficialId(Long registeredByOfficialId) {
        this.registeredByOfficialId = registeredByOfficialId;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}