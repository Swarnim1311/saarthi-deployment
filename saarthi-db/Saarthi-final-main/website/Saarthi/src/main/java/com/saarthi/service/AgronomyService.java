package com.saarthi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saarthi.model.FarmerAnalysisRequest;
import com.saarthi.model.FarmerAnalysisResponse;
import com.saarthi.risks.SoilContext;
import com.saarthi.weather.LiveWeatherService;
import com.saarthi.weather.WeatherController.WeatherUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Farmer Advisory engine — deterministic rules over the LIVE weather contract
 * (GET /api/weather/forecast/{block}: Open-Meteo delivery of ECMWF IFS,
 * D+1..D+16) plus a versioned, source-cited crop reference
 * (classpath:/agronomy/crop_reference.json, PAU PoP / ICAR).
 *
 * <p>Integrity rules enforced here:
 * <ul>
 *   <li>The ONLY weather source is the live block forecast. The frozen
 *       CHIRPS-GEFS package (/api/forecast/*, latest_forecast.json) is never
 *       consulted, never mentioned.</li>
 *   <li>Soil moisture shown to the farmer is the provider's forecast surface
 *       soil moisture (soil_moisture_0_to_7cm, block mean) — labelled as such.
 *       The old synthetic gauge (42 - P(Low)*26 + soil/irrigation offsets) is
 *       REMOVED; no fabricated numbers.</li>
 *   <li>Dry-spell risk is stated as words from live rainfall patterns (no
 *       rain-day probability percentages, no fake "dry-spell %").</li>
 *   <li>Crop stage uses days_since_sowing only where the cited crop reference
 *       provides duration/stages; otherwise stage is explicitly "not
 *       available".</li>
 *   <li>Missing live values are surfaced as "No data" — never zero-filled,
 *       never replaced by proxies.</li>
 *   <li>Panchayat is display context only; weather is block-level. Panchayats
 *       appearing under several blocks are labelled "Panchayat (Block)".</li>
 * </ul>
 */
@Service
public class AgronomyService {

    private static final Logger log = LoggerFactory.getLogger(AgronomyService.class);
    static final String CROP_RESOURCE = "agronomy/crop_reference.json";

    private final RealForecastService blocks; // block registry + canonical names only
    private final LiveWeatherService liveWeather;
    private final SoilContext soilContext;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Panchayat -> blocks containing it (for duplicate-name disambiguation). */
    private final Map<String, List<String>> panchayatBlocks = new LinkedHashMap<>();

    /** Parsed crop reference (PAU/ICAR-cited). */
    private JsonNode cropReference;

    public AgronomyService(RealForecastService blocks, LiveWeatherService liveWeather,
            SoilContext soilContext) {
        this.blocks = blocks;
        this.liveWeather = liveWeather;
        this.soilContext = soilContext;
    }

    @PostConstruct
    void load() {
        for (String b : RealForecastService.BLOCKS) {
            for (String p : DistrictDataService.BLOCK_PANCHAYATS.getOrDefault(b, List.of())) {
                panchayatBlocks.computeIfAbsent(p, k -> new ArrayList<>()).add(b);
            }
        }
        try (InputStream in = new ClassPathResource(CROP_RESOURCE).getInputStream()) {
            cropReference = mapper.readTree(in);
        } catch (Exception e) {
            log.warn("Crop reference unavailable ({}); crop sections will be degraded", e.getMessage());
        }
    }

    /**
     * Full advisory: a deterministic function of (block, panchayat, crop,
     * sowing date, irrigation source) over the live ECMWF IFS block forecast.
     */
    public FarmerAnalysisResponse computeFarmerAnalysis(FarmerAnalysisRequest req) {
        // P0: no silent block default — a missing/blank/unknown block is a 404.
        String block = req.getBlock();
        if (block == null || block.isBlank()) {
            throw new RealForecastService.BlockNotFoundException("(missing block)");
        }
        String canonical = blocks.findBlock(block)
                .map(b -> Objects.toString(b.get("block_name"), null))
                .filter(Objects::nonNull)
                .orElseThrow(() -> new RealForecastService.BlockNotFoundException(block));

        String panchayat = req.getPanchayat() != null ? req.getPanchayat().trim() : "Suler Gherat";
        String crop = req.getCrop() != null && !req.getCrop().isBlank()
                ? req.getCrop() : "Paddy (PR-126)";
        String irrigation = req.getIrrigation() != null && !req.getIrrigation().isBlank()
                ? req.getIrrigation() : "Canals";
        LocalDate sowingDate = parseSowingDate(req.getSowingDate());

        LiveWeatherService.BlockForecast fc = liveWeather.getForecast(false)
                .blocks().get(canonical);
        if (fc == null) {
            throw new WeatherUnavailableException("No live data for block '" + canonical + "'");
        }

        FarmerAnalysisResponse r = new FarmerAnalysisResponse();
        r.setInputs(inputEcho(canonical, panchayat, crop, irrigation, sowingDate));
        r.setLocation(locationSection(canonical, panchayat, fc));
        r.setCropSection(cropSection(crop));
        r.setStage(stageSection(crop, sowingDate));
        r.setWeatherSection(weatherSection(fc));
        r.setSoilSection(soilSection(canonical, fc));
        r.setWaterDemand(waterDemandSection(fc));
        r.setRisks(riskSection(crop, irrigation, fc));
        r.setAdvisory(advisorySection(crop, irrigation, sowingDate, fc));
        r.setSources(sourcesSection());
        return r;
    }

    private Map<String, Object> inputEcho(String block, String panchayat,
            String crop, String irrigation, LocalDate sowingDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("block", block);
        m.put("panchayat", panchayat);
        m.put("crop", crop);
        m.put("irrigation", irrigation);
        m.put("sowing_date", sowingDate == null ? null : sowingDate.toString());
        m.put("sowing_date_provided", sowingDate != null);
        return m;
    }

    private Map<String, Object> locationSection(String block, String panchayat,
            LiveWeatherService.BlockForecast fc) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> containing = panchayatBlocks.getOrDefault(panchayat, List.of());
        // Duplicate panchayat names (e.g. Ubhawal in Sangrur AND Sunam) are
        // disambiguated as "Panchayat (Block)"; unique names stay plain.
        String label = (containing.size() > 1)
                ? panchayat + " (" + block + " block)"
                : panchayat;
        m.put("panchayat_label", label);
        m.put("block", block);
        m.put("weather_resolution", "block");
        m.put("resolution_note", containing.size() > 1
                ? "'" + panchayat + "' appears under several blocks; weather is served at "
                  + "block level and this advisory uses the " + block + " block forecast."
                : "Weather is served at block level (no fabricated panchayat resolution).");
        m.put("forecast_issue_date", fc.issueDate().toString());
        m.put("forecast_retrieved_at", fc.retrievedAt().toString());
        m.put("provider", "Open-Meteo");
        m.put("model", "ECMWF IFS");
        return m;
    }

    private Map<String, Object> cropSection(String crop) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requested", crop);
        JsonNode ref = findCrop(crop);
        if (ref == null) {
            m.put("known", false);
            m.put("note", "Crop not in the cited PAU/ICAR reference; variety-specific "
                    + "duration and stage guidance are not available.");
            return m;
        }
        m.put("known", true);
        m.put("canonical_id", ref.path("id").asText());
        m.put("season", ref.path("season").isMissingNode() ? null : ref.path("season").asText());
        m.put("duration_days", ref.path("duration_days").isInt()
                ? Integer.valueOf(ref.path("duration_days").asInt()) : null);
        JsonNode win = ref.path("sowing_window");
        if (!win.isMissingNode() && win.isObject()) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("start", textOrNull(win, "transplant_start", "sow_start", "plant_start"));
            w.put("end", textOrNull(win, "transplant_end", "sow_end", "plant_end"));
            w.put("note", textOrNull(win, "note"));
            m.put("sowing_window", w);
        } else {
            m.put("sowing_window", null);
        }
        m.put("water_need_class", textOrNull(ref, "water_need_class"));
        m.put("source_ids", toList(ref.path("source_ids")));
        return m;
    }

    private Map<String, Object> stageSection(String crop, LocalDate sowingDate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", false);
        m.put("stage", null);
        m.put("days_since_sowing", null);
        if (sowingDate == null) {
            m.put("note", "No sowing date provided — crop stage cannot be determined. "
                    + "Set the sowing date in the form for stage-specific guidance.");
            return m;
        }
        long d = ChronoUnit.DAYS.between(sowingDate, LocalDate.now());
        m.put("days_since_sowing", (int) d);
        JsonNode ref = findCrop(crop);
        if (ref == null) {
            m.put("note", "Crop not in the cited reference; stage is not available.");
            return m;
        }
        JsonNode stages = ref.path("stages");
        if (!stages.isArray() || stages.size() == 0) {
            m.put("note", "Stage banding not available for this crop in the cited sources.");
            return m;
        }
        for (JsonNode s : stages) {
            if (d >= s.path("start_day").asInt(Integer.MIN_VALUE)
                    && d <= s.path("end_day").asInt(Integer.MAX_VALUE)) {
                m.put("available", true);
                m.put("stage", s.path("name").asText());
                m.put("stage_start_day", s.path("start_day").asInt());
                m.put("stage_end_day", s.path("end_day").asInt());
                break;
            }
        }
        if (!Boolean.TRUE.equals(m.get("available"))) {
            m.put("note", d < 0
                    ? "Sowing date is in the future; stage not applicable yet."
                    : "Days since sowing exceed the cited crop duration; stage not available.");
        }
        return m;
    }

    private Map<String, Object> weatherSection(LiveWeatherService.BlockForecast fc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source_endpoint", "/api/weather/forecast/" + fc.blockName());
        m.put("provider", "Open-Meteo");
        m.put("model", "ECMWF IFS");
        m.put("issue_date", fc.issueDate().toString());
        m.put("horizon_days", fc.days().size());

        Double rain3 = fc.cum3Mm(), rain7 = fc.cum7Mm(), rain15 = fc.cum15Mm();
        m.put("rain_3d_mm", rain3);
        m.put("rain_7d_mm", rain7);
        m.put("rain_15d_mm", rain15);
        m.put("rain_3d_status", statusOf(rain3, "mm"));
        m.put("rain_7d_status", statusOf(rain7, "mm"));
        m.put("rain_15d_status", statusOf(rain15, "mm"));
        m.put("et0_7d_mm", fc.et0_7dMm());
        m.put("et0_7d_status", statusOf(fc.et0_7dMm(), "mm"));

        m.put("days", fc.days().stream().map(d -> {
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("date", d.date().toString());
            dm.put("horizon_day", d.horizonDay());
            dm.put("rainfall_mm", d.rainfallMm());
            dm.put("rain_probability_pct", d.rainProbabilityPct());
            dm.put("temperature_max_c", d.temperatureMaxC());
            dm.put("temperature_min_c", d.temperatureMinC());
            dm.put("et0_mm", d.et0Mm());
            dm.put("soil_moisture_0_to_7cm", d.soilMoisture0To7CmVwc());
            return dm;
        }).toList());
        return m;
    }

    private Map<String, Object> soilSection(String block, LiveWeatherService.BlockForecast fc) {
        Map<String, Object> m = new LinkedHashMap<>();
        // Forecast surface moisture from the SAME live feed — never a synthetic gauge.
        Double v = (fc.days().isEmpty()) ? null : fc.days().get(0).soilMoisture0To7CmVwc();
        m.put("forecast_surface_soil_moisture_vwc", v);
        m.put("forecast_surface_soil_moisture_status", statusOf(v, "m3/m3"));
        m.put("label", v == null
                ? "Forecast surface soil moisture: No data"
                : "Forecast surface soil moisture");
        m.put("source", "ECMWF IFS soil_moisture_0_to_7cm, block mean, day 1 of the live forecast");
        m.put("measured_context", soilContext.describe(block)); // SoilGrids, display-only
        m.put("note", "This is a model forecast of the surface layer, not a field measurement; "
                + "the SoilGrids context describes the soil, not today's moisture.");
        return m;
    }

    private Map<String, Object> waterDemandSection(LiveWeatherService.BlockForecast fc) {
        Map<String, Object> m = new LinkedHashMap<>();
        Double rain7 = fc.cum7Mm();
        Double et0_7d = fc.et0_7dMm();
        // Simple climatic water balance of the coming week: rainfall minus ET0.
        // Deterministic arithmetic on live values only — no invented crop coefficients.
        m.put("rain_7d_mm", rain7);
        m.put("et0_7d_mm", et0_7d);
        m.put("rain_minus_et0_7d_mm",
                (rain7 != null && et0_7d != null) ? round1(rain7 - et0_7d) : null);
        m.put("interpretation", waterBalanceWords(rain7, et0_7d));
        m.put("note", "Crop coefficients (Kc) per variety are not available in the cited "
                + "PAU/ICAR package-of-practices documents, so this is a climate water "
                + "balance, not a field irrigation dosage.");
        return m;
    }

    private Map<String, Object> riskSection(String crop, String irrigation,
            LiveWeatherService.BlockForecast fc) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> flags = new ArrayList<>();

        // Dry-spell watch: consecutive early days with low rainfall probability
        // and little rain — described in WORDS, never as a fabricated percentage.
        int dryDaysAhead = countDryDaysAhead(fc);
        if (dryDaysAhead >= 5) flags.add("dry_spell_watch");

        // Heavy-rain watch: any day >= 35 mm block-mean rainfall.
        boolean heavyRain = fc.days().stream()
                .anyMatch(d -> d.rainfallMm() != null && d.rainfallMm() >= 35.0);
        if (heavyRain) flags.add("heavy_rain_watch");

        // Heat watch: max temperature >= 37 C on any of the first 7 days.
        boolean heat = fc.days().stream().limit(7)
                .anyMatch(d -> d.temperatureMaxC() != null && d.temperatureMaxC() >= 37.0);
        if (heat) flags.add("heat_stress_watch");

        // Waterlogging watch for low-tolerance crops: >= 60 mm over the next 3 days.
        JsonNode ref = findCrop(crop);
        String wlTol = ref == null ? null
                : ref.path("sensitivities").path("waterlogging_tolerance").isTextual()
                        ? ref.path("sensitivities").path("waterlogging_tolerance").asText() : null;
        Double rain3 = fc.cum3Mm();
        boolean waterlog = rain3 != null && rain3 >= 60.0
                && (wlTol == null || wlTol.equalsIgnoreCase("low"));
        if (waterlog) flags.add("waterlogging_watch");

        m.put("flags", flags);
        m.put("dry_spell_watch", drySpellWatchWords(dryDaysAhead));
        m.put("rainfed_caution", "Rainfed".equalsIgnoreCase(irrigation)
                ? "Irrigation source is Rainfed: rainfall timing decides every irrigation "
                  + "event; check the dry-spell watch before critical crop stages."
                : null);
        return m;
    }

    private Map<String, Object> advisorySection(String crop, String irrigation,
            LocalDate sowingDate, LiveWeatherService.BlockForecast fc) {
        Map<String, Object> m = new LinkedHashMap<>();
        JsonNode ref = findCrop(crop);
        Double rain7 = fc.cum7Mm();
        Double et07 = fc.et0_7dMm();
        Double soilD1 = fc.days().isEmpty() ? null : fc.days().get(0).soilMoisture0To7CmVwc();
        int dryDays = countDryDaysAhead(fc);

        List<String> actions = new ArrayList<>();
        // Irrigation guidance — deterministic on live rain/ET0/soil moisture + crop class.
        if (rain7 == null) {
            actions.add("Rainfall for the coming week is not available; check the live "
                    + "forecast before scheduling irrigation.");
        } else if (dryDays >= 5 && rain7 < 10.0) {
            actions.add("Dry-spell watch: little or no meaningful rain expected for the next "
                    + "five days. Plan irrigation for the coming week; for transplanted rice, "
                    + "maintain the standing water layer.");
        } else if (rain7 >= 40.0) {
            actions.add("Substantial rain expected this week (" + fmt(rain7)
                    + " mm over 7 days). Postpone irrigation and check drainage in "
                    + "low-tolerance crops.");
        } else if (et07 != null && rain7 < et07) {
            actions.add("Expected evapotranspiration (" + fmt(et07)
                    + " mm over 7 days) exceeds expected rainfall (" + fmt(rain7)
                    + " mm). Soil water will be drawn down — schedule irrigation for "
                    + "moisture-sensitive stages.");
        } else {
            actions.add("No immediate irrigation trigger from the 7-day outlook: expected "
                    + "rainfall (" + fmt(rain7) + " mm) covers part of the atmospheric demand.");
        }
        if (soilD1 != null && soilD1 < 0.12) {
            actions.add("Forecast surface soil moisture is low (" + fmt(soilD1)
                    + " m3/m3 on " + fc.days().get(0).date() + "); the top layer will dry "
                    + "quickly without rain.");
        }
        if ("Rainfed".equalsIgnoreCase(irrigation)) {
            actions.add("Rainfed field: every operation depends on the realised rain; "
                    + "re-check the live forecast daily.");
        }

        // Sowing window guidance (cited).
        if (ref != null && sowingDate != null) {
            String sowNote = sowingWindowWords(ref, sowingDate);
            if (sowNote != null) actions.add(sowNote);
        } else if (ref != null && sowWindowStart(ref) != null) {
            actions.add("Cited sowing window for " + ref.path("id").asText() + ": "
                    + sowWindowStart(ref) + " to " + Objects.toString(sowWindowEnd(ref), "-") + ".");
        }
        String hint = ref == null ? null : textOrNull(ref, "irrigation_rule_hint");
        if (hint != null) actions.add(hint);

        m.put("actions", actions);
        m.put("deterministic", true);
        m.put("explanation", "Rules use only the live ECMWF IFS block forecast and the "
                + "cited PAU/ICAR crop reference. Change any input (crop, sowing date, "
                + "irrigation, block) and the matching sections change with it.");
        return m;
    }

    private Map<String, Object> sourcesSection() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("weather", List.of(
                "Open-Meteo API delivering ECMWF IFS (model ecmwf_ifs), daily + hourly, "
                        + "Asia/Kolkata, 16-day horizon — GET /api/weather/forecast/{block}"));
        List<String> agronomy = new ArrayList<>();
        if (cropReference != null && cropReference.path("sources").isArray()) {
            for (JsonNode s : cropReference.path("sources")) {
                agronomy.add(s.path("id").asText() + ": " + s.path("title").asText()
                        + " — " + s.path("publisher").asText());
            }
        }
        m.put("agronomy", agronomy);
        m.put("soil", "SoilGrids 0-5 cm block means (risk/soil_context.json), display context only");
        m.put("integrity_note", "No synthetic soil-moisture gauge, no dry-spell percentages, no "
                + "frozen CHIRPS-GEFS artifacts; missing data is reported as 'No data'.");
        return m;
    }

    /* ------------------------------------------------------------------ */
    /* Deterministic helpers                                               */
    /* ------------------------------------------------------------------ */

    /** Days (first 5 of the horizon) where P(rain) < 25% AND rainfall < 2 mm. */
    private int countDryDaysAhead(LiveWeatherService.BlockForecast fc) {
        int n = 0;
        for (LiveWeatherService.BlockDaily d : fc.days()) {
            if (d.horizonDay() > 5) break;
            boolean lowProb = d.rainProbabilityPct() != null && d.rainProbabilityPct() < 25.0;
            boolean littleRain = d.rainfallMm() == null || d.rainfallMm() < 2.0;
            if (lowProb && littleRain) n++;
        }
        return n;
    }

    private String drySpellWatchWords(int dryDays) {
        if (dryDays >= 5) return "Watch: the first five days of the forecast are largely "
                + "rain-free. A dry spell is likely in this window.";
        if (dryDays >= 3) return "Some rain-free days in the first five days of the forecast; "
                + "keep irrigation plans ready.";
        return "No dry-spell signal in the first five days of the forecast.";
    }

    private String waterBalanceWords(Double rain7, Double et07) {
        if (rain7 == null || et07 == null) {
            return "Water balance not available — " + (rain7 == null ? "rainfall" : "ET0")
                    + " missing from the live feed for this block.";
        }
        double bal = rain7 - et07;
        if (bal >= 10.0) return "Surplus week: expected rainfall exceeds atmospheric demand by "
                + fmt(bal) + " mm.";
        if (bal >= -10.0) return "Near-balanced week: rainfall roughly matches atmospheric "
                + "demand (" + fmt(bal) + " mm).";
        return "Deficit week: atmospheric demand exceeds expected rainfall by "
                + fmt(-bal) + " mm — irrigation planning matters this week.";
    }

    private String sowingWindowWords(JsonNode ref, LocalDate sowingDate) {
        String start = sowWindowStart(ref), end = sowWindowEnd(ref);
        if (start == null || end == null) return null;
        try {
            int y = LocalDate.now().getYear();
            LocalDate ws = LocalDate.parse(y + "-" + start);
            LocalDate we = LocalDate.parse(y + "-" + end);
            if (ws.isAfter(we)) {
                if (sowingDate.isBefore(ws)) ws = ws.minusYears(1); else we = we.plusYears(1);
            }
            if (!sowingDate.isBefore(ws) && !sowingDate.isAfter(we)) {
                return "Sowing date " + sowingDate + " falls inside the cited sowing window ("
                        + start + " to " + end + ").";
            }
            return "Sowing date " + sowingDate + " is OUTSIDE the cited sowing window ("
                    + start + " to " + end + ") for " + ref.path("id").asText() + ".";
        } catch (Exception e) {
            return null;
        }
    }

    private String sowWindowStart(JsonNode ref) {
        JsonNode w = ref.path("sowing_window");
        return textOrNull(w, "transplant_start", "sow_start", "plant_start");
    }

    private String sowWindowEnd(JsonNode ref) {
        JsonNode w = ref.path("sowing_window");
        return textOrNull(w, "transplant_end", "sow_end", "plant_end");
    }

    private JsonNode findCrop(String requested) {
        if (cropReference == null || requested == null) return null;
        for (JsonNode c : cropReference.path("crops")) {
            if (requested.equalsIgnoreCase(c.path("id").asText())) return c;
        }
        for (JsonNode c : cropReference.path("crops")) {
            if (c.path("aliases").isArray()) {
                for (JsonNode a : c.path("aliases")) {
                    if (requested.equalsIgnoreCase(a.asText())) return c;
                }
            }
        }
        return null;
    }

    private LocalDate parseSowingDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private String statusOf(Double v, String unit) {
        return v == null ? "No data" : v + " " + unit;
    }

    private String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private String textOrNull(JsonNode n, String... keys) {
        for (String k : keys) {
            if (n != null && n.has(k) && n.get(k).isTextual()) return n.get(k).asText();
        }
        return null;
    }

    private List<String> toList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) for (JsonNode v : arr) out.add(v.asText());
        return out;
    }
}
