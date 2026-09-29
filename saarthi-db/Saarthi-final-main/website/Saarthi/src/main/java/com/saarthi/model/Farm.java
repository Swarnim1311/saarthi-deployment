package com.saarthi.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * A farmer's agricultural land plot. Coordinates are optional and always
 * official/system-derived (block default, map pick, GPS) — never required
 * from the farmer.
 */
@Entity
@Table(name = "farm")
@Check(name = "chk_farm_area", constraints = "area_acres IS NULL OR area_acres > 0")
@Check(name = "chk_farm_lat", constraints = "latitude IS NULL OR (latitude >= -90 AND latitude <= 90)")
@Check(name = "chk_farm_lon", constraints = "longitude IS NULL OR (longitude >= -180 AND longitude <= 180)")
@Check(name = "chk_farm_latlon_pair",
        constraints = "((latitude IS NULL AND longitude IS NULL) "
                + "OR (latitude IS NOT NULL AND longitude IS NOT NULL))")
public class Farm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "farmer_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_farm_farmer"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Farmer farmer;

    /** Official-friendly label, e.g. {@code Farm 1}. */
    @Size(max = 80)
    @Column(name = "plot_label", length = 80)
    private String plotLabel;

    @Column(name = "area_acres")
    private Double areaAcres;

    @Size(max = 120)
    @Column(length = 120)
    private String village;

    @NotBlank
    @Size(max = 80)
    @Column(name = "block_name", nullable = false, length = 80)
    private String blockName;

    /** Display denormalization; the FK truth stays {@code farmer.panchayat}. */
    @Size(max = 120)
    @Column(name = "panchayat_name", length = 120)
    private String panchayatName;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Pattern(regexp = "PANCHAYAT_DEFAULT|BLOCK_CENTROID|MAP_PICKED|GPS_CAPTURE")
    @Size(max = 40)
    @Column(name = "location_method", length = 40)
    private String locationMethod;

    @Size(max = 60)
    @Column(name = "soil_texture", length = 60)
    private String soilTexture;

    @Size(max = 60)
    @Column(name = "irrigation_source", length = 60)
    private String irrigationSource;

    /**
     * Owned planting history. Removing the farm removes its plantings
     * (mirrors {@code ON DELETE CASCADE} on {@code crop_planting.farm_id}
     * in {@code V1__farmer_schema.sql}).
     */
    @OneToMany(mappedBy = "farm", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CropPlanting> cropPlantings = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    @AssertTrue(message = "areaAcres must be greater than 0 when provided")
    public boolean isAreaValid() {
        return areaAcres == null || areaAcres > 0;
    }

    @AssertTrue(message = "latitude and longitude must be set together")
    public boolean isLocationPairValid() {
        return (latitude == null) == (longitude == null);
    }

    @AssertTrue(message = "latitude must be between -90 and 90")
    public boolean isLatitudeValid() {
        return latitude == null || (latitude >= -90 && latitude <= 90);
    }

    @AssertTrue(message = "longitude must be between -180 and 180")
    public boolean isLongitudeValid() {
        return longitude == null || (longitude >= -180 && longitude <= 180);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Farmer getFarmer() {
        return farmer;
    }

    public void setFarmer(Farmer farmer) {
        this.farmer = farmer;
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

    public String getBlockName() {
        return blockName;
    }

    public void setBlockName(String blockName) {
        this.blockName = blockName;
    }

    public String getPanchayatName() {
        return panchayatName;
    }

    public void setPanchayatName(String panchayatName) {
        this.panchayatName = panchayatName;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getLocationMethod() {
        return locationMethod;
    }

    public void setLocationMethod(String locationMethod) {
        this.locationMethod = locationMethod;
    }

    public String getSoilTexture() {
        return soilTexture;
    }

    public void setSoilTexture(String soilTexture) {
        this.soilTexture = soilTexture;
    }

    public List<CropPlanting> getCropPlantings() {
        return cropPlantings;
    }

    public void setCropPlantings(List<CropPlanting> cropPlantings) {
        this.cropPlantings = cropPlantings;
    }

    public void addCropPlanting(CropPlanting planting) {
        cropPlantings.add(planting);
        planting.setFarm(this);
    }

    public void removeCropPlanting(CropPlanting planting) {
        cropPlantings.remove(planting);
        planting.setFarm(null);
    }

    public String getIrrigationSource() {
        return irrigationSource;
    }

    public void setIrrigationSource(String irrigationSource) {
        this.irrigationSource = irrigationSource;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
