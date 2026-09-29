package com.saarthi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class PanchayatOfficialRegistrationRequest {

    @NotBlank
    @Size(max = 120)
    private String fullName;

    @NotBlank
    @Size(max = 80)
    private String username;

    @NotNull
    private Long panchayatId;

    @NotBlank
    @Size(min = 8, max = 100)
    private String password;

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Long getPanchayatId() {
        return panchayatId;
    }

    public void setPanchayatId(Long panchayatId) {
        this.panchayatId = panchayatId;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
    
}