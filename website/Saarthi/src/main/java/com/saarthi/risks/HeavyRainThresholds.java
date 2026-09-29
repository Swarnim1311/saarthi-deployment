package com.saarthi.risks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only access to the train-frozen excess-rainfall thresholds
 * ({@code classpath:/risk/excess_thresholds_v1.json}, byte-identical copy of
 * {@code data/processed/risk/excess_thresholds_v1.json}: excess-v1 fit on
 * 2010–2019 JJAS, never recomputed or tuned here).
 *
 * <p>Only {@code daily_p95} is consumed, and only as the
 * {@code FIELD_HEAVY_RAIN} <b>display-only evidence</b> marker. It never
 * affects severity, confidence, or overall risk — the excess detectors are
 * NO-GO and stay out of production. Fail-soft: any load problem yields
 * {@code null} (heavy evidence unavailable), never an exception.
 */
@Component
public class HeavyRainThresholds {

    private static final Logger log = LoggerFactory.getLogger(HeavyRainThresholds.class);
    static final String RESOURCE = "risk/excess_thresholds_v1.json";

    private final Map<String, Double> p95ByBlock = new ConcurrentHashMap<>();
    private volatile String methodVersion = "excess-v1";
    private volatile String fitPeriod = "2010-2019";

    public HeavyRainThresholds() {
        this(RESOURCE);
    }

    /** Test seam: load from an alternate classpath resource. */
    HeavyRainThresholds(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            methodVersion = root.path("method_version").asText("excess-v1");
            fitPeriod = root.path("fit_period").asText("2010-2019");
            JsonNode blocks = root.path("blocks");
            for (java.util.Iterator<Map.Entry<String, JsonNode>> it = blocks.fields();
                    it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                double v = e.getValue().path("daily_p95").asDouble(Double.NaN);
                if (!Double.isNaN(v)) p95ByBlock.put(e.getKey(), v);
            }
        } catch (Exception e) {
            log.warn("Heavy-rain thresholds unavailable ({}); heavy evidence will be omitted",
                    e.getMessage());
        }
    }

    /** Block JJAS daily p95 in mm, or {@code null} when unavailable. */
    public Double dailyP95(String block) {
        return p95ByBlock.get(block);
    }

    public String methodVersion() {
        return methodVersion;
    }

    public String fitPeriod() {
        return fitPeriod;
    }
}
