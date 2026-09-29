package com.saarthi.risks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saarthi.soil.SoilGridsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Display-only block soil context ({@code classpath:/risk/soil_context.json},
 * derived from the Phase 4.0 SoilGrids 0–5 cm aggregation).
 *
 * <p>Block-to-block spread is small (VERIFIED Phase 4.0), so soil is a
 * context line only: it never affects severity, confidence, tie-breaks, or
 * weights. No texture-class interpretation is performed — values are
 * reported factually with units. Fail-soft: {@code null} when unavailable.
 */
@Component
public class SoilContext {

    private static final Logger log = LoggerFactory.getLogger(SoilContext.class);
    static final String RESOURCE = "risk/soil_context.json";

    private final Map<String, String> lineByBlock = new ConcurrentHashMap<>();

    /**
     * Optional dynamic SoilGrids point lookup (setter-injected so the bundled
     * Sangrur path — and plain {@code new SoilContext()} in tests — works
     * with or without it).
     */
    private volatile SoilGridsClient soilGrids;

    @Autowired(required = false)
    public void setSoilGrids(SoilGridsClient soilGrids) {
        this.soilGrids = soilGrids;
    }

    public SoilContext() {
        this(RESOURCE);
    }

    /** Test seam: load from an alternate classpath resource. */
    SoilContext(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode blocks = new ObjectMapper().readTree(in).path("blocks");
            for (java.util.Iterator<Map.Entry<String, JsonNode>> it = blocks.fields();
                    it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                lineByBlock.put(e.getKey(), String.format(Locale.ROOT,
                        "clay %.0f g/kg · sand %.0f g/kg · silt %.0f g/kg · "
                        + "organic carbon %.1f g/kg · pH %.2f "
                        + "(SoilGrids 0–5 cm block means, context only)",
                        v.path("clay_g_kg").asDouble(),
                        v.path("sand_g_kg").asDouble(),
                        v.path("silt_g_kg").asDouble(),
                        v.path("soc_g_kg").asDouble(),
                        v.path("ph").asDouble()));
            }
        } catch (Exception e) {
            log.warn("Soil context unavailable ({}); soil line will be omitted", e.getMessage());
        }
    }

    /** Factual soil line for a block, or {@code null} when unavailable. */
    public String describe(String block) {
        return lineByBlock.get(block);
    }

    /**
     * Factual soil line for an arbitrary centroid via the SoilGrids point
     * API (dynamic registry path). Fail-soft: {@code null} when the lookup
     * is unconfigured or unavailable — never fabricated, never a Sangrur
     * value substituted for another block.
     */
    public String lineForCoords(double lat, double lon) {
        SoilGridsClient client = soilGrids;
        if (client == null) return null;
        SoilGridsClient.SoilProfile p = client.lookup(lat, lon);
        if (!p.available()) return null;
        return String.format(Locale.ROOT,
                "clay %s · sand %s · silt %s · organic carbon %s · pH %s "
                        + "(SoilGrids 0–5 cm point query, context only)",
                fmt(p.clayGkg(), "g/kg"), fmt(p.sandGkg(), "g/kg"),
                fmt(p.siltGkg(), "g/kg"), fmt(p.socGkg(), "g/kg"), fmt(p.ph(), ""));
    }

    private static String fmt(Double v, String unit) {
        if (v == null) return "No data";
        String s = String.format(Locale.ROOT, v < 100 ? "%.1f" : "%.0f", v);
        return unit.isEmpty() ? s : s + " " + unit;
    }
}
