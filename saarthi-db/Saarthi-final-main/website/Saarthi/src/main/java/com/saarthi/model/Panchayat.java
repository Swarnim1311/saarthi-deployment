package com.saarthi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

/**
 * A village Panchayat served by a Saarthi official.
 *
 * <p>Names are unique only within a block (e.g. {@code Ubhawal} exists in
 * both Sangrur and Sunam blocks), so the unique constraint is
 * {@code (block_name, name)}, never {@code name} alone.
 */
@Entity
@Table(name = "panchayat",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_panchayat_block_name", columnNames = {"block_name", "name"}))
public class Panchayat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String name;

    @NotBlank
    @Size(max = 80)
    @Column(name = "block_name", nullable = false, length = 80)
    private String blockName;

    @Size(max = 80)
    @Column(name = "district_name", nullable = false, length = 80)
    private String districtName = "Sangrur";

    @Size(max = 80)
    @Column(name = "state_name", nullable = false, length = 80)
    private String stateName = "Punjab";

    /** e.g. {@code bhuvan_b_272}; nullable to allow future non-Sangrur rows. */
    @Size(max = 32)
    @Column(name = "bhuvan_block_id", length = 32)
    private String bhuvanBlockId;

    /** LGD codes from {@code geography/blocks.csv}; nullable in this phase. */
    @Size(max = 16)
    @Column(name = "state_code", length = 16)
    private String stateCode;

    @Size(max = 16)
    @Column(name = "district_code", length = 16)
    private String districtCode;

    @Size(max = 16)
    @Column(name = "block_code", length = 16)
    private String blockCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBlockName() {
        return blockName;
    }

    public void setBlockName(String blockName) {
        this.blockName = blockName;
    }

    public String getDistrictName() {
        return districtName;
    }

    public void setDistrictName(String districtName) {
        this.districtName = districtName;
    }

    public String getStateName() {
        return stateName;
    }

    public void setStateName(String stateName) {
        this.stateName = stateName;
    }

    public String getBhuvanBlockId() {
        return bhuvanBlockId;
    }

    public void setBhuvanBlockId(String bhuvanBlockId) {
        this.bhuvanBlockId = bhuvanBlockId;
    }

    public String getStateCode() {
        return stateCode;
    }

    public void setStateCode(String stateCode) {
        this.stateCode = stateCode;
    }

    public String getDistrictCode() {
        return districtCode;
    }

    public void setDistrictCode(String districtCode) {
        this.districtCode = districtCode;
    }

    public String getBlockCode() {
        return blockCode;
    }

    public void setBlockCode(String blockCode) {
        this.blockCode = blockCode;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
