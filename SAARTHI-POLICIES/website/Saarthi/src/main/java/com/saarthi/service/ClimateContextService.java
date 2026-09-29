package com.saarthi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

/**
 * Large-scale climate context (MJO + ENSO + IOD method note).
 *
 * Loads {@code climate/climate_context_summary.json} once at startup.
 * Fail-SOFT: if the package is missing, the service reports
 * {@code available=false} and the rainfall forecast keeps working.
 * Nothing here alters the validated rainfall pipeline.
 */
@Service
public class ClimateContextService {

    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> summary;
    private boolean loaded = false;
    private String loadError;

    @PostConstruct
    public void load() {
        try (InputStream in = new ClassPathResource("climate/climate_context_summary.json").getInputStream()) {
            summary = mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
            loaded = true;
        } catch (Exception e) {
            loaded = false;
            loadError = e.getMessage();
            summary = new LinkedHashMap<>();
        }
    }

    public Map<String, Object> getSummary() {
        if (!loaded) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", false);
            out.put("reason", "Climate context package unavailable"
                    + (loadError != null ? ": " + loadError : ""));
            return out;
        }
        return summary;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMjo() {
        if (!loaded) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", false);
            out.put("reason", "Latest RMM observation unavailable");
            return out;
        }
        Object mjo = summary.get("mjo");
        if (mjo instanceof Map) return (Map<String, Object>) mjo;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", false);
        out.put("reason", "Latest RMM observation unavailable");
        return out;
    }
}
