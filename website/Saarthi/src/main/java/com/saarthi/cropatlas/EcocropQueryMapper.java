package com.saarthi.cropatlas;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Block fingerprint → FAO ECOCROP environment-search parameters.
 *
 * <p><b>Honesty rules (enforced, not advisory).</b> Every parameter below is
 * derived from an actually measured SAARTHI value, or the dimension is left
 * out of the query entirely:
 * <ul>
 *   <li><b>Temperature</b> — the live forecast-horizon extremes
 *       ({@code tempMinLowC} / {@code tempMaxHighC}), rounded <i>outward</i>
 *       to one decimal. This is a 16-day horizon range, NOT an annual normal,
 *       and it is documented as such wherever the query is reported. The
 *       horizon means are deliberately NOT used: a narrow mean band would
 *       silently pretend the block's weather is calmer than observed.</li>
 *   <li><b>Rainfall</b> — a legitimate annual normal ONLY for blocks that ship
 *       deployment day-of-year normals: the summed 365-day normal. Any other
 *       block omits rainfall. The live 7-day/16-day forecast rainfall is
 *       NEVER substituted for annual rainfall, and no normal is ever invented
 *       or borrowed from another block.</li>
 *   <li><b>Soil pH</b> — the fingerprint's SoilGrids/bundled pH when present,
 *       else omitted.</li>
 *   <li><b>Latitude / elevation</b> — the block's registry latitude and the
 *       served elevation when present, else omitted.</li>
 *   <li><b>Texture, depth, fertility, salinity, drainage, light, climate zone,
 *       photoperiod, use</b> — ALWAYS unrestricted. SoilGrids reports measured
 *       fractions and this codebase refuses to derive a texture taxonomy from
 *       them; ECOCROP's categorical codes are never guessed.</li>
 * </ul>
 *
 * <p><b>Fallback strategy.</b> ECOCROP matches by containment, so an
 * over-constrained query can legitimately return zero hits. Callers walk the
 * levels in order; each level only <i>removes</i> a dimension, never widens or
 * edits a measured value:
 * <ol>
 *   <li>all available dimensions</li>
 *   <li>drop elevation (modelled terrain context)</li>
 *   <li>drop latitude (broad geographic filter)</li>
 *   <li>drop soil pH (modelled soil property)</li>
 *   <li>drop annual rainfall (deployment-normal artifact, legacy blocks only)</li>
 *   <li>temperature range only (the core live signal)</li>
 * </ol>
 * A level carrying zero dimensions is never sent. If even the temperature-only
 * query is impossible (no horizon extremes), the block is undiscoverable and
 * reported as such — never padded.
 */
@Component
public class EcocropQueryMapper {

    private static final Logger log = LoggerFactory.getLogger(EcocropQueryMapper.class);

    static final String DIM_TEMPERATURE = "temperature";
    static final String DIM_RAINFALL = "rainfall_annual_normal";
    static final String DIM_PH = "soil_ph";
    static final String DIM_LATITUDE = "latitude";
    static final String DIM_ELEVATION = "elevation";

    /** Dimensions this mapper never sends (no trustworthy mapping exists). */
    static final List<String> NEVER_SENT = List.of(
            "soil_texture", "soil_depth", "soil_fertility", "soil_salinity",
            "soil_drainage", "light_intensity", "climate_zone", "photoperiod",
            "plant_use", "available_field_days");

    /** One query level: params to send plus which dimensions they express. */
    public record EcocropQuery(Map<String, String> params, List<String> dimensionsUsed,
                               List<String> dimensionsOmitted, int level) {}

    /**
     * Ordered query levels for one fingerprint, strongest first. Empty when
     * the fingerprint cannot support even a temperature-only query.
     */
    public List<EcocropQuery> levelsFor(EnvironmentalFingerprint fp, CropAtlasService atlas) {
        Map<String, String> full = new LinkedHashMap<>();
        List<String> omitted = new ArrayList<>();

        String minT = low(fp);
        String maxT = high(fp);
        if (minT != null && maxT != null) {
            full.put("minTemperature", minT);
            full.put("maxTemperature", maxT);
        } else {
            omitted.add(DIM_TEMPERATURE);
        }

        String rain = annualRainfallMm(fp, atlas);
        if (rain != null) {
            full.put("minRainfall", rain);
            full.put("maxRainfall", rain);
        } else {
            omitted.add(DIM_RAINFALL);
        }

        Double ph = fp == null || fp.soil() == null ? null : fp.soil().ph();
        if (finite(ph) && ph >= 0 && ph <= 14) {
            full.put("minSoilPh", fmt(ph));
            full.put("maxSoilPh", fmt(ph));
        } else {
            omitted.add(DIM_PH);
        }

        Double lat = fp == null || fp.location() == null ? null
                : num(fp.location().get("latitude"));
        if (finite(lat) && Math.abs(lat) <= 90) {
            full.put("latitude", fmt(lat));
        } else {
            omitted.add(DIM_LATITUDE);
        }

        Double elev = fp == null ? null : fp.elevationM();
        if (finite(elev) && elev >= -500 && elev <= 9000) {
            full.put("altitude", String.valueOf(Math.round(elev)));
        } else {
            omitted.add(DIM_ELEVATION);
        }

        List<String> present = dimsOf(full);
        List<EcocropQuery> levels = new ArrayList<>();
        // Level 0: everything available. Then shed one dimension at a time in
        // documented order (least trustworthy first, live temperature last).
        // An empty level is never emitted: a dimension-less query would
        // enumerate the database, so an undiscoverable block yields no levels.
        if (full.isEmpty()) return List.of();
        List<String> dropOrder = List.of(DIM_ELEVATION, DIM_LATITUDE, DIM_PH, DIM_RAINFALL);
        Map<String, String> current = new LinkedHashMap<>(full);
        levels.add(query(current, omitted, 0));
        int level = 1;
        for (String dim : dropOrder) {
            if (!present.contains(dim)) continue;
            current = without(current, dim);
            if (current.isEmpty()) break;
            List<String> nowOmitted = new ArrayList<>(omitted);
            nowOmitted.add(dim);
            levels.add(query(current, nowOmitted, level++));
        }
        return List.copyOf(levels);
    }

