package com.saarthi.policy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * One safe initial policy refresh after startup.
 *
 * <p>H2 is in-memory, so a fresh restart starts with an empty policy table.
 * This runner fires a single background refresh so the first
 * {@code GET /api/policy-alerts} can already serve official records without
 * anyone pressing Refresh. It never blocks startup (daemon thread), never
 * retries in a loop (no hammering), never duplicates (the refresh dedups on
 * the official PRID), and a dead external source only logs — the application
 * still starts normally and previously fetched rows are untouched.
 */
@Component
public class PolicyStartupRefresh implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PolicyStartupRefresh.class);

    private final PolicyRefreshService refreshService;
    private final boolean enabled;

    public PolicyStartupRefresh(
            PolicyRefreshService refreshService,
            @Value("${saarthi.policy.startup-refresh-enabled:true}") boolean enabled) {
        this.refreshService = refreshService;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Policy startup refresh disabled; policies load on first manual refresh.");
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                Map<String, Object> outcome = refreshService.refresh();
                log.info("Policy startup refresh finished: status={} saved={} total={}",
                        outcome.get("status"), outcome.get("saved"), outcome.get("total"));
            } catch (Exception e) {
                log.warn("Policy startup refresh failed ({}); app continues with saved policies: {}",
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }, "saarthi-policy-startup-refresh");
        worker.setDaemon(true);
        worker.start();
    }
}
