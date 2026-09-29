package com.saarthi.model;

import java.time.LocalDate;

public class PolicyAlertResponse {

    private Long id;
    private String title;
    private String summary;
    private String source;
    private String sourceUrl;
    private LocalDate publishedDate;
    private String category;
    private String applicableState;
    private String applicableDistrict;
    private String eligibleFarmers;
    private String benefit;
    private LocalDate applicationDeadline;
    private String applicationUrl;
    private String verificationStatus;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
}
