package com.saarthi.controller;

import com.saarthi.service.policy.PolicyIngestionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/policy-ingestion")
public class PolicyIngestionController {

    private final PolicyIngestionService policyIngestionService;

    public PolicyIngestionController(
            PolicyIngestionService policyIngestionService) {
        this.policyIngestionService = policyIngestionService;
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runIngestion() {

        int saved = policyIngestionService.ingestPolicies();

        return ResponseEntity.ok(
                Map.of(
                        "status", "success",
                        "saved", saved
                )
        );
    }
}