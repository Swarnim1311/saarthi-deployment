package com.saarthi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Authoritative source of the validated 7-day rainfall outlook.
 *
 * Loads the Notebook 06 application package from the classpath
 * ({@code forecast/latest_forecast.json}, {@code forecast/blocks.json},
 * {@code forecast/sangrur_blocks.geojson}) once at startup and serves it.
 * No forecasting is performed here — numerical results come from the
 * validated Python pipeline (Raw CHIRPS-GEFS, model_type = raw_gefs).
 */
@Service
public class RealForecastService {

    public static final List<String> BLOCKS = Collections.unmodifiableList(Arrays.asList(
            "Dhuri", "Lehra", "Malerkotla", "Moonak", "Sangrur", "Sunam"));

    public static final String MODEL_TYPE = "raw_gefs";
    public static final String METHOD_NAME = "Raw CHIRPS-GEFS";
    public static final int HORIZON_DAYS = 7;

    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> latest;
    private Map<String, Object> blocksDoc;
    private String geoJson;
    private List<Map<String, Object>> forecastBlocks;
    private final Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> byName = new LinkedHashMap<>();

    /**
     * Optional external forecast directory. When {@code saarthi.forecast.path} is set
     * (e.g. {@code -Dsaarthi.forecast.path=.../data/processed/application}), the service
     * reads {@code latest_forecast.json}/{@code blocks.json}/{@code sangrur_blocks.geojson}
     * from that directory so a refreshed NB06 package can be served without rebuilding
     * the jar. When unset/blank, the packaged classpath copies are used.
     */
    @Value("${saarthi.forecast.path:}")
    private String externalForecastPath;

    /**
     * Forecasts older than this many calendar days (issue_date vs today) are reported
     * as stale, as is any forecast whose valid_to has passed. Same rule as
     * {@code src/utils/forecast_freshness.py} and {@code docs/api/api-contract.md}.
     */
    public static final int STALE_AFTER_DAYS = 2;

