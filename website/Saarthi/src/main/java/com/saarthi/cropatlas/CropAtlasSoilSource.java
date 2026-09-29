package com.saarthi.cropatlas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saarthi.risks.SoilContext;
import com.saarthi.soil.SoilGridsWcsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Measured soil context for the CropAtlas fingerprint.
 *
 * <p>Two paths, both fail-soft and per-property honest:
 * <ol>
 *   <li>{@link SoilGridsWcsClient} — the official SoilGrids WCS subset service
 *       ({@code maps.isric.org}), 0–5 cm median (Q0.5) at the block coordinate.
 *       Works for any registry block. Each of the six properties (pH, sand,
 *       silt, clay, SOC, CEC) succeeds or stays {@code null} independently, so
 *       one failing coverage never hides the properties that did serve.</li>
 *   <li>The bundled {@code risk/soil_context.json} block means (Phase 4.0
 *       SoilGrids 0–5 cm aggregation, 10447 px/block) fill the properties WCS
 *       could not serve, for the six legacy blocks that ship them — so soil
 *       context still renders when the network is unavailable. The same file
 *       already backs {@link SoilContext}. The bundle carries no CEC, so CEC
 *       from a bundled fallback stays {@code null}, never borrowed.</li>
 * </ol>
 *
 * <p>No texture class is derived: applying a texture taxonomy is an
 * interpretation CropAtlas does not make. Clay, sand and silt are reported as
 * measured fractions and the UI shows them as a composition bar.
 *
 * <p>Never fabricates: unavailable soil yields
 * {@code available:false} with all values {@code null}.
 */
@Component
public class CropAtlasSoilSource {

    private static final Logger log = LoggerFactory.getLogger(CropAtlasSoilSource.class);
    static final String BUNDLED_RESOURCE = "risk/soil_context.json";
    static final String DEPTH_NOTE = "0–5 cm";

    /** Bundled per-block means: block name → (clay, sand, silt, soc, pH). */
    private final Map<String, double[]> bundled = new ConcurrentHashMap<>();
    private volatile SoilGridsWcsClient soilWcs;

    @Autowired(required = false)
    public void setSoilWcs(SoilGridsWcsClient soilWcs) {
        this.soilWcs = soilWcs;
    }

    public CropAtlasSoilSource() {
        this(BUNDLED_RESOURCE);
    }

    /** Test seam: load the bundled table from an alternate classpath resource. */
    CropAtlasSoilSource(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode blocks = new ObjectMapper().readTree(in).path("blocks");
            if (blocks.isObject()) {
                blocks.fields().forEachRemaining(e -> {
                    JsonNode v = e.getValue();
                    double[] row = {
                            opt(v, "clay_g_kg"), opt(v, "sand_g_kg"), opt(v, "silt_g_kg"),
                            opt(v, "soc_g_kg"), opt(v, "ph")
                    };
                    bundled.put(e.getKey().trim(), row);
                });
            }
        } catch (Exception e) {
            log.warn("CropAtlas bundled soil context unavailable ({}); soil will be requested "
                    + "per block", e.getMessage());
        }
    }

    /** Missing JSON numbers stay NaN so they are never treated as real values. */
    private static double opt(JsonNode v, String field) {
        JsonNode n = v.path(field);
        if (n.isMissingNode() || n.isNull() || !n.isNumber()) return Double.NaN;
        return n.asDouble();
    }

    /**
     * Fingerprint soil for one block. {@code lat}/{@code lon} drive the WCS
     * coordinate lookup; the bundled means fill only the properties WCS could
     * not serve, for blocks that ship them. Every value comes from ISRIC or is
     * {@code null} — per-property fail-soft, never cross-block substitution.
     */
    public EnvironmentalFingerprint.Soil soilFor(String centroidBlockName, Double lat, Double lon) {
        SoilGridsWcsClient.WcsSoil wcs = null;
        SoilGridsWcsClient client = soilWcs;
        if (client != null && lat != null && lon != null) {
            try {
                SoilGridsWcsClient.WcsSoil got = client.lookup(lat, lon);
                if (got != null && got.available()) wcs = got;
            } catch (RuntimeException e) {
                log.warn("CropAtlas SoilGrids WCS lookup failed ({}); trying bundled block means",
                        e.getMessage());
            }
        }
        double[] row = centroidBlockName == null ? null : bundled.get(centroidBlockName.trim());

        Double clay = wcs == null ? null : wcs.clayGkg();
        Double sand = wcs == null ? null : wcs.sandGkg();
        Double silt = wcs == null ? null : wcs.siltGkg();
        Double soc = wcs == null ? null : wcs.socGkg();
        Double ph = wcs == null ? null : wcs.ph();
        Double cec = wcs == null ? null : wcs.cecCmolKg();
        boolean wcsCached = wcs != null && wcs.allCached();
        boolean supplemented = false;
        if (row != null) {
            // Bundled means backfill WCS gaps only — and the bundle has no CEC,
            // so a missing CEC is never papered over.
            if (clay == null) { clay = nz(row[0]); supplemented |= clay != null; }
            if (sand == null) { sand = nz(row[1]); supplemented |= sand != null; }
            if (silt == null) { silt = nz(row[2]); supplemented |= silt != null; }
            if (soc == null) { soc = nz(row[3]); supplemented |= soc != null; }
            if (ph == null) { ph = nz(row[4]); supplemented |= ph != null; }
        }
        if (clay == null && sand == null && silt == null && soc == null && ph == null
                && cec == null) {
            return new EnvironmentalFingerprint.Soil(false, null, null, null, null, null, null,
                    false, null,
                    "Soil data unavailable for this block", DEPTH_NOTE);
        }
        String source;
        if (wcs != null && !supplemented) {
            source = wcs.source();
        } else if (wcs != null) {
            source = wcs.source() + "; properties WCS could not serve use the bundled"
                    + " 0–5 cm block means (Phase 4.0) for " + centroidBlockName;
        } else {
            source = String.format(Locale.ROOT,
                    "SoilGrids 0–5 cm block means (bundled, Phase 4.0) for %s", centroidBlockName);
        }
        return new EnvironmentalFingerprint.Soil(true, clay, sand, silt, soc, ph, cec,
                wcsCached && !supplemented, null, source, DEPTH_NOTE);
    }

    private static Double nz(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    /** True when bundled block means exist for this block (no network needed). */
    public boolean hasBundledMeans(String blockName) {
        return blockName != null && bundled.containsKey(blockName.trim());
    }

    /** Honest per-dimension provenance entry for the sources panel. */
    public static Map<String, Object> source(String id, String title, String publisher,
            String url, String provenance, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("publisher", publisher);
        m.put("url", url);
        m.put("provenance", provenance);
        m.put("note", note);
        return m;
    }
}
