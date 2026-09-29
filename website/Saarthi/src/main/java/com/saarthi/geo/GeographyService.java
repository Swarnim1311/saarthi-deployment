package com.saarthi.geo;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bundled State → District → Block registry with centroid coordinates.
 *
 * <p>Loads {@code classpath:/geography/blocks.csv} once at startup. Adding
 * more rows later requires NO Java changes — just append CSV lines (see the
 * file's provenance header for how codes/coordinates must be verified).
 *
 * <p>This registry is ADDITIVE: the legacy Sangrur/CHIRPS-GEFS research path
 * ({@code RealForecastService}, its 6 Bhuvan polygons and fixtures) is
 * untouched. Registry centroids feed the dynamic
 * {@code /api/weather/forecast/by-coords} path as single sample points.
 */
@Service
public class GeographyService {

    static final String RESOURCE = "geography/blocks.csv";

    /** One registry row: hierarchy + LGD (or documented legacy) codes + centroid. */
    public record BlockRef(
            @JsonProperty("state_code") String stateCode,
            @JsonProperty("state_name") String stateName,
            @JsonProperty("district_code") String districtCode,
            @JsonProperty("district_name") String districtName,
            @JsonProperty("block_code") String blockCode,
            @JsonProperty("block_name") String blockName,
            @JsonProperty("latitude") double latitude,
            @JsonProperty("longitude") double longitude,
            @JsonProperty("location_method") String locationMethod) {}

    /** Optional external registry file (same CSV schema). Blank = packaged copy. */
    @Value("${saarthi.geography.path:}")
    private String externalGeographyPath;

    private final List<BlockRef> blocks = new ArrayList<>();

    public GeographyService() {
    }

    /** Test seam: build from an alternate classpath resource. */
    GeographyService(String resource) {
        loadFromResource(resource);
    }

    @PostConstruct
    void load() {
        if (!blocks.isEmpty()) return; // test-seam constructor already loaded
        if (externalGeographyPath != null && !externalGeographyPath.isBlank()) {
            try {
                String text = java.nio.file.Files.readString(
                        java.nio.file.Paths.get(externalGeographyPath.trim()),
                        StandardCharsets.UTF_8);
                List<BlockRef> parsed = parse(new StringReader(text));
                blocks.addAll(parsed);
                return;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load geography registry from "
                        + "saarthi.geography.path=" + externalGeographyPath
                        + ". Cause: " + e.getMessage(), e);
            }
        }
        loadFromResource(RESOURCE);
    }

    private void loadFromResource(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream();
                Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            blocks.addAll(parse(r));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load geography registry from classpath:/"
                    + resource + ". Copy website/Saarthi/src/main/resources/geography/blocks.csv "
                    + "into place (or point saarthi.geography.path at it). Cause: "
                    + e.getMessage(), e);
        }
    }

    /**
     * Parse registry CSV. Skips {@code #} comment and blank lines; quoted
     * fields supported. Fails LOUDLY (never a partial registry): missing
     * columns, bad numbers, out-of-range coordinates, blank keys or duplicate
     * {@code block_code} values all throw.
     */
    static List<BlockRef> parse(Reader reader) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            throw new IllegalStateException("Geography registry is empty (no header + rows)");
        }
        List<String> header = splitCsv(lines.get(0));
        List<String> required = List.of("state_code", "state_name", "district_code",
                "district_name", "block_code", "block_name", "latitude", "longitude",
                "location_method");
        for (String col : required) {
            if (!header.contains(col)) {
                throw new IllegalStateException(
                        "Geography registry header missing required column '" + col
                                + "'; header=" + header);
            }
        }
        List<BlockRef> out = new ArrayList<>();
        Map<String, Integer> seenCodes = new LinkedHashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> cols = splitCsv(lines.get(i));
            if (cols.size() != header.size()) {
                throw new IllegalStateException("Geography registry line " + (i + 1)
                        + ": expected " + header.size() + " columns, found " + cols.size());
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) row.put(header.get(c), cols.get(c).trim());
            double lat, lon;
            try {
                lat = Double.parseDouble(row.get("latitude"));
                lon = Double.parseDouble(row.get("longitude"));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Geography registry line " + (i + 1)
                        + ": bad latitude/longitude", e);
            }
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                throw new IllegalStateException("Geography registry line " + (i + 1)
                        + ": coordinates out of range (" + lat + ", " + lon + ")");
            }
            for (String key : List.of("state_code", "state_name", "district_code",
                    "district_name", "block_code", "block_name", "location_method")) {
                if (row.get(key) == null || row.get(key).isBlank()) {
                    throw new IllegalStateException("Geography registry line " + (i + 1)
                            + ": blank '" + key + "'");
                }
            }
            String code = row.get("block_code");
            // Identity is the (state, district, block) triple: LGD block codes
            // were reused across district reorganizations (e.g. Nagaland and
            // Telangana splits), so the same code can label distinct blocks
            // in different districts. The cascade and search APIs are
            // hierarchical/triple-based; single-code lookup returns the first
            // registry row (see the triple overload for exact resolution).
            String triple = row.get("state_code") + ":" + row.get("district_code")
                    + ":" + code;
            if (seenCodes.containsKey(triple)) {
                throw new IllegalStateException("Geography registry: duplicate block '"
                        + triple + "' on lines " + seenCodes.get(triple) + " and " + (i + 1));
            }
            seenCodes.put(triple, i + 1);
            out.add(new BlockRef(row.get("state_code"), row.get("state_name"),
                    row.get("district_code"), row.get("district_name"),
                    code, row.get("block_name"), lat, lon,
                    row.get("location_method")));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Geography registry has a header but no data rows");
        }
        return out;
    }

    /** Minimal CSV split supporting double-quoted fields and escaped quotes. */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** Distinct states in registry order. */
    public List<Map<String, String>> states() {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (BlockRef b : blocks) ordered.putIfAbsent(b.stateCode(), b.stateName());
        List<Map<String, String>> out = new ArrayList<>();
        for (Map.Entry<String, String> e : ordered.entrySet()) {
            out.add(Map.of("state_code", e.getKey(), "state_name", e.getValue()));
        }
        return out;
    }

    /** Distinct districts of a state, in registry order. Empty when unknown. */
    public List<Map<String, String>> districts(String stateCode) {
        Map<String, Map<String, String>> ordered = new LinkedHashMap<>();
        for (BlockRef b : blocks) {
            if (b.stateCode().equals(stateCode)) {
                ordered.putIfAbsent(b.districtCode(), Map.of(
                        "district_code", b.districtCode(),
                        "district_name", b.districtName(),
                        "state_code", b.stateCode(),
                        "state_name", b.stateName()));
            }
        }
        return new ArrayList<>(ordered.values());
    }

    /** Blocks of a district, in registry order. Empty when unknown. */
    public List<BlockRef> blocks(String districtCode) {
        List<BlockRef> out = new ArrayList<>();
        for (BlockRef b : blocks) {
            if (b.districtCode().equals(districtCode)) out.add(b);
        }
        return out;
    }

    /**
     * Single block by registry code (first registry-order match). Codes are
     * unique except for LGD reuse across district splits — use the triple
     * overload for exact resolution.
     */
    public Optional<BlockRef> findBlock(String blockCode) {
        if (blockCode == null) return Optional.empty();
        for (BlockRef b : blocks) {
            if (b.blockCode().equals(blockCode.trim())) return Optional.of(b);
        }
        return Optional.empty();
    }

    /** Exact block resolution on the (state, district, block) identity triple. */
    public Optional<BlockRef> findBlock(String stateCode, String districtCode,
            String blockCode) {
        if (stateCode == null || districtCode == null || blockCode == null) {
            return Optional.empty();
        }
        for (BlockRef b : blocks) {
            if (b.stateCode().equals(stateCode.trim())
                    && b.districtCode().equals(districtCode.trim())
                    && b.blockCode().equals(blockCode.trim())) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }

    /**
     * Blocks whose names contain the query (case-insensitive), capped at 20.
     * Used to resolve legacy UI names (e.g. map chips) to registry rows
     * without hardcoding geography in the frontend.
     */
    public List<BlockRef> search(String query) {
        List<BlockRef> out = new ArrayList<>();
        if (query == null || query.isBlank()) return out;
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        for (BlockRef b : blocks) {
            if (b.blockName().toLowerCase(java.util.Locale.ROOT).contains(q)) {
                out.add(b);
                if (out.size() >= 20) break;
            }
        }
        return out;
    }

    /** Full registry (read-only view, registry order). */
    public List<BlockRef> all() {
        return List.copyOf(blocks);
    }
}