    private static EcocropQuery query(Map<String, String> params, List<String> omitted, int level) {
        return new EcocropQuery(Map.copyOf(params), dimsOf(params), List.copyOf(omitted), level);
    }

    private static Map<String, String> without(Map<String, String> params, String dim) {
        Map<String, String> out = new LinkedHashMap<>(params);
        switch (dim) {
            case DIM_TEMPERATURE -> { out.remove("minTemperature"); out.remove("maxTemperature"); }
            case DIM_RAINFALL -> { out.remove("minRainfall"); out.remove("maxRainfall"); }
            case DIM_PH -> { out.remove("minSoilPh"); out.remove("maxSoilPh"); }
            case DIM_LATITUDE -> out.remove("latitude");
            case DIM_ELEVATION -> out.remove("altitude");
            default -> { }
        }
        return out;
    }

    private static List<String> dimsOf(Map<String, String> params) {
        List<String> dims = new ArrayList<>();
        if (params.containsKey("minTemperature")) dims.add(DIM_TEMPERATURE);
        if (params.containsKey("minRainfall")) dims.add(DIM_RAINFALL);
        if (params.containsKey("minSoilPh")) dims.add(DIM_PH);
        if (params.containsKey("latitude")) dims.add(DIM_LATITUDE);
        if (params.containsKey("altitude")) dims.add(DIM_ELEVATION);
        return dims;
    }

    // ---- dimension derivation (measured values only) ----

    /** Horizon minimum, rounded DOWN to 0.1 °C. Null when not observed. */
    private static String low(EnvironmentalFingerprint fp) {
        Double v = fp == null || fp.climate() == null ? null : fp.climate().tempMinLowC();
        if (!finite(v) || v < -60 || v > 60) return null;
        Double hi = fp.climate().tempMaxHighC();
        if (finite(hi) && v > hi) return null;
        return fmt(Math.floor(v * 10.0) / 10.0);
    }

    /** Horizon maximum, rounded UP to 0.1 °C. Null when not observed. */
    private static String high(EnvironmentalFingerprint fp) {
        Double v = fp == null || fp.climate() == null ? null : fp.climate().tempMaxHighC();
        if (!finite(v) || v < -60 || v > 60) return null;
        Double lo = fp.climate().tempMinLowC();
        if (finite(lo) && v < lo) return null;
        return fmt(Math.ceil(v * 10.0) / 10.0);
    }

    /**
     * Annual rainfall normal (whole mm) for blocks with deployment DOY normals,
     * else {@code null}. The 7-day/16-day forecast is never consulted here —
     * this method only sums reference normals, and only when every one of the
     * year's 365 days resolves (a partial year stays unavailable).
     */
    private static String annualRainfallMm(EnvironmentalFingerprint fp, CropAtlasService atlas) {
        if (fp == null || atlas == null || fp.location() == null) return null;
        Object name = fp.location().get("block_name");
        if (!(name instanceof String block) || block.isBlank()) return null;
        if (!atlas.hasClimatologyBlock(block)) return null;
        List<LocalDate> year = new ArrayList<>(365);
        for (int i = 0; i < 365; i++) year.add(LocalDate.of(2021, 1, 1).plusDays(i));
        Double sum;
        try {
            sum = atlas.climatologySumMm(block, year);
        } catch (RuntimeException e) {
            log.debug("ECOCROP annual normal unavailable for {} ({})", block, e.getMessage());
            return null;
        }
        if (!finite(sum) || sum < 0 || sum > 15000) return null;
        return String.valueOf(Math.round(sum));
    }

    private static boolean finite(Double v) {
        return v != null && Double.isFinite(v);
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static String fmt(double v) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }
}
