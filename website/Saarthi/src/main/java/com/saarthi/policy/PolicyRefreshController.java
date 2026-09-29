package com.saarthi.policy;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public refresh for the Government Policies page.
 *
 * <p>{@code POST /api/policy-alerts/refresh} re-reads the configured official
 * feeds (PIB) and stores unseen items as verified official alerts. It accepts
 * no content from the caller — there is no way to inject a title, URL or date
 * through this endpoint — so it needs no admin token and changes no CORS
 * policy. The admin {@code /api/policy-ingestion} path (arbitrary/manual
 * rows, still {@code PENDING}) is untouched.
 */
@RestController
@RequestMapping("/api/policy-alerts")
public class PolicyRefreshController {

    private final PolicyRefreshService refreshService;

    public PolicyRefreshController(PolicyRefreshService refreshService) {
        this.refreshService = refreshService;
    }

    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        return ResponseEntity.ok(refreshService.refresh());
    }
}
