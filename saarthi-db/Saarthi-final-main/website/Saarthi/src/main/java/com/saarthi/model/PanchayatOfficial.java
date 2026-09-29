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
 * A Panchayat employee / authorized official who registers and maintains
 * farmer information.
 *
 * <p>No password in this phase. {@code username} exists now only as the
 * stable unique identifier the future authentication phase will need.
 */
@Entity
@Table(name = "panchayat_official")
public class PanchayatOfficial {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 120)
    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @NotBlank
    @Size(max = 80)
    @Column(nullable = false, unique = true, length = 80)
    private String username;

    @NotBlank
    @Size(max = 40)
    @Column(nullable = false, length = 40)
    private String role = "PANCHAYAT_OFFICIAL";

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "panchayat_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_official_panchayat"))
    private Panchayat panchayat;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * Farmers currently assigned to this official for monitoring.
     * Inverse view only: deliberately NO cascade and NO orphan removal —
     * this is an assignment of responsibility, not lifecycle ownership.
     * Deleting or deactivating an official must never delete farmers
     * (the foreign key is {@code ON DELETE SET NULL}).
     */
    @OneToMany(mappedBy = "assignedOfficial")
    private List<Farmer> assignedFarmers = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

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
    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Panchayat getPanchayat() {
        return panchayat;
    }

    public void setPanchayat(Panchayat panchayat) {
        this.panchayat = panchayat;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public List<Farmer> getAssignedFarmers() {
        return assignedFarmers;
    }

    public void setAssignedFarmers(List<Farmer> assignedFarmers) {
        this.assignedFarmers = assignedFarmers;
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
