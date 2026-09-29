package com.saarthi.weather;

import com.saarthi.risks.FieldShadowService;
import com.saarthi.service.RealForecastService;
import com.saarthi.shadow.ShadowCaptureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Live block-level rainfall engine.
 *
 * <pre>
 *   Open-Meteo (ECMWF IFS) → sample points → block mean → daily forecast
 * </pre>
 *
 * <ul>
 *   <li>Daily rainfall = mean of {@code daily.precipitation_sum} across the
 *       block's inside-polygon sample points (Asia/Kolkata calendar days,
 *       provided pre-aggregated by Open-Meteo). Null point values are
 *       ignored; a day that is null at every point stays null (never zero).</li>
 *   <li>Horizon: 16 daily dates as served (days 1–7 operational, 8–15
 *       extended/lower-confidence, 16–30 NOT available — exposed as
 *       {@code available:false}, never synthesised).</li>
 *   <li>Cache: one provider call serves all blocks; results cached for
 *       {@code saarthi.weather.cache-ttl-minutes} (default 60). On provider
 *       failure a cached value is served ONLY with {@code stale:true} and an
 *       explicit warning; with no cache the failure surfaces as 502.</li>
 * </ul>
 */
@Service
public class LiveWeatherService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final WeatherProvider provider;
    private final BlockSampler sampler;

    @Value("${saarthi.weather.cache-ttl-minutes:60}")
    private long cacheTtlMinutes = 60;

    private volatile CachedForecast cache;

    /**
     * Optional live-IFS shadow hook (validation/data-collection only).
     * Setter-injected so the weather path works with or without it; the hook
     * itself is fail-soft and can never break forecast serving.
     */
    private volatile ShadowCaptureService shadow;

    @Autowired(required = false)
    public void setShadowCapture(ShadowCaptureService shadow) {
        this.shadow = shadow;
    }

    /**
     * Optional FIELD_HIGH shadow hook (separate field ledger, same fail-soft
     * contract as the dry-spell hook; the dry-spell path is untouched).
     */
    private volatile FieldShadowService fieldShadow;

    @Autowired(required = false)
    public void setFieldShadow(FieldShadowService fieldShadow) {
        this.fieldShadow = fieldShadow;
    }

    @Autowired
    public LiveWeatherService(WeatherProvider provider, BlockSampler sampler) {
        this.provider = provider;
        this.sampler = sampler;
    }

    /** Test seam (public so additive risk/shadow packages can build fixtures). */
    public LiveWeatherService(WeatherProvider provider, BlockSampler sampler, long cacheTtlMinutes) {
        this.provider = provider;
        this.sampler = sampler;
        this.cacheTtlMinutes = cacheTtlMinutes;
    }

    /** Full live forecast for all 6 blocks (cached when fresh). */
    public synchronized LiveForecast getForecast(boolean forceRefresh) {
        CachedForecast c = cache;
        if (!forceRefresh && c != null && !isExpired(c)) {
            return c.forecast();
        }
        try {
            LiveForecast fresh = fetchFresh();
            cache = new CachedForecast(fresh, Instant.now());
            ShadowCaptureService s = shadow;
            if (s != null) s.tryCapture(fresh); // fail-soft: never breaks serving
            FieldShadowService f = fieldShadow;
            if (f != null) f.tryCapture(fresh); // fail-soft: separate field ledger
            return fresh;
        } catch (WeatherProviderException e) {
            if (c != null) {
                // Explicit stale serve: never silent, warning travels with the payload.
                return c.forecast().withStaleWarning(
                        "Serving cached forecast: live provider failed (" + e.getMessage() + ")");
            }
            throw e;
        }
    }

    /** Provider/model labels for dynamic (uncached) responses. */
    public String providerName() {
        return provider.providerName();
    }

    /** Provider/model labels for dynamic (uncached) responses. */
    public String modelName() {
        return provider.modelName();
    }

    /**
     * One-off live forecast for an arbitrary registry centroid (dynamic
     * India-wide path): a single provider point aggregated with the
     * {@code single_point_centroid} label — never polygon-averaged, same
     * 16-day contract fields as block forecasts.
     *
     * <p>Uncached by design: unbounded block cardinality would poison the
     * bulk cache. Callers needing freshness guarantees must re-request.
     * Provider failure surfaces as {@link WeatherProviderException} (502),
     * never synthetic data.
     */
    public BlockForecast forecastForCentroid(String blockName, double lat, double lon) {
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new IllegalArgumentException(
                    "Coordinates out of range (" + lat + ", " + lon + ")");
        }
        String name = (blockName == null || blockName.isBlank())
                ? String.format(java.util.Locale.ROOT, "centroid (%.5f, %.5f)", lat, lon)
                : blockName.strip();
        List<WeatherProvider.LocationDailyForecast> locs =
                provider.fetch(List.of(new WeatherProvider.SamplePoint(lat, lon)));
        if (locs.isEmpty()) {
            throw new WeatherProviderException("Provider returned no locations for centroid");
        }
        return aggregateBlock(name, locs, BlockSampler.CENTROID_METHOD,
                LocalDate.now(IST), Instant.now());
    }

    /** Freshness of the currently cached forecast (no fetch). */
    public synchronized Map<String, Object> getFreshness() {
        Map<String, Object> out = new LinkedHashMap<>();
        CachedForecast c = cache;
        if (c == null) {
            out.put("available", false);
            out.put("reason", "No live forecast retrieved yet; call GET /api/weather/forecast first");
            return out;
        }
        out.put("available", true);
        out.put("provider", provider.providerName());
        out.put("model", provider.modelName());
        out.put("model_run_time", null);
        out.put("model_run_note", "Open-Meteo exposes no per-run initialisation timestamp; retrieved_at is the freshness anchor");
        out.put("retrieved_at", c.retrievedAt.toString());
        out.put("age_minutes", ageMinutes(c));
        out.put("stale", isExpired(c));
        out.put("cache_ttl_minutes", cacheTtlMinutes);
        out.put("issue_date", c.forecast.issueDate().toString());
        return out;
    }

    private boolean isExpired(CachedForecast c) {
        return ageMinutes(c) >= cacheTtlMinutes;
    }

    private long ageMinutes(CachedForecast c) {
        return java.time.Duration.between(c.retrievedAt, Instant.now()).toMinutes();
    }

    private LiveForecast fetchFresh() {
        List<BlockSampler.SampleRef> refs = sampler.allPoints();
        List<WeatherProvider.SamplePoint> points = refs.stream()
                .map(BlockSampler.SampleRef::point).toList();
        List<WeatherProvider.LocationDailyForecast> locs = provider.fetch(points);
        if (locs.size() != points.size()) {
            throw new WeatherProviderException("Provider returned " + locs.size()
                    + " locations for " + points.size() + " sample points");
        }
        // Group location forecasts by block, preserving provider order.
        // Legacy order first (identical for the 6 Sangrur blocks), then any
        // extra sampler keys — the bulk path stays Sangrur-only in practice.
        List<String> order = new ArrayList<>();
        for (String b : RealForecastService.BLOCKS) {
            if (sampler.samples().containsKey(b)) order.add(b);
        }
        for (String b : sampler.samples().keySet()) {
            if (!order.contains(b)) order.add(b);
        }
        Map<String, List<WeatherProvider.LocationDailyForecast>> byBlock = new LinkedHashMap<>();
        for (String b : order) byBlock.put(b, new ArrayList<>());
        for (int i = 0; i < refs.size(); i++) {
            byBlock.computeIfAbsent(refs.get(i).blockName(), k -> new ArrayList<>())
                    .add(locs.get(i));
        }
        LocalDate issueDate = LocalDate.now(IST);
        Instant retrievedAt = Instant.now();
        Map<String, BlockForecast> blocks = new LinkedHashMap<>();
        for (String b : order) {
            BlockSampler.BlockSamples s = sampler.samples().get(b);
            String method = (s == null) ? "unknown" : s.spatialMethod();
            blocks.put(b, aggregateBlock(b, byBlock.get(b), method, issueDate, retrievedAt));
        }
        return new LiveForecast(provider.providerName(), provider.modelName(), issueDate,
                retrievedAt, false, null, blocks);
    }

    /** Mean across sample points per date; all-null day stays null. Package-visible for tests. */
    static BlockForecast aggregateBlock(String blockName,
            List<WeatherProvider.LocationDailyForecast> locs,
            String spatialMethod, LocalDate issueDate, Instant retrievedAt) {
        Map<LocalDate, List<Double>> rainByDate = new TreeMap<>();
        Map<LocalDate, List<Double>> probByDate = new TreeMap<>();
        Map<LocalDate, List<Double>> tMaxByDate = new TreeMap<>();
        Map<LocalDate, List<Double>> tMinByDate = new TreeMap<>();
        Map<LocalDate, List<Double>> et0ByDate = new TreeMap<>();
        Map<LocalDate, List<Double>> soilMoistByDate = new TreeMap<>();
        Map<LocalDate, List<Double>> rainSplitByDate = new TreeMap<>();
        Map<LocalDate, List<Double>> showersByDate = new TreeMap<>();
        Map<LocalDate, List<Integer>> codeByDate = new TreeMap<>();
        for (WeatherProvider.LocationDailyForecast loc : locs) {
            for (WeatherProvider.DailyPointValues d : loc.days()) {
                add(rainByDate, d.date(), d.precipitationMm());
                add(probByDate, d.date(), d.precipitationProbability());
                add(tMaxByDate, d.date(), d.temperatureMaxC());
                add(tMinByDate, d.date(), d.temperatureMinC());
                add(et0ByDate, d.date(), d.et0Mm());
                add(soilMoistByDate, d.date(), d.soilMoisture0To7CmVwc());
                add(rainSplitByDate, d.date(), d.rainMm());
                add(showersByDate, d.date(), d.showersMm());
                addInt(codeByDate, d.date(), d.weatherCode());
            }
        }
        List<BlockDaily> days = new ArrayList<>();
        int horizon = 0;
        for (Map.Entry<LocalDate, List<Double>> e : rainByDate.entrySet()) {
            horizon++;
            LocalDate date = e.getKey();
            days.add(new BlockDaily(
                    date, horizon,
                    meanOrNull(e.getValue()),
                    meanOrNull(probByDate.getOrDefault(date, List.of())),
                    meanOrNull(tMaxByDate.getOrDefault(date, List.of())),
                    meanOrNull(tMinByDate.getOrDefault(date, List.of())),
                    meanOrNull(et0ByDate.getOrDefault(date, List.of())),
                    meanOrNull(soilMoistByDate.getOrDefault(date, List.of())),
                    meanOrNull(rainSplitByDate.getOrDefault(date, List.of())),
                    meanOrNull(showersByDate.getOrDefault(date, List.of())),
                    modalOrNull(codeByDate.getOrDefault(date, List.of()))));
        }
        return new BlockForecast(blockName, days,
                cumulative(days, 3), cumulative(days, 7), cumulative(days, 15),
                cumulativeEt0(days, 7), spatialMethod, issueDate, retrievedAt);
    }

    /** Accumulates a value for a date; a {@code null} value registers the date with no value. */
    private static void add(Map<LocalDate, List<Double>> map, LocalDate date, Double v) {
        List<Double> list = map.computeIfAbsent(date, k -> new ArrayList<>());
        if (v != null) list.add(v);
    }

    private static void addInt(Map<LocalDate, List<Integer>> map, LocalDate date, Integer v) {
        List<Integer> list = map.computeIfAbsent(date, k -> new ArrayList<>());
        if (v != null) list.add(v);
    }

    /** Most frequent value across sample points (WMO weather code), {@code null} when absent. */
    static Integer modalOrNull(List<Integer> values) {
        if (values == null || values.isEmpty()) return null;
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Integer v : values) counts.merge(v, 1, Integer::sum);
        Integer best = null;
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    /**
     * ET0 total over the first {@code n} horizon days. Unavailable unless all
     * {@code n} days carry an ET0 value — a partial sum would understate demand.
     */
    static Double cumulativeEt0(List<BlockDaily> days, int n) {
        if (days.size() < n) return null;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            Double v = days.get(i).et0Mm();
            if (v == null) return null;
            sum += v;
        }
        return sum;
    }

    static Double meanOrNull(List<Double> values) {
        if (values == null || values.isEmpty()) return null;
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.size();
    }

    /**
     * Cumulative rainfall over the first {@code n} horizon days. Returns null
     * (unavailable) unless all {@code n} days exist with non-null rainfall —
     * never zero-fills missing future days.
     */
    static Double cumulative(List<BlockDaily> days, int n) {
        if (days.size() < n) return null;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            Double v = days.get(i).rainfallMm();
            if (v == null) return null;
            sum += v;
        }
        return sum;
    }

    // ---- DTO records (serialised by Jackson) ----

    public record LiveForecast(String provider, String model, LocalDate issueDate,
            Instant retrievedAt, boolean stale, String staleWarning,
            Map<String, BlockForecast> blocks) {
        /** Horizon actually served (16 for ECMWF IFS via Open-Meteo). */
        public int horizonDays() {
            return blocks.values().stream().mapToInt(b -> b.days().size()).max().orElse(0);
        }

        LiveForecast withStaleWarning(String warning) {
            return new LiveForecast(provider, model, issueDate, retrievedAt, true, warning, blocks);
        }
    }

    public record BlockForecast(String blockName, List<BlockDaily> days,
            Double cum3Mm, Double cum7Mm, Double cum15Mm, Double et0_7dMm,
            String spatialMethod, LocalDate issueDate, Instant retrievedAt) {
        /** Back-compatible constructor: ET0 total unknown. */
        public BlockForecast(String blockName, List<BlockDaily> days,
                Double cum3Mm, Double cum7Mm, Double cum15Mm,
                String spatialMethod, LocalDate issueDate, Instant retrievedAt) {
            this(blockName, days, cum3Mm, cum7Mm, cum15Mm, null,
                    spatialMethod, issueDate, retrievedAt);
        }

        /** 30-day outlook is NOT served: provider horizon is 16 days; no synthesis. */
        public Map<String, Object> cum30() {
            return Map.of("available", false,
                    "reason", "30-day deterministic rainfall not available from the 16-day ECMWF IFS feed; "
                            + "days 16–30 require a probabilistic/climate-informed outlook (Phase 3), not synthesised here");
        }
    }

    /**
     * One block-level forecast day: rainfall/probability (the operational
     * fields) plus agronomic extras (temperature, ET0, 0–7 cm soil moisture,
     * rain/showers split, WMO weather code). Extras are {@code null} whenever
     * the provider did not return them — never zero-filled.
     */
    public record BlockDaily(LocalDate date, int horizonDay,
            Double rainfallMm, Double rainProbabilityPct,
            Double temperatureMaxC, Double temperatureMinC,
            Double et0Mm, Double soilMoisture0To7CmVwc,
            Double rainMm, Double showersMm, Integer weatherCode) {

        /** Back-compatible 4-argument construction (agronomic extras absent). */
        public BlockDaily(LocalDate date, int horizonDay,
                Double rainfallMm, Double rainProbabilityPct) {
            this(date, horizonDay, rainfallMm, rainProbabilityPct,
                    null, null, null, null, null, null, null);
        }
    }

    private record CachedForecast(LiveForecast forecast, Instant retrievedAt) {
    }
}
