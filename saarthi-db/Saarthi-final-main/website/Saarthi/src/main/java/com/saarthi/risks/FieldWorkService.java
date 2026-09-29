package com.saarthi.risks;

import com.saarthi.service.RealForecastService;
import com.saarthi.service.RealForecastService.BlockNotFoundException;
import com.saarthi.weather.LiveWeatherService;
import com.saarthi.weather.WeatherProviderException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * FIELD_WORK_DISRUPTION risk derived from the existing live IFS forecast.
 *
 * <p>Consumes the already-retrieved {@link LiveWeatherService.LiveForecast}
 * (same object served by {@code /api/weather/*}) — never issues a second
 * Open-Meteo request. Fail-soft: assessment failures degrade to an
 * {@code UNAVAILABLE} risk entry, and a provider failure without cache
 * surfaces as {@link RiskUnavailableException}, never synthetic data.
 *
 * <p>Confidence is conservative by design: the rule was validated
 * historically on GEFS (precision 0.721, recall 0.809), while production
 * runs on ECMWF IFS — the live transfer is NOT yet confirmed, so results
 * are labelled {@code MODERATE} ( {@code LOW} when the underlying forecast
 * is stale), never "validated".
 */
@Service
public class FieldWorkService {

    /** Only operational window served in this checkpoint. */
    public static final String WINDOW_3D = "3d";
    public static final String WINDOW_LABEL = "D+1-D+3";

    static final String VALIDATION_NOTE =
            "Candidate rule validated historically on GEFS (precision 0.721, "
            + "recall 0.809, F1 0.763); live IFS transfer not yet confirmed — "
            + "not an IFS validation.";

    private final LiveWeatherService live;
    private final RealForecastService blocks;

    @Autowired
    public FieldWorkService(LiveWeatherService live, RealForecastService blocks) {
        this.live = live;
        this.blocks = blocks;
    }

    /** Risk for one block (name or Bhuvan id, case-insensitive). */
    public Map<String, Object> getBlockRisk(String blockId, String window) {
        String w = normaliseWindow(window);
        String canonical = blocks.findBlock(blockId)
                .map(b -> java.util.Objects.toString(b.get("block_name"), null))
                .orElseThrow(() -> new BlockNotFoundException(blockId));
        LiveWeatherService.LiveForecast fc = currentForecast();
        LiveWeatherService.BlockForecast b = fc.blocks().get(canonical);
        if (b == null) {
            throw new RiskUnavailableException(
                    "No live data for block '" + canonical + "'");
        }
        return toRiskMap(canonical, b, fc, w);
    }

    /** Risk for all six blocks. Per-block assessment failures degrade to
     * {@code UNAVAILABLE} entries; only a forecast-level failure aborts. */
    public Map<String, Object> getAllRisks(String window) {
        String w = normaliseWindow(window);
        LiveWeatherService.LiveForecast fc = currentForecast();
        List<Map<String, Object>> list = new ArrayList<>();
        for (String canonical : RealForecastService.BLOCKS) {
            LiveWeatherService.BlockForecast b = fc.blocks().get(canonical);
            if (b == null) {
                list.add(unavailableMap(canonical, fc, w,
                        "No live data for block '" + canonical + "'"));
            } else {
                list.add(toRiskMap(canonical, b, fc, w));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("risk", "FIELD_WORK_DISRUPTION");
        out.put("window", WINDOW_LABEL);
        out.put("method_version", FieldWorkRule.METHOD_VERSION);
        out.put("confidence", fc.stale() ? "LOW" : "MODERATE");
        out.put("validation_note", VALIDATION_NOTE);
        out.put("provider", fc.provider());
        out.put("model", fc.model());
        out.put("issue_date", fc.issueDate().toString());
        out.put("retrieved_at", fc.retrievedAt().toString());
        out.put("stale", fc.stale());
        if (fc.staleWarning() != null) out.put("stale_warning", fc.staleWarning());
        out.put("blocks", list);
        return out;
    }

    private LiveWeatherService.LiveForecast currentForecast() {
        try {
            return live.getForecast(false);
        } catch (WeatherProviderException e) {
            throw new RiskUnavailableException(
                    "Live forecast unavailable, risk cannot be assessed: " + e.getMessage(), e);
        }
    }

    static String normaliseWindow(String window) {
        if (window == null || window.isBlank() || WINDOW_3D.equalsIgnoreCase(window.trim())) {
            return WINDOW_3D;
        }
        throw new InvalidWindowException(window);
    }

    private Map<String, Object> toRiskMap(String canonical,
            LiveWeatherService.BlockForecast b,
            LiveWeatherService.LiveForecast fc, String w) {
        LocalDate issue = fc.issueDate();
        Map<LocalDate, LiveWeatherService.BlockDaily> byDate = new TreeMap<>();
        for (LiveWeatherService.BlockDaily d : b.days()) byDate.put(d.date(), d);
        List<String> windowDates = new ArrayList<>();
        List<Double> d1d3 = new ArrayList<>();
        for (int k = 1; k <= 3; k++) {
            LocalDate d = issue.plusDays(k);
            windowDates.add(d.toString());
            LiveWeatherService.BlockDaily day = byDate.get(d);
            d1d3.add(day == null ? null : day.rainfallMm());
        }
        FieldWorkRule.Assessment a = FieldWorkRule.assess(d1d3);
        if (a.category() == FieldWorkRule.Category.UNAVAILABLE) {
            return unavailableMap(canonical, fc, w,
                    "Incomplete D+1..D+3 forecast for block '" + canonical
                    + "' (missing days are never zero-filled)");
        }
        Map<String, Object> out = baseMap(canonical, fc, w, windowDates);
        out.put("category", a.category().name());
        out.put("confidence", fc.stale() ? "LOW" : "MODERATE");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("wet_days", a.wetDays());
        evidence.put("max_precipitation_mm", a.maxMm());
        evidence.put("daily_precipitation_mm", a.dailyMm());
        out.put("evidence", evidence);
        out.put("reasons", a.reasonCodes());
        out.put("advisory", advisory(a));
        return out;
    }

    private Map<String, Object> unavailableMap(String canonical,
            LiveWeatherService.LiveForecast fc, String w, String reason) {
        Map<String, Object> out = baseMap(canonical, fc, w, windowDatesOf(fc));
        out.put("category", FieldWorkRule.Category.UNAVAILABLE.name());
        out.put("confidence", "LOW");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("wet_days", null);
        evidence.put("max_precipitation_mm", null);
        evidence.put("daily_precipitation_mm", null);
        out.put("evidence", evidence);
        out.put("reasons", List.of("FIELD_UNAVAILABLE"));
        out.put("unavailable_reason", reason);
        out.put("advisory",
                "Field-work risk unavailable because the forecast data is incomplete.");
        return out;
    }

    private List<String> windowDatesOf(LiveWeatherService.LiveForecast fc) {
        List<String> dates = new ArrayList<>();
        for (int k = 1; k <= 3; k++) dates.add(fc.issueDate().plusDays(k).toString());
        return dates;
    }

    private Map<String, Object> baseMap(String canonical,
            LiveWeatherService.LiveForecast fc, String w, List<String> windowDates) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("block", canonical);
        out.put("issue_date", fc.issueDate().toString());
        out.put("risk", "FIELD_WORK_DISRUPTION");
        out.put("window", WINDOW_LABEL);
        out.put("window_dates", windowDates);
        out.put("method_version", FieldWorkRule.METHOD_VERSION);
        out.put("validation_note", VALIDATION_NOTE);
        out.put("provider", fc.provider());
        out.put("model", fc.model());
        out.put("retrieved_at", fc.retrievedAt().toString());
        out.put("stale", fc.stale());
        if (fc.staleWarning() != null) out.put("stale_warning", fc.staleWarning());
        return out;
    }

    static String advisory(FieldWorkRule.Assessment a) {
        if (a.category() == FieldWorkRule.Category.HIGH) {
            return "Field work may be disrupted during the next 3 days because "
                    + a.wetDays() + " of 3 forecast days are expected to be wet "
                    + "(≥1 mm/day).";
        }
        return "No strong field-work disruption signal in the next 3 days.";
    }

    /** Upstream failure with no cache: explicit unavailable, never synthetic risk. */
    public static class RiskUnavailableException extends RuntimeException {
        public RiskUnavailableException(String message) {
            super(message);
        }

        public RiskUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Unsupported operational window. */
    public static class InvalidWindowException extends RuntimeException {
        public InvalidWindowException(String window) {
            super("Unsupported window '" + window + "': only '3d' (D+1-D+3) is served");
        }
    }
}
