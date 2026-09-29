package com.saarthi.intelligence;

import com.saarthi.geo.GeographyService;
import com.saarthi.risks.SoilContext;
import com.saarthi.service.RealForecastService;
import com.saarthi.weather.LiveWeatherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * BUILD 1 shared context builder (additive).
 *
 * <p>Builds ONE {@link ForecastContext} from the already-retrieved live
 * forecast: cached bulk {@code LiveWeatherService.getForecast(false)} for the
 * 6 Sangrur blocks, or the uncached single-point centroid path
 * ({@code forecastForCentroid}) for generic registry blocks. Never issues a
 * second provider call per sector — callers share the returned context.
 *
 * <p>Wraps the legacy-frozen weather layer without modifying it. Missing
 * values stay {@code null}/unavailable (never zero-filled, never synthetic).
 */
@Service
public class ForecastContextService {

    private final LiveWeatherService live;
    private final RealForecastService blocks;
    private final GeographyService geography;
    private volatile SoilContext soilContext;

    @Autowired
    public ForecastContextService(LiveWeatherService live, RealForecastService blocks,
            GeographyService geography) {
        this.live = live;
        this.blocks = blocks;
        this.geography = geography;
    }

    @Autowired(required = false)
    public void setSoilContext(SoilContext soilContext) {
        this.soilContext = soilContext;
    }

    /** Test seam: collaborators may be partially null (degrades gracefully). */
    ForecastContextService(LiveWeatherService live, RealForecastService blocks,
            GeographyService geography, SoilContext soil) {
        this.live = live;
        this.blocks = blocks;
        this.geography = geography;
        this.soilContext = soil;
    }

