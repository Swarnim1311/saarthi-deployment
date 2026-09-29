package com.saarthi.cropatlas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Comparable agricultural environments" matcher.
 *
 * <p><b>Honest by construction.</b> The reference regions come from
 * {@code classpath:/cropatlas/global-regions.json}, which ships with an
 * intentionally EMPTY {@code regions} array because this repository holds no
 * verified global agricultural-region dataset. The matching logic is fully
 * implemented, so adding sourced regions to that file is the only step needed to
 * make the panel return results; until then the API reports
 * {@code available:false} with the expansion message. No region is invented.
 *
 * <p>Framing is deliberately "comparable agricultural environment", never
 * "this block is identical to X": the comparison is on climate and soil ranges
 * only, and each match carries its own sources.
 */
@Component
public class GlobalRegionMatcher {

    private static final Logger log = LoggerFactory.getLogger(GlobalRegionMatcher.class);
    static final String RESOURCE = "cropatlas/global-regions.json";

    public record Region(String regionId, String displayName, String country,
                         Double tempMinC, Double tempMaxC, Double annualRainMm,
                         Double clayGkg, Double socGkg, Double ph,
                         List<Map<String, Object>> sources) {}

    private final List<Region> regions;

    public GlobalRegionMatcher() {
        this(RESOURCE);
    }

    /** Test seam: load from an alternate classpath resource. */
    GlobalRegionMatcher(String resource) {
        List<Region> parsed = new ArrayList<>();
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            JsonNode arr = root.path("regions");
            if (arr.isArray()) {
                for (JsonNode r : arr) {
                    String id = r.path("region_id").asText("").trim();
                    if (id.isEmpty()) continue;
                    JsonNode cl = r.path("climate");
                    JsonNode so = r.path("soil");
                    List<Map<String, Object>> srcs = new ArrayList<>();
                    for (JsonNode s : r.path("sources")) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", s.path("id").asText(null));
                        m.put("title", s.path("title").asText(null));
                        m.put("publisher", s.path("publisher").asText(null));
                        m.put("url", s.path("url").asText(null));
                        srcs.add(m);
                    }
                    parsed.add(new Region(id,
                            r.path("display_name").asText(id),
                            r.path("country").asText(null),
                            num(cl, "temp_min_c"), num(cl, "temp_max_c"), num(cl, "annual_rain_mm"),
                            num(so, "clay_g_kg"), num(so, "soc_g_kg"), num(so, "ph"),
                            List.copyOf(srcs)));
                }
            }
        } catch (Exception e) {
            log.warn("CropAtlas global regions unavailable ({}); region panel will report "
                    + "unavailable", e.getMessage());
        }
        this.regions = List.copyOf(parsed);
    }

    private static Double num(JsonNode parent, String field) {
        if (parent == null) return null;
        JsonNode n = parent.path(field);
        if (!n.isNumber()) return null;
        double v = n.asDouble();
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    public boolean hasRegions() { return !regions.isEmpty(); }

    public List<Region> regions() { return regions; }

    /**
     * Comparable-environment panel for one fingerprint. With no sourced regions
     * this returns {@code available:false} plus the expansion message — never an
     * invented list.
     */
    public Map<String, Object> compare(EnvironmentalFingerprint fp) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("framing", "Comparable agricultural environments");
        out.put("disclaimer", "A comparable environment is not an identical one. CropAtlas never "
                + "claims a block is the same as a named region, and never asserts a global "
                + "match without a cited source.");
        if (regions.isEmpty()) {
            out.put("available", false);
            out.put("regions", List.of());
            out.put("message", "Global reference matching will expand as additional verified "
                    + "agricultural-region data is added.");
            out.put("reason", "No verified global agricultural-region dataset is present in this "
                    + "repository. The matching architecture is implemented and will activate when "
                    + "sourced regions are added to cropatlas/global-regions.json.");
            return out;
        }
        List<Map<String, Object>> matches = new ArrayList<>();
        for (Region r : regions) {
            double overlap = 0;
            int dims = 0;
            List<String> shared = new ArrayList<>();
            if (fp.climate() != null && r.tempMaxC() != null && fp.climate().tempMaxMeanC() != null) {
                dims++;
                double a = fp.climate().tempMaxMeanC(), b = r.tempMaxC();
                if (Math.abs(a - b) <= 5.0) { overlap += 1; shared.add("mean daily maximum temperature"); }
            }
            if (fp.soil() != null && fp.soil().available() && r.clayGkg() != null
                    && fp.soil().clayGkg() != null) {
                dims++;
                if (Math.abs(fp.soil().clayGkg() - r.clayGkg()) <= 80.0) { overlap += 1; shared.add("clay content"); }
            }
            if (fp.soil() != null && fp.soil().available() && r.ph() != null && fp.soil().ph() != null) {
                dims++;
                if (Math.abs(fp.soil().ph() - r.ph()) <= 0.8) { overlap += 1; shared.add("soil pH"); }
            }
            if (dims == 0) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("region_id", r.regionId());
            m.put("display_name", r.displayName());
            m.put("country", r.country());
            m.put("shared_dimensions", shared);
            m.put("dimensions_compared", dims);
            m.put("dimensions_overlapping", overlap);
            m.put("sources", r.sources());
            matches.add(m);
        }
        matches.sort((a, b) -> {
            int byOverlap = Integer.compare((int) b.get("dimensions_overlapping"),
                    (int) a.get("dimensions_overlapping"));
            if (byOverlap != 0) return byOverlap;
            return String.valueOf(a.get("region_id")).compareTo(String.valueOf(b.get("region_id")));
        });
        out.put("available", !matches.isEmpty());
        out.put("regions", matches);
        if (matches.isEmpty()) {
            out.put("message", "No sourced reference region overlapped this block's fingerprint.");
        }
        return out;
    }
}
