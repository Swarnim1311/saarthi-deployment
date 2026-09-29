package com.saarthi.controller;

import com.saarthi.model.PolicyAlertResponse;
import com.saarthi.service.PolicyAlertService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/policy-alerts")
@CrossOrigin(origins = "*")
public class PolicyAlertController {

    private final PolicyAlertService policyAlertService;

    public PolicyAlertController(PolicyAlertService policyAlertService) {
        this.policyAlertService = policyAlertService;
    }

    @GetMapping
    public ResponseEntity<List<PolicyAlertResponse>> getPolicies() {
        return ResponseEntity.ok(
                policyAlertService.getLatestPolicies()
        );
    }

    @GetMapping("/all")
    public ResponseEntity<List<PolicyAlertResponse>> getAllPolicies() {
        return ResponseEntity.ok(
                policyAlertService.getAllPolicies()
        );
    }
}
