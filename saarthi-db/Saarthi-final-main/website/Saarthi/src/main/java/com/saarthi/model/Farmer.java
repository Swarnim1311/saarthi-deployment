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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A farmer (the person). Farmers may not have smartphones, so only the
 * name and Panchayat are required; phone, village and notes are optional.
 *
 * <p>Minimum personal information by design: no email, no Aadhaar, no
 * address blob.
 */
@Entity
@Table(name = "farmer")
public class Farmer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Globally unique human code, e.g. {@code SAN-SUN-000123}. */
    @NotBlank
    @Size(max = 32)
    @Column(name = "farmer_code", nullable = false, unique = true, length = 32)
    private String farmerCode;

    @NotBlank
    @Size(max = 120)
    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Size(max = 20)
    @Column(length = 20)
    private String phone;

    @Size(max = 120)
    @Column(length = 120)
    private String village;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "panchayat_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_farmer_panchayat"))
    private Panchayat panchayat;

    /**
     * Registration/audit link only. The official does NOT own the farmer
     * record; deleting the official sets this to {@code null}.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "registered_by_official_id",
            foreignKey = @ForeignKey(name = "fk_farmer_registrar"))
    private PanchayatOfficial registeredByOfficial;

    /**
     * The official currently responsible for monitoring this farmer.
     * Nullable: pre-existing farmers start unassigned until the assignment
     * workflow assigns them. Reassignment only changes this column — never
     * creates a new farmer row. This is an assignment of responsibility,
     * not lifecycle ownership: deleting the official sets this to
     * {@code null} and the farmer survives.
     *
     * <p>Distinct from {@link #registeredByOfficial}, which records who
     * originally registered the farmer (audit/history).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_official_id",
            foreignKey = @ForeignKey(name = "fk_farmer_assigned_official"))
    private PanchayatOfficial assignedOfficial;

    @Size(max = 500)
    @Column(length = 500)
    private String notes;

    /**
     * Owned plots. A farm is meaningless without its farmer, so removal of
     * the farmer cascades to the farms (mirrors {@code ON DELETE CASCADE}
     * on {@code farm.farmer_id} in {@code V1__farmer_schema.sql}, which is
     * what the PostgreSQL schema enforces; this keeps the H2 test schema
     * consistent with production).
     */
    @OneToMany(mappedBy = "farmer", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Farm> farms = new ArrayList<>();

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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFarmerCode() {
        return farmerCode;
    }

    public void setFarmerCode(String farmerCode) {
        this.farmerCode = farmerCode;
    }

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

    public Panchayat getPanchayat() {
        return panchayat;
    }

    public void setPanchayat(Panchayat panchayat) {
        this.panchayat = panchayat;
    }

    public PanchayatOfficial getRegisteredByOfficial() {
        return registeredByOfficial;
    }

    public void setRegisteredByOfficial(PanchayatOfficial registeredByOfficial) {
        this.registeredByOfficial = registeredByOfficial;
    }

    public PanchayatOfficial getAssignedOfficial() {
        return assignedOfficial;
    }

    public void setAssignedOfficial(PanchayatOfficial assignedOfficial) {
        this.assignedOfficial = assignedOfficial;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<Farm> getFarms() {
        return farms;
    }

    public void setFarms(List<Farm> farms) {
        this.farms = farms;
    }

    public void addFarm(Farm farm) {
        farms.add(farm);
        farm.setFarmer(this);
    }

    public void removeFarm(Farm farm) {
        farms.remove(farm);
        farm.setFarmer(null);
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
