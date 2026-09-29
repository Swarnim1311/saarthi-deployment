package com.saarthi.chat;

import com.saarthi.cropatlas.CropAtlasSoilSource;
import com.saarthi.geo.GeographyService;
import com.saarthi.intelligence.ForecastContext;
import com.saarthi.intelligence.ForecastContextService;
import com.saarthi.intelligence.GridGroundwaterService;
import com.saarthi.intelligence.SectorResult;
import com.saarthi.intelligence.SectorRiskService;
import com.saarthi.risks.ClimatologyContext;
import com.saarthi.weather.LiveWeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a controlled context block for the assistant from services SAARTHI already
 * runs. This is what makes the assistant SAARTHI-aware rather than a generic
 * chatbot: it answers with the platform's own measured values, and it is explicit
 * about which of them are missing.
 *
 * <p><b>No value is ever invented.</b> A quantity that is unavailable is
 * {@code null} and is also named in {@code unavailable}, so the model is told
 * "this is not known" rather than being allowed to guess. A missing day never
 * contributes a zero to a total.
 *
 * <p><b>Nothing here is authoritative that it is not.</b> The forecast block is
 * labelled as a forecast, climate-scale context is labelled as such, and soil is
 * labelled as a modelled 0-5 cm surface value rather than a field measurement.
 *
 * <p>Every lookup is individually guarded: a failing optional source degrades that
 * one field to unavailable and never fails the chat request.
 */
@Component
public class SaarthiChatContext {

    private static final Logger log = LoggerFactory.getLogger(SaarthiChatContext.class);

    private final GeographyService geography;
    private final LiveWeatherService live;
    private final ClimatologyContext climatology;
    private final LocalAdvisoryCorpus corpus;
    private final ForecastContextService intelligenceContexts;
    private final SectorRiskService sectorRisk;
    private final GridGroundwaterService grid;
    private volatile CropAtlasSoilSource sharedSoil;

    @Autowired
    public SaarthiChatContext(GeographyService geography, LiveWeatherService live,
            ClimatologyContext climatology, LocalAdvisoryCorpus corpus,
            ForecastContextService intelligenceContexts, SectorRiskService sectorRisk,
            GridGroundwaterService grid) {
        this.geography = geography;
        this.live = live;
        this.climatology = climatology;
        this.corpus = corpus;
        this.intelligenceContexts = intelligenceContexts;
        this.sectorRisk = sectorRisk;
        this.grid = grid;
    }

    /**
     * The SAME soil source CropAtlas uses — no second soil stack. Optional so
     * the chat path never breaks when CropAtlas wiring is absent (tests, slim
     * contexts): without it the soil block is simply reported unavailable.
     */
    @Autowired(required = false)
    public void setSharedSoil(CropAtlasSoilSource sharedSoil) {
        this.sharedSoil = sharedSoil;
    }

    /**
     * Assemble the context for one question. Always returns a non-null map; the
     * {@code available} flag says whether any SAARTHI data backed it.
     *
     * @param state    state code from the client, or {@code null}
     * @param district district code from the client, or {@code null}
     * @param block    block code or legacy block name from the client, or {@code null}
     * @param crop     crop the farmer is working with, or {@code null}
     */
    public Map<String, Object> build(String state, String district, String block,
            String crop) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        java.util.List<String> unavailable = new java.util.ArrayList<>();
        String blockName = null;

        // ---- location identity ----
        Map<String, Object> location = new LinkedHashMap<>();
        GeographyService.BlockRef ref = resolve(state, district, block);
        if (ref != null) {
            blockName = ref.blockName();
            put(location, "block", ref.blockName());
            put(location, "district", ref.districtName());
            put(location, "state", ref.stateName());
            put(location, "locationMethod", ref.locationMethod());
        } else if (block != null && !block.isBlank()) {
            // A legacy block name may be valid for the forecast even when it is not
            // in the registry, so keep it and let the forecast resolve it.
            blockName = block.trim();
            put(location, "block", blockName);
            unavailable.add("registry_coordinates");
        } else {
            unavailable.add("location");
        }
        ctx.put("location", location);

        // ---- crop ----
        Map<String, Object> cropBlock = new LinkedHashMap<>();
        if (crop != null && !crop.isBlank()) {
            cropBlock.put("crop", crop.trim());
            Map<String, Object> knowledge = corpus.cropKnowledge(crop);
            cropBlock.putAll(knowledge);
            if (knowledge.isEmpty()) unavailable.add("crop_reference_entry_for_selected_crop");
        } else {
            unavailable.add("crop");
        }
        ctx.put("crop", cropBlock);

