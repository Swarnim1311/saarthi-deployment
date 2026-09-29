package com.saarthi.cropatlas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Crop-side requirements for CropAtlas, loaded from the versioned
 * {@code classpath:/cropatlas/crop-requirements.json}.
 *
 * <p><b>Provenance contract.</b> Every requirement value in that file is
 * carried over unchanged from the repository's source-cited agronomy reference
 * ({@code agronomy/crop_reference.json}: PAU Package of Practices Kharif/Rabi +
 * ICAR-IARI). This class introduces no new agronomic numbers. Requirement
 * dimensions the consulted reference does not state (per-crop temperature
 * range, soil pH range, texture class, quantitative irrigation mm) are recorded
 * in the file's {@code not_available_dimensions} map and are surfaced to callers
 * as genuinely unavailable, so the engine reports "Insufficient data" instead
 * of substituting a guess.
 *
 * <p>Ordinal maps ({@code water_need_ordinal}, {@code tolerance_ordinal}) rank
 * the reference's own qualitative classes. They carry no unit and are used only
 * to compare a crop's demand class against an observed block class.
 *
 * <p>Fail-soft: a missing or malformed resource yields an empty catalogue
 * (and a warning), never an exception and never invented crops.
 */
@Component
public class CropRequirements {

    private static final Logger log = LoggerFactory.getLogger(CropRequirements.class);
    static final String RESOURCE = "cropatlas/crop-requirements.json";

    /**
     * One crop's requirements. {@code soilPhRange} / {@code temperatureRangeC}
     * are deliberately absent as fields: the reference states neither, so the
     * model cannot express them at all. That is intentional — it makes it
     * impossible for the engine to score them accidentally.
     */
    public record Requirement(
            String cropId,
            String displayName,
            List<String> aliases,
            String group,
            String season,
            String sowStartMmDd,
            String sowEndMmDd,
            String sowWindowBasis,
            Integer durationDays,
            String durationBasis,
            String waterNeedClass,
            int waterNeedOrdinal,
            String waterloggingTolerance,
            int waterloggingOrdinal,
            String droughtSensitivity,
            int droughtOrdinal,
            String irrigationRuleHint,
            List<String> referenceIds,
            List<String> sourceIds) {

        /** Inclusive day-of-year bounds of the reference sowing window. */
        public int sowStartDoy() { return mmDdToDoy(sowStartMmDd); }
        public int sowEndDoy() { return mmDdToDoy(sowEndMmDd); }

        /** Calendar length of the sowing window in days (inclusive). */
        public int sowWindowLengthDays() {
            int s = sowStartDoy(), e = sowEndDoy();
            int len = e - s + 1;
            return len > 0 ? len : len + 365;
        }
    }

    private final List<Requirement> requirements;
    private final Map<String, String> notAvailableDimensions;
    private final String methodVersion;
    private final String derivedFrom;

    public CropRequirements() {
        this(RESOURCE);
    }

