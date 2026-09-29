package com.saarthi.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
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

    @PostConstruct
    public void load() {
        try {
            latest = readJson("forecast/latest_forecast.json");
            blocksDoc = readJson("forecast/blocks.json");
            try (InputStream in = new ClassPathResource("forecast/sangrur_blocks.geojson").getInputStream()) {
                geoJson = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load validated forecast package from classpath:/forecast/. "
                    + "Copy data/processed/application/{latest_forecast.json,blocks.json,sangrur_blocks.geojson} "
                    + "into src/main/resources/forecast/. Cause: " + e.getMessage(), e);
        }
        validate();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJson(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return mapper.readValue(in, new TypeReference<Map<String, Object>>() {});
        }
    }

    @SuppressWarnings("unchecked")
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
