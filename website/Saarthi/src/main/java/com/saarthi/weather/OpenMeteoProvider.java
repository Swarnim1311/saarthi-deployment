package com.saarthi.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@link WeatherProvider} backed by Open-Meteo (data-delivery layer) serving
 * the ECMWF IFS numerical weather prediction model.
 *
 * <p>Endpoint: {@code GET {baseUrl}/v1/forecast} with
 * {@code models=ecmwf_ifs}, {@code timezone=Asia/Kolkata},
 * {@code forecast_days=16}. Daily {@code precipitation_sum} (mm, already
 * aggregated by Open-Meteo to Asia/Kolkata calendar days) is authoritative
 * for block rainfall; hourly precipitation is fetched alongside so daily
 * totals can be cross-checked and day-boundary behaviour tested.
 *
 * <p>Live-verified 2026-09-19 (single bounded request, Sangrur 30.24/75.89,
 * HTTP 200, keyless): 16 daily dates, 384 hourly steps, units mm/°C/%/km/h,
 * {@code timezone=Asia/Kolkata} (utc_offset 19800), daily keys exactly
 * {@code time/precipitation_sum/precipitation_probability_max/temperature_2m_max/temperature_2m_min},
 * trailing-day {@code precipitation_sum=null} (missing stays null upstream —
 * never zero-filled here). {@code models=ecmwf_ifs} accepted; the response
 * carries no per-run model initialisation timestamp (only
 * {@code generationtime_ms}), so run time is recorded as unknown and
 * {@code retrieved_at} is the freshness anchor — see
 * {@code docs/project_context/11_LIVE_WEATHER_ARCHITECTURE.md}.
 */
@Component
public class OpenMeteoProvider implements WeatherProvider {

    public static final String PROVIDER_NAME = "Open-Meteo";
    /** ECMWF IFS via Open-Meteo {@code models=ecmwf_ifs} selector. */
    public static final String MODEL_NAME = "ECMWF IFS (ecmwf_ifs)";
    public static final String TIMEZONE = "Asia/Kolkata";
    public static final int FORECAST_DAYS = 16;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    @Value("${saarthi.weather.base-url:https://api.open-meteo.com}")
    private String baseUrl;

    @Value("${saarthi.weather.timeout-seconds:25}")
    private int timeoutSeconds;

    public OpenMeteoProvider() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Test seam: inject base URL / timeout without Spring. */
    OpenMeteoProvider(String baseUrl, int timeoutSeconds, HttpClient httpClient) {
        this.baseUrl = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
        this.httpClient = httpClient;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }

    @Override
    public List<LocationDailyForecast> fetch(List<SamplePoint> points) throws WeatherProviderException {
        if (points == null || points.isEmpty()) {
            throw new WeatherProviderException("No sample points requested");
        }
        String url = buildUrl(points);
        String body = get(url);
        return parseResponse(body, points.size());
    }

    String buildUrl(List<SamplePoint> points) {
        String lat = points.stream()
                .map(p -> String.format(Locale.ROOT, "%.5f", p.latitude()))
                .collect(Collectors.joining(","));
        String lon = points.stream()
                .map(p -> String.format(Locale.ROOT, "%.5f", p.longitude()))
                .collect(Collectors.joining(","));
        String hourly = "precipitation,temperature_2m,relative_humidity_2m,precipitation_probability,"
                + "wind_speed_10m,soil_moisture_0_to_7cm,soil_temperature_0_to_7cm";
        String daily = "precipitation_sum,precipitation_probability_max,temperature_2m_max,"
                + "temperature_2m_min,rain_sum,showers_sum,weather_code,et0_fao_evapotranspiration";
        return baseUrl + "/v1/forecast?latitude=" + enc(lat)
                + "&longitude=" + enc(lon)
                + "&hourly=" + enc(hourly)
                + "&daily=" + enc(daily)
                + "&timezone=" + enc(TIMEZONE)
                + "&forecast_days=" + FORECAST_DAYS
                + "&models=ecmwf_ifs";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String get(String url) throws WeatherProviderException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp;
        try {
            resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new WeatherProviderException("Open-Meteo request timed out after " + timeoutSeconds + "s", e);
        } catch (Exception e) {
            throw new WeatherProviderException("Open-Meteo request failed (network): " + e.getMessage(), e);
        }
        if (resp.statusCode() == 429) {
            throw new WeatherProviderException("Open-Meteo rate limit hit (HTTP 429); retry after backoff");
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new WeatherProviderException("Open-Meteo HTTP " + resp.statusCode()
                    + ": " + snip(resp.body()));
        }
        if (resp.body() == null || resp.body().isBlank()) {
            throw new WeatherProviderException("Open-Meteo returned an empty body");
        }
        return resp.body();
    }

