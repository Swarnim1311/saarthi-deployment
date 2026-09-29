package com.saarthi.service;

import com.saarthi.model.PolicyAlert;
import com.saarthi.model.PolicyAlertResponse;
import com.saarthi.repository.PolicyAlertRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PolicyAlertService {

    private final PolicyAlertRepository repository;

    public PolicyAlertService(PolicyAlertRepository repository) {
        this.repository = repository;
    }

    public List<PolicyAlertResponse> getLatestPolicies() {
        return repository
                .findTop50ByApprovedTrueOrderByPublishedDateDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<PolicyAlertResponse> getAllPolicies() {
        return repository
                .findTop50ByOrderByPublishedDateDesc()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private PolicyAlertResponse toResponse(PolicyAlert p) {
        PolicyAlertResponse r = new PolicyAlertResponse();

        r.setId(p.getId());
        r.setTitle(p.getTitle());
        r.setSummary(p.getSummary());
        r.setSource(p.getSource());
        r.setSourceUrl(p.getSourceUrl());
        r.setPublishedDate(p.getPublishedDate());
        r.setCategory(p.getCategory());
        r.setApplicableState(p.getApplicableState());
        r.setApplicableDistrict(p.getApplicableDistrict());
        r.setEligibleFarmers(p.getEligibleFarmers());
        r.setBenefit(p.getBenefit());
        r.setApplicationDeadline(p.getApplicationDeadline());
        r.setApplicationUrl(p.getApplicationUrl());
        r.setVerificationStatus(p.getVerificationStatus());

        return r;
    }
}
