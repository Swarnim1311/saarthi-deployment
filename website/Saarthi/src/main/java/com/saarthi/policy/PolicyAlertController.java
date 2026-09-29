package com.saarthi.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/policy-alerts")
public class PolicyAlertController {

    private final PolicyAlertService policyAlertService;
    private final String adminToken;

    public PolicyAlertController(
            PolicyAlertService policyAlertService,
            @Value("${saarthi.policy.admin-token:}") String adminToken) {
        this.policyAlertService = policyAlertService;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    @GetMapping
    public ResponseEntity<List<PolicyAlertResponse>> getPolicies() {
        return ResponseEntity.ok(policyAlertService.getLatestPolicies());
    }

    @GetMapping("/all")
    public ResponseEntity<?> getAllPolicies(
            @RequestHeader(value = "X-Saarthi-Admin-Token", required = false) String token) {
        if (adminToken.isEmpty() || token == null || !adminToken.equals(token.trim())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of(
                            "error", "forbidden",
                            "message", "Unapproved policy data is restricted."));
        }
        return ResponseEntity.ok(policyAlertService.getAllPolicies());
    }
}