    @PostConstruct
    public synchronized void load() {
        byId.clear();
        byName.clear();
        try {
            Path ext = externalDir();
            if (ext != null) {
                latest = readExternalJson(ext.resolve("latest_forecast.json"));
                blocksDoc = readExternalJson(ext.resolve("blocks.json"));
                geoJson = Files.readString(ext.resolve("sangrur_blocks.geojson"), StandardCharsets.UTF_8);
            } else {
                latest = readJson("forecast/latest_forecast.json");
                blocksDoc = readJson("forecast/blocks.json");
                try (InputStream in = new ClassPathResource("forecast/sangrur_blocks.geojson").getInputStream()) {
                    geoJson = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load validated forecast package"
                    + (externalDir() != null ? " from saarthi.forecast.path=" + externalDir() + ". "
                        : " from classpath:/forecast/. ")
                    + "Copy data/processed/application/{latest_forecast.json,blocks.json,sangrur_blocks.geojson} "
                    + "into src/main/resources/forecast/ (or point saarthi.forecast.path at it). Cause: " + e.getMessage(), e);
        }
        validate();
    }

    /**
     * Re-reads the forecast package (external directory if configured, else classpath).
     * Allows serving a refreshed NB06 package without restarting the JVM:
     * {@code POST /api/forecast/reload}. Never fabricates data — a missing/invalid
     * package throws and the previously loaded forecast keeps being served.
     */
    public synchronized Map<String, Object> reload() {
        Map<String, Object> prevLatest = latest;
        Map<String, Object> prevBlocks = blocksDoc;
        String prevGeo = geoJson;
        LinkedHashMap<String, Map<String, Object>> prevById = new LinkedHashMap<>(byId);
        LinkedHashMap<String, Map<String, Object>> prevByName = new LinkedHashMap<>(byName);
        try {
            load();
        } catch (RuntimeException e) {
            latest = prevLatest;
            blocksDoc = prevBlocks;
            geoJson = prevGeo;
            byId.clear();
            byId.putAll(prevById);
            byName.clear();
            byName.putAll(prevByName);
            throw e;
        }
        return getFreshness();
    }

    private Path externalDir() {
        if (externalForecastPath != null && !externalForecastPath.isBlank()) {
            return Paths.get(externalForecastPath.trim());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
    }

    private Map<String, Object> readExternalJson(Path path) throws Exception {
        try (InputStream in = Files.newInputStream(path)) {
            return mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
    }

    /**
     * Explicit freshness metadata for the currently loaded forecast. A stale result
     * is a valid state (latest AVAILABLE bundle may be older than today) — callers
     * must expose it, never hide it. Same rule as
     * {@code src/utils/forecast_freshness.py}: stale = expired OR age_days &gt; 2.
     */
    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> getFreshness() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (latest == null) {
            out.put("available", false);
            out.put("reason", "No forecast package loaded");
            return out;
        }
        Map<String, Object> forecast = (Map<String, Object>) latest.get("forecast");
        if (forecast == null) {
            out.put("available", false);
            out.put("reason", "Loaded package has no 'forecast' object");
            return out;
        }
        String issue = Objects.toString(forecast.get("issue_date"), null);
        String validFrom = Objects.toString(forecast.get("valid_from"), null);
        String validTo = Objects.toString(forecast.get("valid_to"), null);
        String generatedAt = Objects.toString(forecast.get("generated_at"), null);
        LocalDate today = LocalDate.now();
        long ageDays = -1;
        boolean expired = false;
        boolean stale = true;
        try {
            LocalDate issueDate = LocalDate.parse(issue);
            LocalDate validToDate = LocalDate.parse(validTo);
            ageDays = ChronoUnit.DAYS.between(issueDate, today);
            expired = today.isAfter(validToDate);
            stale = expired || ageDays > STALE_AFTER_DAYS;
        } catch (Exception e) {
            out.put("available", false);
            out.put("reason", "Unparsable forecast dates: " + e.getMessage());
            return out;
        }
        out.put("available", true);
        out.put("issue_date", issue);
        out.put("valid_from", validFrom);
        out.put("valid_to", validTo);
        out.put("generated_at", generatedAt);
        out.put("source", "Raw CHIRPS-GEFS (model_type=raw_gefs)");
        out.put("stale", stale);
        out.put("expired", expired);
        out.put("age_days", ageDays);
        out.put("expires_at", validTo);
        return out;
    }

    private void validate() {
        Map<String, Object> forecast = (Map<String, Object>) latest.get("forecast");
        if (forecast == null) throw new IllegalStateException("latest_forecast.json: missing 'forecast'");
        forecastBlocks = (List<Map<String, Object>>) forecast.get("blocks");
        if (forecastBlocks == null || forecastBlocks.size() != 6) {
            throw new IllegalStateException("latest_forecast.json: expected exactly 6 blocks");
        }
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> b : forecastBlocks) {
            String name = Objects.toString(b.get("block_name"), null);
            String id = Objects.toString(b.get("block_id"), null);
            if (name == null || id == null) throw new IllegalStateException("forecast block missing id/name");
            names.add(name);
            List<Map<String, Object>> daily = (List<Map<String, Object>>) b.get("daily_forecast");
            if (daily == null || daily.size() != HORIZON_DAYS) {
                throw new IllegalStateException("block " + name + ": expected 7 daily values");
            }
            Map<String, Object> prob = (Map<String, Object>) b.get("probability");
            double sum = ((Number) prob.get("low")).doubleValue()
                    + ((Number) prob.get("normal")).doubleValue()
                    + ((Number) prob.get("high")).doubleValue();
            if (Math.abs(sum - 1.0) > 1e-3) throw new IllegalStateException("block " + name + ": probabilities do not sum to 1");
            byId.put(id.toLowerCase(Locale.ROOT), b);
            byName.put(name.toLowerCase(Locale.ROOT), b);
        }
        if (!names.equals(new LinkedHashSet<>(BLOCKS))) {
            throw new IllegalStateException("forecast blocks " + names + " != 6 legacy Bhuvan blocks");
        }
        Map<String, Object> system = (Map<String, Object>) latest.get("system");
        if (!MODEL_TYPE.equals(system.get("model_type"))) {
            throw new IllegalStateException("unexpected model_type: " + system.get("model_type"));
        }
    }

    public Map<String, Object> getLatest() { return latest; }

    public Map<String, Object> getBlocksDoc() { return blocksDoc; }

    public String getGeoJson() { return geoJson; }

    public List<Map<String, Object>> getForecastBlocks() { return forecastBlocks; }

    /** Lookup by Bhuvan block_id (e.g. bhuvan_b_270) or block name (case-insensitive). Empty if unknown. */
    public Optional<Map<String, Object>> findBlock(String idOrName) {
        if (idOrName == null) return Optional.empty();
        String key = idOrName.trim().toLowerCase(Locale.ROOT);
        if (byId.containsKey(key)) return Optional.of(byId.get(key));
        if (byName.containsKey(key)) return Optional.of(byName.get(key));
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getSummary() {
        return (Map<String, Object>) latest.get("summary");
    }

    public Map<String, Object> getSystem() {
        @SuppressWarnings("unchecked")
        Map<String, Object> system = (Map<String, Object>) latest.get("system");
        return system;
    }

    /** Exception for unknown block identifiers — mapped to HTTP 404, never a silent fallback. */
    public static class BlockNotFoundException extends RuntimeException {
        public BlockNotFoundException(String idOrName) {
            super("Unknown block '" + idOrName + "'. Valid blocks: " + BLOCKS
                    + " (or Bhuvan IDs bhuvan_b_269..bhuvan_b_274).");
        }
    }
}
