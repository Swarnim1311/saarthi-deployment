package com.saarthi.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/policy-ingestion")
public class PolicyIngestionController {

    private final PolicyIngestionService policyIngestionService;
    private final boolean enabled;
    private final String adminToken;

    public PolicyIngestionController(
            PolicyIngestionService policyIngestionService,
            @Value("${saarthi.policy.ingestion-enabled:false}") boolean enabled,
            @Value("${saarthi.policy.admin-token:}") String adminToken) {
        this.policyIngestionService = policyIngestionService;
        this.enabled = enabled;
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runIngestion(
            @RequestHeader(value = "X-Saarthi-Admin-Token", required = false) String token) {
        if (!enabled || adminToken.isEmpty()
                || token == null || !adminToken.equals(token.trim())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "status", "forbidden",
                    "message", "Policy ingestion is disabled or not authorized."));
        }

        int saved = policyIngestionService.ingestPolicies();

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "saved", saved));
    }
}
