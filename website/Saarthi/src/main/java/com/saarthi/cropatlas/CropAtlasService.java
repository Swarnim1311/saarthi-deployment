package com.saarthi.cropatlas;

import com.saarthi.geo.GeographyController;
import com.saarthi.geo.GeographyService;
import com.saarthi.risks.ClimatologyContext;
import com.saarthi.weather.LiveWeatherService;
import com.saarthi.weather.WeatherController;
import com.saarthi.weather.WeatherProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Builds ONE environmental context per CropAtlas request, reusing the existing
 * SAARTHI services. No second geography, weather or soil stack is created.
 *
 * <p>Sources, all pre-existing except elevation:
 * <ul>
 *   <li>{@link GeographyService} — the single block registry (identity + centroid).</li>
 *   <li>{@link LiveWeatherService} — the live Open-Meteo / ECMWF IFS forecast,
 *       shared cache, unmodified. One call per request; sectors/crops share it.</li>
 *   <li>{@link CropAtlasSoilSource} — SoilGrids point query, else bundled block means.</li>
 *   <li>{@link ClimatologyContext} — deployment DOY rainfall normals (six legacy blocks).</li>
 *   <li>{@link ElevationClient} — keyless Open-Meteo elevation (Copernicus DEM),
 *       cached per coordinate, fail-soft. The only new source in this build.</li>
 * </ul>
 *
 * <p>Selection uses the same identity pattern as
 * {@code /api/weather/*} and {@code /api/intelligence/*}: a registry triple
 * (state/district/block code) or coordinates. Legacy Sangrur block names are
 * supported through the existing forecast path so their richer polygon sampling
 * and bundled soil means are preserved.
 *
 * <p>Fail-soft where a source is optional (soil, climatology) and explicit where
 * the live forecast is the point of the page (an unavailable forecast surfaces as
 * HTTP 503, never as fabricated data).
 */
@Service
public class CropAtlasService {

    private static final Logger log = LoggerFactory.getLogger(CropAtlasService.class);

    private final LiveWeatherService live;
    private final GeographyService geography;
    private final ClimatologyContext climatology;
    private final CropAtlasSoilSource soilSource;
    private volatile ElevationClient elevation;

    @Autowired
    public CropAtlasService(LiveWeatherService live, GeographyService geography,
            ClimatologyContext climatology, CropAtlasSoilSource soilSource) {
        this.live = live;
        this.geography = geography;
        this.climatology = climatology;
        this.soilSource = soilSource;
    }

    @Autowired(required = false)
    public void setElevation(ElevationClient elevation) {
        this.elevation = elevation;
    }

    /** Resolved selection plus the measured fingerprint for it. */
    public record Context(LocalDate asOf, Map<String, Object> location,
                          EnvironmentalFingerprint fingerprint) {}

    /**
     * Resolve a selection to a measured environmental fingerprint.
     *
     * @param state  state code, or {@code null}
     * @param district district code, or {@code null}
     * @param code   block code, or {@code null}
     * @param block  legacy block name, or {@code null}
     */
    public Context context(String state, String district, String code, String block) {
        if (block != null && !block.isBlank()) {
            return legacyContext(block.trim());
        }
        if (state != null || district != null || code != null) {
            if (isBlank(state) || isBlank(district) || isBlank(code)) {
                throw new IllegalArgumentException(
                        "Query parameters 'state', 'district' and 'code' are all required");
            }
            GeographyService.BlockRef ref = geography
                    .findBlock(state.trim(), district.trim(), code.trim())
                    .orElseThrow(() -> new GeographyController.UnknownGeographyException(
                            "unknown_block", "Unknown block triple '"
                                    + state.trim() + ":" + district.trim() + ":" + code.trim() + "'"));
            return centroidContext(ref, ref.locationMethod());
        }
        throw new IllegalArgumentException(
                "Provide a registry triple (?state=&district=&code=) or a legacy block name (?block=)");
    }

    private Context legacyContext(String block) {
        LiveWeatherService.LiveForecast cached = live.getForecast(false);
        if (cached == null || cached.blocks() == null) {
            throw new WeatherController.WeatherUnavailableException(
                    "Live forecast is currently unavailable");
        }
        String canonical = cached.blocks().keySet().stream()
                .filter(k -> k != null && k.equalsIgnoreCase(block))
                .findFirst()
                .orElseThrow(() -> new GeographyController.UnknownGeographyException(
                        "unknown_block", "Unknown legacy block '" + block + "'"));
        GeographyService.BlockRef ref = geography.search(canonical).stream()
                .filter(b -> b.blockName() != null && b.blockName().equalsIgnoreCase(canonical))
                .findFirst().orElse(null);
        return contextFor(canonical, ref, cached, ref == null ? "sangrur_legacy_polygon" : ref.locationMethod());
    }

    private Context centroidContext(GeographyService.BlockRef ref, String locationMethod) {
        LiveWeatherService.BlockForecast bf =
                live.forecastForCentroid(ref.blockName(), ref.latitude(), ref.longitude());
        return contextFor(ref.blockName(), ref, bf, locationMethod);
    }

    private Context contextFor(String blockName, GeographyService.BlockRef ref,
            Object forecastOrLive, String locationMethod) {
        List<LiveWeatherService.BlockDaily> days;
        String provider;
        String model;
        String issueDate;
        String spatialMethod;
        boolean stale;

        if (forecastOrLive instanceof LiveWeatherService.LiveForecast lf) {
            LiveWeatherService.BlockForecast b = lf.blocks().get(blockName);
            if (b == null) {
                throw new WeatherController.WeatherUnavailableException(
                        "No live data for block '" + blockName + "'");
            }
            days = b.days();
            provider = lf.provider();
            model = lf.model();
            issueDate = lf.issueDate() == null ? null : lf.issueDate().toString();
            spatialMethod = b.spatialMethod();
            stale = lf.stale();
        } else if (forecastOrLive instanceof LiveWeatherService.BlockForecast bf) {
            days = bf.days();
            provider = live.providerName();
            model = live.modelName();
            issueDate = bf.issueDate() == null ? null : bf.issueDate().toString();
            spatialMethod = bf.spatialMethod();
            stale = false;
        } else {
            throw new WeatherController.WeatherUnavailableException(
                    "Live forecast is currently unavailable");
        }

        Double lat = ref == null ? null : ref.latitude();
        Double lon = ref == null ? null : ref.longitude();
        // Soil and elevation are independent of the forecast aggregation:
        // fetch them concurrently so one slow upstream delays the block by
        // the slowest source, not the sum. Both stay fail-soft per property.
        final Double fLat = lat;
        final Double fLon = lon;
        CompletableFuture<EnvironmentalFingerprint.Soil> soilFuture =
                CompletableFuture.supplyAsync(() -> soilSource.soilFor(blockName, fLat, fLon));
        CompletableFuture<ElevationClient.Elevation> elevFuture =
                CompletableFuture.supplyAsync(() -> elevationFor(fLat, fLon));
        EnvironmentalFingerprint.Climate climate = climate(provider, model, issueDate,
                spatialMethod, stale, days);
        EnvironmentalFingerprint.Climatology clim = climatology(blockName);
        EnvironmentalFingerprint.Soil soil;
        try {
            soil = soilFuture.join();
            if (soil == null) soil = unavailableSoil();
        } catch (RuntimeException e) {
            log.warn("CropAtlas soil source failed ({}); soil will be unavailable", e.getMessage());
            soil = unavailableSoil();
        }
        ElevationClient.Elevation elev;
        try {
            ElevationClient.Elevation got = elevFuture.join();
            elev = got != null && got.available() ? got : null;
        } catch (RuntimeException e) {
            log.debug("CropAtlas elevation unavailable: {}", e.getMessage());
            elev = null;
        }

        Map<String, Object> location = locationMap(blockName, ref, locationMethod);
        List<String> missing = missing(soil, clim, climate, elev);
        List<Map<String, Object>> sources = sources();

        // CEC rides the shared soil result: same source, same provenance. When
        // the soil source has no CEC, it stays null — never borrowed.
        Double cec = soil == null ? null : soil.cecCmolKg();
        String cecProv = cec == null ? null
                : (soil.cached() ? EnvironmentalFingerprint.CACHED
                        : EnvironmentalFingerprint.REFERENCE);
        EnvironmentalFingerprint fp = new EnvironmentalFingerprint(location, climate, soil, clim,
                elev == null ? null : elev.metres(),
                elev == null || !elev.available() ? null
                        : (elev.cached() ? EnvironmentalFingerprint.CACHED
                                : EnvironmentalFingerprint.REFERENCE),
                cec, cecProv, missing, sources);
        return new Context(LocalDate.now(), location, fp);
    }

    /**
     * Honest empty soil used only when the soil source itself throws (its own
     * fail-soft path already returns unavailable instead of throwing, so this
     * is a last-resort guard that keeps the contract identical).
     */
    private static EnvironmentalFingerprint.Soil unavailableSoil() {
        return new EnvironmentalFingerprint.Soil(false, null, null, null, null, null, null,
                false, null, "Soil data unavailable for this block", "0–5 cm");
    }

    /**
     * Block elevation for a coordinate, or an unavailable marker. Fail-soft:
     * no coordinate or a failing source degrades this one dimension only.
     */
    private ElevationClient.Elevation elevationFor(Double lat, Double lon) {        ElevationClient client = elevation;
        if (client == null || lat == null || lon == null) return null;
        try {
            ElevationClient.Elevation e = client.lookup(lat, lon);
            return e != null && e.available() ? e : null;
        } catch (RuntimeException e) {
            log.debug("CropAtlas elevation unavailable: {}", e.getMessage());
            return null;
        }
    }

    // ---- climate aggregation (null-honest) ----

    EnvironmentalFingerprint.Climate climate(String provider, String model, String issueDate,
            String spatialMethod, boolean stale, List<LiveWeatherService.BlockDaily> days) {
        if (days == null || days.isEmpty()) {
            return new EnvironmentalFingerprint.Climate(provider, model, issueDate, spatialMethod,
                    stale, 0, null, null, null, null, null, null, null, null, null, null, null);
        }
        Double rain7 = sumRain(days, 0, 7);
        Double rain16 = sumRain(days, 0, days.size());
        Double et0_7 = sumEt0(days, 0, 7);
        Double balance = (rain7 != null && et0_7 != null) ? round1(rain7 - et0_7) : null;

        Double tMaxMean = mean(days, d -> d.temperatureMaxC());
        Double tMinMean = mean(days, d -> d.temperatureMinC());
        Double tMaxHigh = max(days, d -> d.temperatureMaxC());
        Double tMinLow = min(days, d -> d.temperatureMinC());

        Integer wet7 = null;
        Integer dry7 = null;
        Double rain7Only = sumRain(days, 0, 7);
        if (rain7Only != null) {
            int w = 0, dr = 0;
            for (int i = 0; i < Math.min(7, days.size()); i++) {
                // sumRain already proved every day in the window is non-null and
                // finite, but re-check rather than rely on it: a null is never wet
                // and never dry.
                Double v = days.get(i).rainfallMm();
                if (v == null || Double.isNaN(v) || Double.isInfinite(v)) {
                    w = -1;
                    break;
                }
                if (v >= 1.0) w++;
                if (v < 1.0) dr++;
            }
            if (w >= 0) {
                wet7 = w;
                dry7 = dr;
            }
        }
        Double moist0 = days.get(0).soilMoisture0To7CmVwc();

        return new EnvironmentalFingerprint.Climate(provider, model, issueDate, spatialMethod,
                stale, days.size(), tMinMean, tMaxMean, tMinLow, tMaxHigh,
                rain7, rain16, et0_7, balance, wet7, dry7, moist0);
    }

    /**
     * Deployment rainfall normals for this block. Available only for the blocks
     * that ship a DOY normal record; otherwise an explicit unavailable reason
     * (never another block's numbers, never zero).
     */
    EnvironmentalFingerprint.Climatology climatology(String blockName) {
        // No per-block normal row for arbitrary registry blocks: probed lazily by
        // CropSuitabilityService for the crop-specific growing window.
        if (!hasClimatologyBlock(blockName)) {
            return new EnvironmentalFingerprint.Climatology(false, blockName, null, null, null,
                    "Reference rainfall normals are not published for this block");
        }
        return new EnvironmentalFingerprint.Climatology(true, blockName, null, null,
                climatology.vintage(), null);
    }

    /** Sum of normal rainfall over an arbitrary date window (null if incomplete). */
    public Double climatologySumMm(String blockName, List<LocalDate> dates) {
        if (blockName == null || dates == null || dates.isEmpty()) return null;
        try {
            return climatology.normalSumMm(blockName, dates);
        } catch (RuntimeException e) {
            log.warn("CropAtlas climatology sum failed ({}); component will be unavailable",
                    e.getMessage());
            return null;
        }
    }

    public String climatologyVintage() {
        return climatology.vintage();
    }

    /** Cheap probe: a block has DOY normals only if the first date resolves. */
    public boolean hasClimatologyBlock(String blockName) {
        if (blockName == null) return false;
        return climatology.normalSumMm(blockName, List.of(LocalDate.of(2021, 7, 1))) != null;
    }

    // ---- helpers ----

    private Map<String, Object> locationMap(String blockName, GeographyService.BlockRef ref,
            String locationMethod) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("block_name", blockName);
        m.put("block_code", ref == null ? null : ref.blockCode());
        m.put("state_code", ref == null ? null : ref.stateCode());
        m.put("state_name", ref == null ? null : ref.stateName());
        m.put("district_code", ref == null ? null : ref.districtCode());
        m.put("district_name", ref == null ? null : ref.districtName());
        m.put("latitude", ref == null ? null : ref.latitude());
        m.put("longitude", ref == null ? null : ref.longitude());
        m.put("location_method", locationMethod);
        m.put("label", ref == null ? blockName
                : String.format(Locale.ROOT, "%s · %s, %s", blockName,
                        ref.districtName(), ref.stateName()));
        return m;
    }

    private List<String> missing(EnvironmentalFingerprint.Soil soil,
            EnvironmentalFingerprint.Climatology clim, EnvironmentalFingerprint.Climate climate,
            ElevationClient.Elevation elev) {
        List<String> out = new ArrayList<>();
        if (soil == null || !soil.available()) out.add("soil_unavailable");
        else {
            if (soil.clayGkg() == null) out.add("soil_clay_unavailable");
            if (soil.sandGkg() == null) out.add("soil_sand_unavailable");
            if (soil.siltGkg() == null) out.add("soil_silt_unavailable");
            if (soil.ph() == null) out.add("soil_ph_unavailable");
            if (soil.socGkg() == null) out.add("soil_soc_unavailable");
            if (soil.cecCmolKg() == null) out.add("cec_unavailable");
        }
        if (clim == null || !clim.available()) out.add("climatology_normals_unavailable");
        if (climate == null) {
            out.add("live_forecast_unavailable");
        } else {
            if (climate.waterBalance7dMm() == null) out.add("water_balance_7d_unavailable");
            if (climate.tempMaxMeanC() == null) out.add("temperature_unavailable");
            if (climate.soilMoistureDay0Vwc() == null) out.add("soil_moisture_forecast_unavailable");
        }
        // Elevation and CEC are reported only when actually unknown: a served
        // value carries its LIVE/CACHED/REFERENCE provenance instead.
        if (elev == null || !elev.available()) out.add("elevation_unavailable");
        if (soil == null || !soil.available()) out.add("cec_unavailable_no_source");
        return out;
    }

    private List<Map<String, Object>> sources() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(CropAtlasSoilSource.source("OPEN_METEOO_IFS",
                "Open-Meteo delivery of the ECMWF IFS NWP forecast",
                "Open-Meteo / ECMWF", "https://open-meteo.com/",
                EnvironmentalFingerprint.LIVE,
                "Live forecast, shared with the SAARTHI forecast and intelligence pages."));
        out.add(CropAtlasSoilSource.source("SOILGRIDS", "SoilGrids v2.0 WCS (ISRIC)",
                "International Soil Reference Information Centre", "https://soilgrids.org/",
                EnvironmentalFingerprint.REFERENCE,
                "0–5 cm median (Q0.5) at the block coordinate via the official WCS subset "
                        + "service, supplemented by bundled block means where the service "
                        + "cannot serve a property. Context only — not an in-field measurement."));
        out.add(CropAtlasSoilSource.source("CLIMATOLOGY_NORMALS",
                "Block day-of-year rainfall normals (deployment artifact)",
                "SAARTHI Phase 3A deployment normals", null,
                EnvironmentalFingerprint.HISTORICAL,
                "Reference rainfall normals, not a forecast. Available for a limited set of blocks."));
        out.add(CropAtlasSoilSource.source("ELEVATION",
                "Block elevation (Copernicus DEM via Open-Meteo)",
                "Open-Meteo / Copernicus", "https://open-meteo.com/",
                EnvironmentalFingerprint.REFERENCE,
                "Static terrain height at the block centroid, cached per coordinate. "
                        + "Context only — not a field survey."));
        out.add(CropAtlasSoilSource.source("CROP_REFERENCE",
                "Package of Practices for Kharif/Rabi Crops of Punjab; ICAR-IARI crop guidelines",
                "Punjab Agricultural University; ICAR", "https://www.pau.edu/",
                EnvironmentalFingerprint.REFERENCE,
                "Crop requirements, quoted unchanged. No CropAtlas requirement value is invented."));
        out.add(CropAtlasSoilSource.source("ECOCROP",
                "FAO ECOCROP — Crop Ecological Requirements Database (environment search)",
                "Food and Agriculture Organization of the United Nations",
                EcocropSearchClient.SOURCE_URL,
                EnvironmentalFingerprint.REFERENCE,
                "Dynamic candidate source: one Absolute-mode environment search per "
                        + "block/environment, short-TTL cached. Candidates are ECOCROP's own "
                        + "answers — no static crop list, no third-party dataset."));
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** All-day sums require every day present; otherwise {@code null}. */
    static Double sumRain(List<LiveWeatherService.BlockDaily> days, int from, int to) {
        return sum(days, from, to, d -> d.rainfallMm());
    }

    static Double sumEt0(List<LiveWeatherService.BlockDaily> days, int from, int to) {
        return sum(days, from, to, d -> d.et0Mm());
    }

    /**
     * The extractor is deliberately a boxing {@code Function}, not a
     * {@code ToDoubleFunction}: applying a primitive-returning function would
     * unbox a null daily value and throw before the guard below could run, turning
     * an honest "unavailable" into a 500.
     */
    private static Double sum(List<LiveWeatherService.BlockDaily> days, int from, int to,
            java.util.function.Function<LiveWeatherService.BlockDaily, Double> f) {
        if (days == null) return null;
        int end = Math.min(to, days.size());
        if (end - from < Math.max(1, to - from)) return null;
        double s = 0;
        for (int i = from; i < end; i++) {
            Double v = f.apply(days.get(i));
            if (v == null || Double.isNaN(v) || Double.isInfinite(v)) return null;
            s += v;
        }
        return round1(s);
    }

    private static Double mean(List<LiveWeatherService.BlockDaily> days,
            java.util.function.Function<LiveWeatherService.BlockDaily, Double> f) {
        double s = 0;
        int n = 0;
        for (LiveWeatherService.BlockDaily d : days) {
            Double v = f.apply(d);
            if (v == null || Double.isNaN(v) || Double.isInfinite(v)) continue;
            s += v;
            n++;
        }
        return n == 0 ? null : round1(s / n);
    }

    private static Double max(List<LiveWeatherService.BlockDaily> days,
            java.util.function.Function<LiveWeatherService.BlockDaily, Double> f) {
        Double best = null;
        for (LiveWeatherService.BlockDaily d : days) {
            Double v = f.apply(d);
            if (v == null || Double.isNaN(v) || Double.isInfinite(v)) continue;
            if (best == null || v > best) best = v;
        }
        return best;
    }

    private static Double min(List<LiveWeatherService.BlockDaily> days,
            java.util.function.Function<LiveWeatherService.BlockDaily, Double> f) {
        Double best = null;
        for (LiveWeatherService.BlockDaily d : days) {
            Double v = f.apply(d);
            if (v == null || Double.isNaN(v) || Double.isInfinite(v)) continue;
            if (best == null || v < best) best = v;
        }
        return best;
    }

    static Double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