        // ---- live forecast ----
        Map<String, Object> forecast = new LinkedHashMap<>();
        if (blockName == null) {
            unavailable.add("forecast_requires_a_block");
        } else {
            fillForecast(forecast, blockName, ref, unavailable);
        }
        ctx.put("forecast", forecast);

        // ---- climatology ----
        Map<String, Object> climate = new LinkedHashMap<>();
        if (blockName == null) {
            unavailable.add("climatology_requires_a_block");
        } else {
            try {
                if (hasNormals(blockName)) {
                    climate.put("vintage", climatology.vintage());
                    climate.put("status", "reference normals (not a forecast)");
                } else {
                    unavailable.add("climatology_normals_for_this_block");
                }
            } catch (RuntimeException e) {
                log.debug("Climatology unavailable for {}: {}", blockName, e.getMessage());
                unavailable.add("climatology_normals_for_this_block");
            }
        }
        ctx.put("climateContext", climate);

        // ---- Climate Intelligence sector verdicts ----
        // Reuses the existing engine. No second risk calculation, no new score,
        // no ranking: the four sector verdicts are surfaced exactly as the
        // Intelligence page already computes them.
        Map<String, Object> sectors = sectors(state, district, block, unavailable);
        ctx.put("sectors", sectors);

        // ---- shared soil properties (same source CropAtlas renders) ----
        // Informational context only: neither the chat fallback nor CropAtlas
        // scores soil compatibility, because the reference states no per-crop
        // soil requirement. A missing value stays missing, never estimated.
        ctx.put("soil", soilBlock(ref, blockName, unavailable));

        // ---- what the assistant must not claim ----
        Map<String, Object> notAvailable = new LinkedHashMap<>();
        notAvailable.put("marketPrices", "not available in SAARTHI");
        notAvailable.put("yield", "not estimated by SAARTHI");
        notAvailable.put("governmentSchemes", "not covered by SAARTHI");
        notAvailable.put("fieldMeasuredSoil", "soil is a modelled 0-5 cm surface value");
        ctx.put("notAvailable", notAvailable);

