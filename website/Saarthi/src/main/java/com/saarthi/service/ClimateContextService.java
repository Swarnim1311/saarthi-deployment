package com.saarthi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    /**
     * Optional external climate directory. When {@code saarthi.climate.path} is set,
     * {@code climate_context_summary.json} is read from that directory so refreshed
     * NB07/NB08 context can be served without rebuilding the jar. Otherwise the
     * packaged classpath copy is used. Documented in application.properties.
     */
    @Value("${saarthi.climate.path:}")
    private String externalClimatePath;

    @PostConstruct
    public void load() {
        Path ext = externalFile();
        try {
            if (ext != null) {
                try (InputStream in = Files.newInputStream(ext)) {
                    summary = mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
                }
            } else {
                try (InputStream in = new ClassPathResource("climate/climate_context_summary.json").getInputStream()) {
                    summary = mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
                }
            }
            loaded = true;
            loadError = null;
        } catch (Exception e) {
            loaded = false;
            loadError = e.getMessage();
            summary = new LinkedHashMap<>();
        }
    }

    private Path externalFile() {
        if (externalClimatePath != null && !externalClimatePath.isBlank()) {
            return Paths.get(externalClimatePath.trim()).resolve("climate_context_summary.json");
        }
        return null;
    }

    /**
     * P0: re-read the climate context package without restarting the JVM
     * ({@code POST /api/climate-context/reload}). Fail-SOFT like the initial
     * load: on a missing/invalid package the service reports
     * {@code available=false} and the rainfall forecast keeps working.
     * DMI/IOD values pass through untouched (numeric JSON numbers, not strings).
     */
    public synchronized Map<String, Object> reload() {
        load();
        return getSummary();
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
