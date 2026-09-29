package com.saarthi.weather;

import java.time.LocalDate;
import java.util.List;

/**
 * Abstraction over an external numerical-weather-prediction delivery layer
 * (e.g. Open-Meteo serving ECMWF IFS). SAARTHI never does NWP itself; this
 * provider only fetches and normalises third-party forecasts.
 *
 * <p>Implementations must NOT invent values: missing precipitation is
 * returned as {@code null} (never zero-filled), and provider/model/run
 * metadata is preserved for reproducibility and stale-data detection.
 */
public interface WeatherProvider {

    /** Stable provider display name, e.g. {@code "Open-Meteo"}. */
    String providerName();

    /** NWP model served, e.g. {@code "ECMWF IFS (ecmwf_ifs)"}. Never the delivery layer itself. */
    String modelName();

    /**
     * Fetch normalised daily forecasts for the given sample points.
     * Implementations should batch all points into the fewest HTTP calls
     * the provider supports (Open-Meteo accepts comma-separated coordinates).
     *
     * @throws WeatherProviderException on timeout, network failure,
     *         malformed response or missing precipitation data
     */
    List<LocationDailyForecast> fetch(List<SamplePoint> points) throws WeatherProviderException;

    /** Single geographic sample point. */
    record SamplePoint(double latitude, double longitude) {}

    /** Normalised daily forecast for one sampled location. */
    record LocationDailyForecast(
            double latitude,
            double longitude,
            List<DailyPointValues> days) {}

    /**
     * One calendar-day forecast (Asia/Kolkata local date) at one point.
     *
     * <p>Agronomic extras (ET0, surface soil moisture, rain/showers split,
     * WMO weather code) come from the SAME provider request — never a second
     * HTTP call. Every extra stays {@code null} when the provider does not
     * return it (missing is never zero-filled).
     */
    record DailyPointValues(
            LocalDate date,
            Double precipitationMm,
            Double precipitationProbability,
            Double temperatureMaxC,
            Double temperatureMinC,
            Double humidityPct,
            Double windSpeedKmh,
            Double et0Mm,
            Double soilMoisture0To7CmVwc,
            Double rainMm,
            Double showersMm,
            Integer weatherCode) {

        /** Back-compatible 7-argument construction: agronomic extras default to {@code null}. */
        public DailyPointValues(LocalDate date, Double precipitationMm,
                Double precipitationProbability, Double temperatureMaxC,
                Double temperatureMinC, Double humidityPct, Double windSpeedKmh) {
            this(date, precipitationMm, precipitationProbability, temperatureMaxC,
                    temperatureMinC, humidityPct, windSpeedKmh, null, null, null, null, null);
        }
    }
}