        ctx.put("unavailable", unavailable);
        ctx.put("available", !forecast.isEmpty() || !location.isEmpty());
        return ctx;
    }

    // ---- forecast ----

    private void fillForecast(Map<String, Object> out, String blockName,
            GeographyService.BlockRef ref, java.util.List<String> unavailable) {
        List<LiveWeatherService.BlockDaily> days = null;
        try {
            if (ref != null) {
                days = live.forecastForCentroid(ref.blockName(), ref.latitude(),
                        ref.longitude()).days();
            } else {
                LiveWeatherService.LiveForecast lf = live.getForecast(false);
                if (lf != null && lf.blocks() != null) {
                    LiveWeatherService.BlockForecast bf = lf.blocks().get(blockName);
                    if (bf != null) {
                        days = bf.days();
                        out.put("provider", lf.provider());
                        out.put("model", lf.model());
                        if (lf.issueDate() != null) out.put("issued", lf.issueDate().toString());
                        out.put("stale", lf.stale());
                    }
                }
            }
        } catch (RuntimeException e) {
            log.debug("Forecast unavailable for {}: {}", blockName, e.getMessage());
            unavailable.add("live_forecast");
            out.put("status", "unavailable");
            return;
        }
        if (days == null || days.isEmpty()) {
            unavailable.add("live_forecast");
            out.put("status", "unavailable");
            return;
        }
        if (!out.containsKey("provider")) {
            try {
                out.put("provider", live.providerName());
                out.put("model", live.modelName());
            } catch (RuntimeException ignored) {
                // Provider labels are cosmetic; their absence is not fatal.
            }
        }
        out.put("status", "live forecast — a forecast, not an observation");
        out.put("horizonDays", days.size());
        out.put("rain3dMm", rainSum(days, 0, 3));
        out.put("rain7dMm", rainSum(days, 0, 7));
        out.put("rain16dMm", rainSum(days, 0, days.size()));
        out.put("et0_7dMm", et0Sum(days, 0, 7));
        out.put("maxTempC", mean(days, LiveWeatherService.BlockDaily::temperatureMaxC));
        out.put("minTempC", mean(days, LiveWeatherService.BlockDaily::temperatureMinC));
        out.put("maxTempPeakC", peak(days, LiveWeatherService.BlockDaily::temperatureMaxC, true));
        out.put("minTempLowC", peak(days, LiveWeatherService.BlockDaily::temperatureMinC, false));
        Integer dry = dryDays(days);
        out.put("dryDaysFirst7", dry);
        if (dry != null && dry >= 5) {
            out.put("drySpellWatch", "forecast is mostly dry over the first 7 days");
        }
        try {
            Double vwc = days.get(0).soilMoisture0To7CmVwc();
            out.put("soilMoisture0to7cmForecast", vwc);
            if (vwc != null) {
                out.put("soilMoistureNote",
                        "model soil moisture forecast, not a field reading");
            }
        } catch (RuntimeException ignored) {
            // Optional field.
        }
        if (out.get("rain3dMm") == null) unavailable.add("forecast_rainfall_3d");
        if (out.get("rain7dMm") == null) unavailable.add("forecast_rainfall_7d");
    }

    // ---- Climate Intelligence sectors ----

    /**
     * The four existing sector verdicts for the selected location.
     *
     * <p>Delegates entirely to {@code ForecastContextService} +
     * {@code GridGroundwaterService} + {@code SectorRiskService}, so the chatbot can
     * never disagree with the Intelligence page. Only the fields needed to explain a
     * verdict are carried: state, the engine's own reasons, its supporting evidence,
     * and the unavailable reason when there is no verdict. The engine's validation
     * note travels with each sector so the model can repeat that these are rule-based
     * planning defaults, not calibrated probabilities.
     */
    private Map<String, Object> sectors(String state, String district, String block,
            List<String> unavailable) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (intelligenceContexts == null || sectorRisk == null) {
                unavailable.add("climate_intelligence");
                return out;
            }
            // The client sends one "block" value which may be either a registry code
            // or a legacy block name, so the registry triple is tried first and the
            // legacy name second — mirroring how the other pages resolve identity.
            ForecastContext fctx = intelligenceContexts.build(null, state, district, block,
                    null, null, null);
            if (fctx == null || fctx.days() == null || fctx.days().isEmpty()) {
                fctx = intelligenceContexts.build(block, null, null, null,
                        null, null, null);
            }
            if (fctx == null || fctx.days() == null || fctx.days().isEmpty()) {
                unavailable.add("climate_intelligence_forecast");
                return out;
            }
            SectorResult energy = grid == null ? null : grid.assess(fctx);
            Map<String, SectorResult> all = sectorRisk.assessAll(fctx, energy);
            for (Map.Entry<String, SectorResult> e : all.entrySet()) {
                out.put(e.getKey(), sectorSummary(e.getValue()));
            }
            if (out.isEmpty()) unavailable.add("climate_intelligence_sectors");
        } catch (RuntimeException ex) {
            // A failing optional source degrades this one block of context only.
            log.debug("Climate Intelligence unavailable: {}", ex.getMessage());
            unavailable.add("climate_intelligence");
        }
        return out;
    }

    /** Flatten one {@link SectorResult} to the fields the assistant may quote. */
    private static Map<String, Object> sectorSummary(SectorResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (r == null) {
            m.put("state", "UNAVAILABLE");
            m.put("available", false);
            return m;
        }
        m.put("state", r.state());
        m.put("available", r.available());
        // The engine's own words, not a paraphrase.
        if (r.reasons() != null && !r.reasons().isEmpty()) m.put("reasons", r.reasons());
        if (r.available() && r.evidence() != null && !r.evidence().isEmpty()) {
            m.put("evidence", r.evidence());
        }
        if (r.available() && r.actions() != null && !r.actions().isEmpty()) {
            m.put("actions", r.actions());
        }
        if (!r.available() && r.unavailableReason() != null) {
            m.put("unavailableReason", r.unavailableReason());
        }
        if (r.validationNote() != null) m.put("validationNote", r.validationNote());
        return m;
    }

    // ---- shared soil (same CropAtlas source, informational only) ----

    private Map<String, Object> soilBlock(GeographyService.BlockRef ref, String blockName,
            java.util.List<String> unavailable) {
        Map<String, Object> out = new LinkedHashMap<>();
        CropAtlasSoilSource source = sharedSoil;
        if (source == null) {
            unavailable.add("soil_source_not_wired");
            return out;
        }
        Double lat = ref == null ? null : ref.latitude();
        Double lon = ref == null ? null : ref.longitude();
        try {
            com.saarthi.cropatlas.EnvironmentalFingerprint.Soil soil =
                    source.soilFor(blockName, lat, lon);
            if (soil == null || !soil.available()) {
                unavailable.add("soil_properties_for_this_block");
                return out;
            }
            put(out, "ph", soil.ph());
            put(out, "sandGkg", soil.sandGkg());
            put(out, "siltGkg", soil.siltGkg());
            put(out, "clayGkg", soil.clayGkg());
            put(out, "socGkg", soil.socGkg());
            put(out, "cecCmolKg", soil.cecCmolKg());
            put(out, "provenance", soil.cached() ? "cached" : "reference");
            put(out, "source", soil.source());
            put(out, "depth", soil.depthNote());
            if (out.size() <= 3) unavailable.add("soil_properties_incomplete_for_this_block");
        } catch (RuntimeException e) {
            log.debug("Shared soil unavailable for {}: {}", blockName, e.getMessage());
            unavailable.add("soil_properties_for_this_block");
        }
        return out;
    }

    // ---- identity ----

    private GeographyService.BlockRef resolve(String state, String district, String block) {
        try {
            if (state != null && !state.isBlank() && district != null && !district.isBlank()
                    && block != null && !block.isBlank()) {
                Optional<GeographyService.BlockRef> found =
                        geography.findBlock(state.trim(), district.trim(), block.trim());
                return found.orElse(null);
            }
            if (block != null && !block.isBlank()) {
                return geography.search(block.trim()).stream().findFirst().orElse(null);
            }
        } catch (RuntimeException e) {
            log.debug("Geography lookup failed: {}", e.getMessage());
        }
        return null;
    }

    private boolean hasNormals(String blockName) {
        try {
            return climatology.normalSumMm(blockName,
                    List.of(java.time.LocalDate.of(2021, 7, 1))) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- null-honest aggregation (a missing day never contributes zero) ----

    static Double rainSum(List<LiveWeatherService.BlockDaily> days, int from, int to) {
        return sum(days, from, to, d -> d.rainfallMm());
    }

    static Double et0Sum(List<LiveWeatherService.BlockDaily> days, int from, int to) {
        return sum(days, from, to, d -> d.et0Mm());
    }

    private static Double sum(List<LiveWeatherService.BlockDaily> days, int from, int to,
            java.util.function.Function<LiveWeatherService.BlockDaily, Double> f) {
        if (days == null || days.isEmpty()) return null;
        int end = Math.min(to, days.size());
        if (end - from < Math.max(1, to - from)) return null;
        double total = 0;
        for (int i = from; i < end; i++) {
            Double v = f.apply(days.get(i));
            if (v == null || Double.isNaN(v) || Double.isInfinite(v)) return null;
            total += v;
        }
        return round1(total);
    }

    private static Double mean(List<LiveWeatherService.BlockDaily> days,
            java.util.function.Function<LiveWeatherService.BlockDaily, Double> f) {
        double total = 0;
        int n = 0;
        for (LiveWeatherService.BlockDaily d : days) {
            Double v = f.apply(d);
            if (v == null || Double.isNaN(v) || Double.isInfinite(v)) continue;
            total += v;
            n++;
        }
        return n == 0 ? null : round1(total / n);
    }

    private static Double peak(List<LiveWeatherService.BlockDaily> days,
            java.util.function.Function<LiveWeatherService.BlockDaily, Double> f,
            boolean wantMax) {
        Double best = null;
        for (LiveWeatherService.BlockDaily d : days) {
            Double v = f.apply(d);
            if (v == null || Double.isNaN(v) || Double.isInfinite(v)) continue;
            if (best == null || (wantMax ? v > best : v < best)) best = v;
        }
        return best;
    }

    private static Integer dryDays(List<LiveWeatherService.BlockDaily> days) {
        int n = 0;
        for (int i = 0; i < Math.min(7, days.size()); i++) {
            Double v = days.get(i).rainfallMm();
            if (v == null || Double.isNaN(v) || Double.isInfinite(v)) return null;
            if (v < 1.0) n++;
        }
        return n;
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    private static Double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
