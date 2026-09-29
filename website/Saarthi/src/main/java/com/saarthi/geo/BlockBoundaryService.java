package com.saarthi.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Display-only block boundaries for the India-wide risk map.
 *
 * <p>Loads {@code classpath:/geography/block_boundaries.json} once at
 * startup: simplified bharatlas LGD polygons keyed by the
 * {@code state_code:district_code:block_code} triple (same source that fed
 * {@code scripts/build_india_blocks.py}; simplification is display-only and
 * never touches forecast or risk math).
 *
 * <p>Serves ONE block geometry per lookup — the browser never downloads the
 * national file. Unknown triples (geocoded rows, legacy Bhuvan-only ids)
 * return empty so the caller can fall back to an honest centroid marker;
 * nothing is invented or substituted (no district-as-block, no Sangrur
 * fallback).
 */
@Service
public class BlockBoundaryService {

    static final String RESOURCE = "geography/block_boundaries.json";

    private final Map<String, JsonNode> boundaries = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public BlockBoundaryService() {
    }

    /** Test seam: build from an alternate classpath resource. */
    BlockBoundaryService(String resource) {
        loadFromResource(resource);
    }

    @PostConstruct
    void load() {
        if (!boundaries.isEmpty()) return; // test-seam constructor already loaded
        loadFromResource(RESOURCE);
    }

    private void loadFromResource(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode doc = mapper.readTree(in);
            JsonNode map = doc.get("boundaries");
            if (map == null || !map.isObject()) {
                throw new IllegalStateException("Block boundary file has no 'boundaries' object: "
                        + resource);
            }
            Iterator<Map.Entry<String, JsonNode>> fields = map.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                JsonNode geom = e.getValue();
                if (geom != null && geom.isObject()
                        && geom.has("type") && geom.has("coordinates")) {
                    boundaries.put(e.getKey(), geom);
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load block boundaries from classpath:/"
                    + resource + ". Regenerate with scripts/extract_block_boundaries.py. Cause: "
                    + e.getMessage(), e);
        }
    }

    static String key(String stateCode, String districtCode, String blockCode) {
        return stateCode.trim() + ":" + districtCode.trim() + ":" + blockCode.trim();
    }

    /** Exact-triple boundary lookup. Empty when no compiled geometry exists. */
    public Optional<JsonNode> findBoundary(String stateCode, String districtCode,
            String blockCode) {
        if (stateCode == null || districtCode == null || blockCode == null
                || stateCode.isBlank() || districtCode.isBlank() || blockCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                boundaries.get(key(stateCode, districtCode, blockCode)));
    }

    /** Number of compiled boundaries (read-only view for health/tests). */
    public int count() {
        return boundaries.size();
    }

    /** Metadata string for diagnostics (source note, never geometry). */
    public String describe() {
        return "BlockBoundaryService{boundaries=" + boundaries.size()
                + ", source=bharatlas LGD Blocks (2024) simplified display polygons}";
    }
}
