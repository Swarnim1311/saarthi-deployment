package com.saarthi.voice;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bhashini (ULCA) configuration. Server-side only.
 *
 * <p>Resolution order for each credential: environment variable first, then
 * {@code application.properties} ({@code saarthi.bhashini.*}). Spring resolves
 * {@code ${BHASHINI_USER_ID:}} style placeholders from the environment, so both
 * mechanisms work without code changes.
 *
 * <p>Defaults mirror the teammate's Node backend
 * ({@code SAARTHI-VOICE/backend/bhashini.js}): the shared MeitY pipeline id and
 * the {@code getModelsPipeline} discovery endpoint. Only placeholders are ever
 * committed; real values live in the environment or an untracked
 * {@code application.properties} override.
 */
@Component
public class BhashiniProperties {

    @Value("${BHASHINI_USER_ID:${saarthi.bhashini.user-id:}}")
    private String userId = "";

    @Value("${BHASHINI_API_KEY:${saarthi.bhashini.api-key:}}")
    private String apiKey = "";

    @Value("${saarthi.bhashini.pipeline-id:64392f96daac500b55c543cd}")
    private String pipelineId = "64392f96daac500b55c543cd";

    @Value("${saarthi.bhashini.pipeline-url:https://meity-auth.ulcacontrib.org/ulca/apis/v0/model/getModelsPipeline}")
    private String pipelineUrl =
            "https://meity-auth.ulcacontrib.org/ulca/apis/v0/model/getModelsPipeline";

    @Value("${saarthi.bhashini.timeout-seconds:15}")
    private int timeoutSeconds = 15;

    @Value("${saarthi.bhashini.cache-ttl-minutes:60}")
    private long cacheTtlMinutes = 60;

    /** Test seam: explicit values without Spring. */
    BhashiniProperties(String userId, String apiKey, String pipelineId,
            String pipelineUrl, int timeoutSeconds, long cacheTtlMinutes) {
        this.userId = userId;
        this.apiKey = apiKey;
        this.pipelineId = pipelineId;
        this.pipelineUrl = pipelineUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.cacheTtlMinutes = cacheTtlMinutes;
    }

    public BhashiniProperties() {
    }

    public String userId() {
        return blankToNull(userId);
    }

    public String apiKey() {
        return blankToNull(apiKey);
    }

    /** True only when both credentials are present; otherwise voice is fail-soft. */
    public boolean isConfigured() {
        return userId() != null && apiKey() != null;
    }

    public String pipelineId() {
        return (pipelineId == null || pipelineId.isBlank())
                ? "64392f96daac500b55c543cd" : pipelineId.trim();
    }

    public String pipelineUrl() {
        return (pipelineUrl == null || pipelineUrl.isBlank())
                ? "https://meity-auth.ulcacontrib.org/ulca/apis/v0/model/getModelsPipeline"
                : pipelineUrl.trim();
    }

    public int timeoutSeconds() {
        return timeoutSeconds <= 0 ? 15 : timeoutSeconds;
    }

    public long cacheTtlMinutes() {
        return cacheTtlMinutes <= 0 ? 60 : cacheTtlMinutes;
    }

    private static String blankToNull(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }
}