    /**
     * Resolve a selection to a shared context. Accepted keys: legacy Sangrur
     * {@code block} name/id (case-insensitive), registry triple
     * ({@code state, district, block} codes), or centroid
     * ({@code lat, lon} + optional {@code name}). Exactly one of
     * {@code block} / triple / coords must identify the target.
     */
    public ForecastContext build(String block, String state, String district, String code,
            Double lat, Double lon, String name) {
        if (block != null && !block.isBlank()) {
            String canonical = blocks.findBlock(block)
                    .map(b -> java.util.Objects.toString(b.get("block_name"), null))
                    .orElseThrow(() -> new RealForecastService.BlockNotFoundException(block));
            LiveWeatherService.LiveForecast fc = live.getForecast(false);
            LiveWeatherService.BlockForecast b = fc.blocks().get(canonical);
            if (b == null) {
                throw new com.saarthi.weather.WeatherController.WeatherUnavailableException(
                        "No live data for block '" + canonical + "'");
            }
            GeographyService.BlockRef ref = geography.search(canonical).stream().findFirst().orElse(null);
            return assemble(canonical, canonical,
                    ref == null ? null : ref.stateCode(), ref == null ? null : ref.stateName(),
                    ref == null ? null : ref.districtCode(), ref == null ? null : ref.districtName(),
                    ref == null ? null : ref.blockCode(),
                    ref == null ? null : ref.latitude(), ref == null ? null : ref.longitude(),
                    ref == null ? "sangrur_legacy_polygon" : ref.locationMethod(),
                    fc.provider(), fc.model(), fc.issueDate(), fc.retrievedAt(), fc.stale(),
                    fc.staleWarning(), b, null);
        }
        if (lat != null || lon != null) {
            if (lat == null || lon == null) {
                throw new IllegalArgumentException(
                        "Query parameters 'lat' and 'lon' are both required for coordinate lookup");
            }
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
                throw new IllegalArgumentException(
                        "Coordinates out of range (" + lat + ", " + lon + ")");
            }
            String label = (name == null || name.isBlank())
                    ? String.format(java.util.Locale.ROOT, "centroid (%.5f, %.5f)", lat, lon)
                    : name.strip();
            LiveWeatherService.BlockForecast bf = live.forecastForCentroid(label, lat, lon);
            return forCentroid(bf, lat, lon, label);
        }
        if (state != null || district != null || code != null) {
            if (state == null || state.isBlank() || district == null || district.isBlank()
                    || code == null || code.isBlank()) {
                throw new IllegalArgumentException(
                        "Query parameters 'state', 'district' and 'block' (codes) are all required");
            }
            GeographyService.BlockRef ref = geography
                    .findBlock(state.trim(), district.trim(), code.trim())
                    .orElseThrow(() -> new com.saarthi.geo.GeographyController.UnknownGeographyException(
                            "unknown_block", "Unknown block triple '"
                                    + state.trim() + ":" + district.trim() + ":" + code.trim() + "'"));
            LiveWeatherService.BlockForecast bf =
                    live.forecastForCentroid(ref.blockName(), ref.latitude(), ref.longitude());
            return forRegistryRef(bf, ref);
        }
        throw new IllegalArgumentException(
                "Provide a block name/id (?block=), a registry triple (?state=&district=&block=), "
                        + "or coordinates (?lat=&lon=)");
    }

    ForecastContext forCentroid(LiveWeatherService.BlockForecast b,
            double lat, double lon, String label) {
        return assemble(b.blockName(), label, null, null, null, null, null,
                lat, lon, "single_point_centroid",
                live.providerName(), live.modelName(), b.issueDate(), b.retrievedAt(), false,
                null, b, null);
    }

    ForecastContext forRegistryRef(LiveWeatherService.BlockForecast b,
            GeographyService.BlockRef ref) {
        return assemble(b.blockName(), ref.blockName(),
                ref.stateCode(), ref.stateName(), ref.districtCode(), ref.districtName(),
                ref.blockCode(), ref.latitude(), ref.longitude(), ref.locationMethod(),
                live.providerName(), live.modelName(), b.issueDate(), b.retrievedAt(), false,
                null, b, ref);
    }

    ForecastContext assemble(String blockName, String displayName,
            String stateCode, String stateName, String distCode, String distName,
            String blockCode, Double lat, Double lon, String locMethod,
            String provider, String model, java.time.LocalDate issue,
            java.time.Instant retrieved, boolean stale, String staleWarn,
            LiveWeatherService.BlockForecast b, GeographyService.BlockRef ref) {
        List<ForecastContext.Day> days = new ArrayList<>();
        if (b.days() != null) {
            for (LiveWeatherService.BlockDaily d : b.days()) {
                days.add(new ForecastContext.Day(d.date(), d.horizonDay(), d.rainfallMm(),
                        d.rainProbabilityPct(), d.temperatureMaxC(), d.temperatureMinC(),
                        d.et0Mm(), d.soilMoisture0To7CmVwc(), d.rainMm(), d.showersMm(),
                        d.weatherCode()));
            }
        }
        List<Double> rain = new ArrayList<>();
        for (ForecastContext.Day d : days) rain.add(d.rainfallMm());
        List<Double> et0 = new ArrayList<>();
        for (ForecastContext.Day d : days) et0.add(d.et0Mm());
        Double rain3 = sumFirst(rain, 3);
        Double rain7 = sumFirst(rain, 7);
        Double et07 = sumFirst(et0, 7);
        Double balance = (rain7 != null && et07 != null) ? round1(rain7 - et07) : null;
        Double max = null;
        String maxDate = null;
        for (ForecastContext.Day d : days) {
            if (d.rainfallMm() != null && (max == null || d.rainfallMm() > max)) {
                max = d.rainfallMm();
                maxDate = d.date() == null ? null : d.date().toString();
            }
        }
        boolean soilOk = false;
        String soilLine = null;
        String soilReason = "Soil context resource unavailable";
        SoilContext ctx = soilContext;
        try {
            if (ctx != null) {
                String line = (ref == null) ? ctx.describe(blockName)
                        : ctx.lineForCoords(ref.latitude(), ref.longitude());
                if (line != null) { soilOk = true; soilLine = line; }
                else if (ref != null) { soilReason = "Soil data unavailable for this block"; }
            }
        } catch (Exception e) {
            soilOk = false;
            soilReason = "Soil lookup failed";
        }
        List<String> missing = new ArrayList<>();
        if (rain3 == null) missing.add("rain_3d_unavailable");
        if (rain7 == null) missing.add("rain_7d_unavailable");
        if (et07 == null) missing.add("et0_7d_unavailable");
        Double moist0 = days.isEmpty() ? null : days.get(0).soilMoisture0To7CmVwc();
        if (moist0 == null) missing.add("soil_moisture_day0_unavailable");
        if (!soilOk) missing.add("soil_line_unavailable");
        return new ForecastContext(blockName, displayName, stateCode, stateName,
                distCode, distName, blockCode, lat, lon, locMethod, provider, model,
                issue, retrieved, stale, staleWarn, b.spatialMethod(), List.copyOf(days),
                rain3, rain7, et07, balance, max, maxDate,
                countWet(rain, 3), countWet(rain, 7), countDry(rain, 7),
                longestDryRun(rain, 7), trailingDryRun(rain), moist0,
                soilOk, soilLine, soilReason, List.copyOf(missing));
    }

    static Double sumFirst(List<Double> vals, int n) {
        if (vals == null || vals.size() < n) return null;
        double s = 0;
        for (int i = 0; i < n; i++) {
            Double v = vals.get(i);
            if (v == null) return null;
            s += v;
        }
        return round1(s);
    }

    static Integer countWet(List<Double> vals, int n) {
        if (vals == null || vals.size() < n) return null;
        int c = 0;
        for (int i = 0; i < n; i++) {
            Double v = vals.get(i);
            if (v == null) return null;
            if (ForecastContext.isWet(v)) c++;
        }
        return c;
    }

    static Integer countDry(List<Double> vals, int n) {
        if (vals == null || vals.size() < n) return null;
        int c = 0;
        for (int i = 0; i < n; i++) {
            Double v = vals.get(i);
            if (v == null) return null;
            if (ForecastContext.isDry(v)) c++;
        }
        return c;
    }

    static Integer longestDryRun(List<Double> vals, int n) {
        if (vals == null || vals.size() < n) return null;
        int best = 0;
        int cur = 0;
        for (int i = 0; i < n; i++) {
            Double v = vals.get(i);
            if (v == null) return null;
            if (ForecastContext.isDry(v)) { cur++; best = Math.max(best, cur); }
            else { cur = 0; }
        }
        return best;
    }

    static Integer trailingDryRun(List<Double> vals) {
        if (vals == null || vals.isEmpty()) return null;
        int n = 0;
        for (int i = vals.size() - 1; i >= 0; i--) {
            Double v = vals.get(i);
            if (v == null) return null;
            if (ForecastContext.isDry(v)) n++;
            else break;
        }
        return n;
    }

    static Double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
