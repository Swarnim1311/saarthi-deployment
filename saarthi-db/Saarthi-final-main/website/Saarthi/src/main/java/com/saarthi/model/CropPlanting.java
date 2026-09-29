package com.saarthi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * A crop grown on a farm during one season/year. Append-only history: a
 * new season is a new row, never an overwrite. No {@code isCurrent} flag —
 * the current planting is determined by a repository query ordering by
 * {@code crop_year} / {@code sowing_date} / {@code created_at}.
 */
@Entity
@Table(name = "crop_planting",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_crop_farm_season_year_name",
                columnNames = {"farm_id", "season", "crop_year", "crop_name"}))
@Check(name = "chk_crop_season",
        constraints = "season IN ('KHARIF', 'RABI', 'ZAID')")
@Check(name = "chk_crop_year",
        constraints = "crop_year BETWEEN 2000 AND 2100")
@Check(name = "chk_crop_dates",
        constraints = "harvest_date IS NULL OR sowing_date IS NULL OR harvest_date >= sowing_date")
public class CropPlanting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "farm_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_crop_farm"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Farm farm;

    /** Free text validated against {@code crop_reference.json} in service. */
    @NotBlank
    @Size(max = 120)
    @Column(name = "crop_name", nullable = false, length = 120)
    private String cropName;

    @Size(max = 120)
    @Column(length = 120)
    private String variety;

    @NotBlank
    @Pattern(regexp = "KHARIF|RABI|ZAID")
    @Size(max = 16)
    @Column(nullable = false, length = 16)
    private String season;

    @NotNull
    @Min(2000)
    @Max(2100)
    @Column(name = "crop_year", nullable = false)
    private Integer cropYear;

    @Column(name = "sowing_date")
    private LocalDate sowingDate;

    @Column(name = "harvest_date")
    private LocalDate harvestDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    @AssertTrue(message = "harvestDate must not precede sowingDate")
    public boolean isHarvestDateValid() {
        return harvestDate == null || sowingDate == null || !harvestDate.isBefore(sowingDate);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Farm getFarm() {
        return farm;
    }

    public void setFarm(Farm farm) {
        this.farm = farm;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
