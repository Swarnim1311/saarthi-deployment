package com.saarthi.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "policy_alerts",
    indexes = {
        @Index(name = "idx_policy_published_date", columnList = "published_date"),
        @Index(name = "idx_policy_source", columnList = "source")
    }
)
public class PolicyAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(length = 4000)
    private String summary;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(name = "source_url", nullable = false, length = 2000)
    private String sourceUrl;

    @Column(name = "published_date")
    private LocalDate publishedDate;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "applicable_state", length = 100)
    private String applicableState;

    @Column(name = "applicable_district", length = 100)
    private String applicableDistrict;

    @Column(name = "eligible_farmers", length = 1000)
    private String eligibleFarmers;

    @Column(length = 2000)
    private String benefit;

    @Column(name = "application_deadline")
    private LocalDate applicationDeadline;

    @Column(name = "application_url", length = 2000)
    private String applicationUrl;

    @Column(name = "verification_status", nullable = false, length = 50)
    private String verificationStatus = "PENDING";

    @Column(name = "approved", nullable = false)
    private boolean approved = false;

    @Column(name = "external_id", unique = true, length = 1000)
    private String externalId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public LocalDate getPublishedDate() {
        return publishedDate;
    }

    public void setPublishedDate(LocalDate publishedDate) {
        this.publishedDate = publishedDate;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getApplicableState() {
        return applicableState;
    }

    public void setApplicableState(String applicableState) {
        this.applicableState = applicableState;
    }

    public String getApplicableDistrict() {
        return applicableDistrict;
    }

    public void setApplicableDistrict(String applicableDistrict) {
        this.applicableDistrict = applicableDistrict;
    }

    public String getEligibleFarmers() {
        return eligibleFarmers;
    }

    public void setEligibleFarmers(String eligibleFarmers) {
        this.eligibleFarmers = eligibleFarmers;
    }

    public String getBenefit() {
        return benefit;
    }

    public void setBenefit(String benefit) {
        this.benefit = benefit;
    }

    public LocalDate getApplicationDeadline() {
        return applicationDeadline;
    }

    public void setApplicationDeadline(LocalDate applicationDeadline) {
        this.applicationDeadline = applicationDeadline;
    }

    public String getApplicationUrl() {
        return applicationUrl;
    }

    public void setApplicationUrl(String applicationUrl) {
        this.applicationUrl = applicationUrl;
    }

    public String getVerificationStatus() {
        return verificationStatus;
    }

    public void setVerificationStatus(String verificationStatus) {
        this.verificationStatus = verificationStatus;
    }

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