    private static String snip(String body) {
        if (body == null) return "<empty>";
        return body.length() > 300 ? body.substring(0, 300) + "…" : body;
    }

    /**
     * Parse an Open-Meteo forecast response. Single-coordinate responses are
     * a JSON object; multi-coordinate responses are a JSON array. Missing
     * precipitation stays {@code null} — never zero-filled.
     */
    List<LocationDailyForecast> parseResponse(String body, int expectedPoints) throws WeatherProviderException {
        try {
            JsonNode root = mapper.readTree(body);
            if (root.has("error") && root.path("error").asBoolean(false)) {
                throw new WeatherProviderException("Open-Meteo error: " + root.path("reason").asText("unknown"));
            }
            List<LocationDailyForecast> out = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode loc : root) out.add(parseLocation(loc));
            } else {
                out.add(parseLocation(root));
            }
            if (out.isEmpty()) {
                throw new WeatherProviderException("Open-Meteo response contained no locations");
            }
            return out;
        } catch (WeatherProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new WeatherProviderException("Malformed Open-Meteo response: " + e.getMessage(), e);
        }
    }

    private LocationDailyForecast parseLocation(JsonNode loc) throws WeatherProviderException {
        double lat = loc.path("latitude").asDouble(Double.NaN);
        double lon = loc.path("longitude").asDouble(Double.NaN);
        JsonNode daily = loc.path("daily");
        if (daily.isMissingNode() || !daily.has("time")) {
            throw new WeatherProviderException("Open-Meteo location missing 'daily' forecast");
        }
        List<DailyPointValues> days = parseDaily(daily, loc.path("hourly"));
        if (days.isEmpty()) {
            throw new WeatherProviderException("Open-Meteo location has zero daily entries");
        }
        return new LocationDailyForecast(lat, lon, days);
    }

    static List<DailyPointValues> parseDaily(JsonNode daily) {
        return parseDaily(daily, null);
    }

    /**
     * Parse the daily block, joining hourly-derived daily means for the
     * variables Open-Meteo serves hourly only (humidity, wind, 0–7 cm soil
     * moisture). Every joined value is {@code null} when no hour of the local
     * day carries data — missing is never zero-filled.
     */
    static List<DailyPointValues> parseDaily(JsonNode daily, JsonNode hourly) {
        List<String> times = toStringList(daily.path("time"));
        List<Double> precip = toDoubleList(daily.path("precipitation_sum"));
        List<Double> prob = toDoubleList(daily.path("precipitation_probability_max"));
        List<Double> tMax = toDoubleList(daily.path("temperature_2m_max"));
        List<Double> tMin = toDoubleList(daily.path("temperature_2m_min"));
        List<Double> rain = toDoubleList(daily.path("rain_sum"));
        List<Double> showers = toDoubleList(daily.path("showers_sum"));
        List<Double> et0 = toDoubleList(daily.path("et0_fao_evapotranspiration"));
        List<Integer> codes = toIntList(daily.path("weather_code"));
        java.util.Map<LocalDate, Double> humidityByDate = hourlyDailyMean(hourly, "relative_humidity_2m");
        java.util.Map<LocalDate, Double> windByDate = hourlyDailyMean(hourly, "wind_speed_10m");
        java.util.Map<LocalDate, Double> soilMoistureByDate = hourlyDailyMean(hourly, "soil_moisture_0_to_7cm");
        List<DailyPointValues> out = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            LocalDate date;
            try {
                date = LocalDate.parse(times.get(i).substring(0, 10));
            } catch (Exception e) {
                continue; // skip unparsable date entries, keep the rest honest
            }
            out.add(new DailyPointValues(
                    date,
                    at(precip, i), at(prob, i), at(tMax, i), at(tMin, i),
                    humidityByDate.get(date), windByDate.get(date),
                    at(et0, i), soilMoistureByDate.get(date),
                    at(rain, i), at(showers, i), atInt(codes, i)));
        }
        return out;
    }

    /**
     * Aggregate hourly precipitation into Asia/Kolkata calendar-day totals.
     * Hourly {@code time} stamps are already provider-localised when
     * {@code timezone=Asia/Kolkata} is requested, so grouping by the date
     * part is the documented local-day aggregation. Used to cross-check the
     * authoritative {@code daily.precipitation_sum}.
     */
    static java.util.Map<LocalDate, Double> aggregateHourlyToDaily(JsonNode hourly) {
        List<String> times = toStringList(hourly.path("time"));
        List<Double> precip = toDoubleList(hourly.path("precipitation"));
        java.util.Map<LocalDate, Double> totals = new java.util.TreeMap<>();
        for (int i = 0; i < times.size(); i++) {
            Double v = at(precip, i);
            if (v == null) continue; // missing hours are skipped, never zero-filled
            LocalDate d = LocalDate.parse(times.get(i).substring(0, 10));
            totals.merge(d, v, Double::sum);
        }
        return totals;
    }

    /**
     * Mean of an hourly variable per Asia/Kolkata calendar day. Hours with
     * {@code null} are skipped; a day with no valid hour is ABSENT from the
     * map (callers store {@code null}) — never zero-filled.
     */
    static java.util.Map<LocalDate, Double> hourlyDailyMean(JsonNode hourly, String variable) {
        java.util.Map<LocalDate, Double> out = new java.util.TreeMap<>();
        if (hourly == null || hourly.isMissingNode()) return out;
        List<String> times = toStringList(hourly.path("time"));
        List<Double> values = toDoubleList(hourly.path(variable));
        java.util.Map<LocalDate, double[]> acc = new java.util.TreeMap<>();
        for (int i = 0; i < times.size(); i++) {
            Double v = at(values, i);
            if (v == null) continue;
            LocalDate d;
            try {
                d = LocalDate.parse(times.get(i).substring(0, 10));
            } catch (Exception e) {
                continue;
            }
            double[] slot = acc.computeIfAbsent(d, k -> new double[2]);
            slot[0] += v;
            slot[1] += 1;
        }
        for (java.util.Map.Entry<LocalDate, double[]> e : acc.entrySet()) {
            if (e.getValue()[1] > 0) {
                out.put(e.getKey(), e.getValue()[0] / e.getValue()[1]);
            }
        }
        return out;
    }

    private static Integer atInt(List<Integer> list, int i) {
        return (i < list.size()) ? list.get(i) : null;
    }

    private static List<Integer> toIntList(JsonNode node) {
        List<Integer> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode v : node) out.add((v == null || v.isNull()) ? null : v.asInt());
        }
        return out;
    }

    private static Double at(List<Double> list, int i) {
        return (i < list.size()) ? list.get(i) : null;
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode v : node) out.add(v.isNull() ? null : v.asText());
        }
        return out;
    }

    private static List<Double> toDoubleList(JsonNode node) {
        List<Double> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode v : node) out.add((v == null || v.isNull()) ? null : v.asDouble());
        }
        return out;
    }
}