    /** Test seam: load from an alternate classpath resource. */
    CropRequirements(String resource) {
        List<Requirement> reqs = new ArrayList<>();
        Map<String, String> gaps = new LinkedHashMap<>();
        String version = "unknown";
        String derived = "unknown";
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            version = root.path("method_version").asText("unknown");
            derived = root.path("derived_from").asText("unknown");
            JsonNode na = root.path("not_available_dimensions");
            if (na.isObject()) {
                na.fields().forEachRemaining(e -> gaps.put(e.getKey(), e.getValue().asText()));
            }
            JsonNode needOrd = root.path("water_need_ordinal");
            JsonNode tolOrd = root.path("tolerance_ordinal");
            JsonNode crops = root.path("crops");
            if (crops.isArray()) {
                for (JsonNode c : crops) {
                    Requirement r = parseCrop(c, needOrd, tolOrd);
                    if (r != null) reqs.add(r);
                }
            }
        } catch (Exception e) {
            log.warn("CropAtlas crop requirements unavailable ({}); no candidates will be served",
                    e.getMessage());
        }
        reqs.sort((a, b) -> a.cropId().compareTo(b.cropId()));
        this.requirements = Collections.unmodifiableList(reqs);
        this.notAvailableDimensions = Collections.unmodifiableMap(gaps);
        this.methodVersion = version;
        this.derivedFrom = derived;
    }

    private Requirement parseCrop(JsonNode c, JsonNode needOrd, JsonNode tolOrd) {
        String id = c.path("crop_id").asText("").trim();
        String name = c.path("display_name").asText("").trim();
        if (id.isEmpty() || name.isEmpty()) return null;
        JsonNode w = c.path("sow_window");
        String start = w.path("start").asText("").trim();
        String end = w.path("end").asText("").trim();
        if (!isMmDd(start) || !isMmDd(end)) {
            log.warn("CropAtlas crop '{}' skipped: unusable sow window", id);
            return null;
        }
        int need = ordinal(needOrd, c.path("water_need_class").asText(""), -1);
        if (need < 0) {
            log.warn("CropAtlas crop '{}' skipped: unranked water_need_class", id);
            return null;
        }
        Integer duration = c.hasNonNull("duration_days") ? c.path("duration_days").asInt() : null;
        return new Requirement(
                id,
                name,
                strings(c.path("aliases")),
                c.path("group").asText("unclassified"),
                c.path("season").asText("unspecified"),
                start,
                end,
                w.path("basis").asText("sowing window"),
                duration,
                c.path("duration_basis").asText(null),
                c.path("water_need_class").asText("unspecified"),
                need,
                c.path("waterlogging_tolerance").asText("unspecified"),
                ordinal(tolOrd, c.path("waterlogging_tolerance").asText(""), -1),
                c.path("drought_sensitivity").asText("unspecified"),
                ordinal(tolOrd, c.path("drought_sensitivity").asText(""), -1),
                c.path("irrigation_rule_hint").asText(null),
                strings(c.path("reference_ids")),
                strings(c.path("source_ids")));
    }

    /** All requirements, ordered by crop id (deterministic). */
    public List<Requirement> all() { return requirements; }

    public boolean isEmpty() { return requirements.isEmpty(); }

    public String methodVersion() { return methodVersion; }

    public String derivedFrom() { return derivedFrom; }

    /** Requirement dimensions the consulted reference does not state. */
    public Map<String, String> notAvailableDimensions() { return notAvailableDimensions; }

    public Requirement byId(String cropId) {
        for (Requirement r : requirements) {
            if (r.cropId().equalsIgnoreCase(cropId)) return r;
        }
        return null;
    }

    // ---- helpers ----

    private static int ordinal(JsonNode map, String key, int fallback) {
        if (map == null || key == null || key.isBlank()) return fallback;
        int v = map.path(key.trim().toLowerCase(Locale.ROOT)).asInt(fallback);
        return v;
    }

    private static List<String> strings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                String s = n.asText("").trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return Collections.unmodifiableList(out);
    }

    static boolean isMmDd(String v) {
        if (v == null || v.length() != 5 || v.charAt(2) != '-') return false;
        try {
            int mo = Integer.parseInt(v.substring(0, 2));
            int d = Integer.parseInt(v.substring(3, 5));
            return mo >= 1 && mo <= 12 && d >= 1 && d <= 31;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Non-leap day-of-year for an {@code MM-DD} string, using the same wheel
     * convention as {@code ClimatologyContext.wheelDoy} (Feb-29 → 60, else +1
     * from Mar-01) so CropAtlas and the W3/W4 normals agree on calendar position.
     */
    static int mmDdToDoy(String mmDd) {
        if (!isMmDd(mmDd)) return -1;
        int mo = Integer.parseInt(mmDd.substring(0, 2));
        int d = Integer.parseInt(mmDd.substring(3, 5));
        if (mo == 2 && d == 29) return 60;
        LocalDate base = LocalDate.of(2021, mo, d);
        int d0 = base.getDayOfYear();
        return d0 + (d0 >= 60 ? 1 : 0);
    }
}
