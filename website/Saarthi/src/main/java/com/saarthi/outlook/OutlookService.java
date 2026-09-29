package com.saarthi.outlook;

import com.saarthi.service.ClimateContextService;
import com.saarthi.service.RealForecastService;
import com.saarthi.weather.RecentRainfallService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Phase 3B display-only Weeks 3-4 outlook.
 *
 * <p>Source of climatology: frozen Phase 3A deployment artifact
 * {@code data/processed/climatology/block_doy_normals_full.csv}
 * (packaged copy: {@code classpath:/climatology/block_doy_normals_full.csv}).
 * The artifact is NEVER rebuilt here; this service only reads it.
 *
 * <p>Semantics (honest baseline):
 * <ul>
 *   <li>W3 = D+17..D+23, W4 = D+24..D+30 where D = issue date (today IST).</li>
 *   <li>Probabilities are the climatological tercile prior (1/3, 1/3, 1/3),
 *       labelled as such — NOT a calibrated forecast. Phase 3A backtest showed
 *       negative Brier skill for MJO/recent modifiers, so no modifier ships.</li>
 *   <li>Rainfall amounts for W3/W4 are CLIMATOLOGICAL NORMALS (expected 7-day
 *       sums from train daily means), never deterministic forecasts.</li>
 *   <li>MJO/IOD/ENSO are context-only and never alter probabilities.</li>
 *   <li>HIGH confidence is never issued in Phase 3B (climatology-only).</li>
 * </ul>
 */
@Service
public class OutlookService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Conservative freshness thresholds (documented in api-contract.md). */
    static final int MJO_STALE_AFTER_DAYS = 14;
    static final int CLIMATE_STALE_AFTER_MONTHS = 2;

    private final RecentRainfallService recent;
    private final ClimateContextService climate;
    private final RealForecastService blocks;

    /** Optional external override; blank = packaged classpath copy. */
    @Value("${saarthi.outlook.climatology-path:}")
    private String externalClimatologyPath;

    /** (block, doy) -> row. Null when the artifact failed to load. */
    private Map<BlockDoy, ClimRow> normals;
    private String loadError;
    private String climatologyVintage = "Phase 3A deployment (block_doy_normals_full.csv, 2010-2025 span)";

    @Autowired
    public OutlookService(RecentRainfallService recent, ClimateContextService climate,
            RealForecastService blocks) {
        this.recent = recent;
        this.climate = climate;
        this.blocks = blocks;
    }

    @PostConstruct
    void load() {
        try {
            normals = readNormals(openNormals());
            loadError = null;
        } catch (Exception e) {
            normals = null;
            loadError = e.getMessage();
        }
    }

    /** Test seam: inject parsed normals directly. */
    void setNormals(Map<BlockDoy, ClimRow> n) {
        this.normals = n;
        this.loadError = null;
    }

    /** Test seam. */
    void setExternalClimatologyPath(String p) {
        this.externalClimatologyPath = p;
    }

    // ---- Public API ----

    /** Full W3/W4 outlook for all six blocks. */
    public synchronized Map<String, Object> getOutlook() {
        LocalDate issue = issueDate();
        ensureLoaded();
        Map<String, Object> ctx = climateContext(issue);
        Map<String, Object> rec14 = recent.observedLast(14);
        Map<String, Object> rec30 = recent.observedLast(30);
        List<Map<String, Object>> list = RealForecastService.BLOCKS.stream()
                .map(b -> blockOutlook(b, issue, rec14, rec30, ctx))
                .toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issue_date", issue.toString());
        out.put("generated_at", Instant.now().toString());
        out.put("method", "climatology-only display baseline (Phase 3A deployment normals; "
                + "probabilities are the tercile prior 1/3-1/3-1/3, not a calibrated forecast)");
        out.put("w3_definition", "sum of rainfall over issue+17..issue+23 (per block)");
        out.put("w4_definition", "sum of rainfall over issue+24..issue+30 (per block)");
        out.put("blocks", list);
        out.put("climate_context", ctx);
        out.put("freshness", freshnessMap(issue, rec14, rec30, ctx));
        return out;
    }

    /** W3/W4 outlook for one block (name or id, case-insensitive). */
    public synchronized Map<String, Object> getBlockOutlook(String blockId) {
        String canonical = blocks.findBlock(blockId)
                .map(b -> String.valueOf(b.get("block_name")))
                .orElseThrow(() -> new RealForecastService.BlockNotFoundException(blockId));
        LocalDate issue = issueDate();
        ensureLoaded();
        Map<String, Object> ctx = climateContext(issue);
        Map<String, Object> rec14 = recent.observedLast(14);
        Map<String, Object> rec30 = recent.observedLast(30);
        Map<String, Object> out = blockOutlook(canonical, issue, rec14, rec30, ctx);
        out.put("issue_date", issue.toString());
        out.put("generated_at", Instant.now().toString());
        return out;
    }

    /** Freshness of the outlook inputs (no upstream fetch). */
    public synchronized Map<String, Object> getFreshness() {
        LocalDate issue = issueDate();
        if (normals == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("available", false);
            out.put("reason", "Climatology artifact unavailable: " + loadError);
            return out;
        }
        Map<String, Object> ctx = climateContext(issue);
        Map<String, Object> rec14 = recent.observedLast(14);
        Map<String, Object> rec30 = recent.observedLast(30);
        Map<String, Object> f = freshnessMap(issue, rec14, rec30, ctx);
        f.put("available", true);
        return f;
    }

    // ---- Core construction ----

    private void ensureLoaded() {
        if (normals == null) {
            throw new OutlookUnavailableException(
                    "Climatology artifact unavailable" + (loadError != null ? ": " + loadError : ""));
        }
    }

    static LocalDate issueDate() {
        return LocalDate.now(IST);
    }

    private Map<String, Object> blockOutlook(String block, LocalDate issue,
            Map<String, Object> rec14, Map<String, Object> rec30, Map<String, Object> ctx) {
        LocalDate w3s = issue.plusDays(17);
        LocalDate w3e = issue.plusDays(23);
        LocalDate w4s = issue.plusDays(24);
        LocalDate w4e = issue.plusDays(30);
        int issueDoy = wheelDoy(issue);

        ClimRow ref = normals.get(new BlockDoy(block, issueDoy));
        Map<String, Object> w3 = periodMap(block, "W3", w3s, w3e, ref, issue);
        Map<String, Object> w4 = periodMap(block, "W4", w4s, w4e, ref, issue);

        Map<String, Object> recentMap = recentMap(block, rec14, rec30);
        Confidence conf = confidence(issue, rec14, rec30, ctx);
        String narrative = narrative(block, w3, w4, recentMap, conf, ctx, issue);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("block_name", block);
        out.put("horizon_label", "W3 (Days 17-23) + W4 (Days 24-30)");
        out.put("w3", w3);
        out.put("w4", w4);
        out.put("recent_observed", recentMap);
        out.put("recent_anomaly", Map.of("available", false,
                "reason", "No validated trailing-window anomaly reference in the frozen artifact; "
                        + "recent totals shown as context only, probabilities stay climatology-only"));
        out.put("confidence", conf.level());
        out.put("confidence_reason", conf.reason());
        out.put("climate_context", ctx);
        out.put("narrative", narrative);
        out.put("status", "available");
        return out;
    }

    private Map<String, Object> periodMap(String block, String horizon,
            LocalDate start, LocalDate end, ClimRow ref, LocalDate issue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("horizon_label", horizon + (horizon.equals("W3") ? " (Days 17-23)" : " (Days 24-30)"));
        m.put("period_start", start.toString());
        m.put("period_end", end.toString());
        // Climatological prior: artifact stores thresholds, not a distribution.
        m.put("below_probability", 1.0 / 3);
        m.put("near_probability", 1.0 / 3);
        m.put("above_probability", 1.0 / 3);
        m.put("probability_method", "climatological_tercile_prior (1/3 each; "
                + "not a calibrated forecast — Phase 3A backtest skill was negative)");
        if (ref == null) {
            m.put("status", "unavailable");
            m.put("reason", "No climatology row for issue DOY " + wheelDoy(issue));
            return m;
        }
        double normal = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            ClimRow r = normals.get(new BlockDoy(block, wheelDoy(d)));
            if (r == null) {
                m.put("status", "unavailable");
                m.put("reason", "Missing daily-mean row for window date " + d);
                return m;
            }
            normal += r.dailyMeanMm();
        }
        normal = Math.round(normal * 1000.0) / 1000.0;
        m.put("climatological_normal_mm", normal);
        m.put("climatological_normal_label", "CLIMATOLOGICAL NORMAL / REFERENCE — not forecast rainfall");
        if (horizon.equals("W3")) {
            m.put("tercile_t33_mm", ref.w3t33());
            m.put("tercile_t66_mm", ref.w3t66());
        } else {
            m.put("tercile_t33_mm", ref.w4t33());
            m.put("tercile_t66_mm", ref.w4t66());
        }
        m.put("wet_day_probability", ref.wetProb());
        m.put("status", "available");
        return m;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recentMap(String block,
            Map<String, Object> rec14, Map<String, Object> rec30) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("observed_14d_mm", blockValue(rec14, block, 14));
        out.put("observed_30d_mm", blockValue(rec30, block, 30));
        out.put("available_14d", isAvail(rec14));
        out.put("available_30d", isAvail(rec30));
        out.put("period_14d", periodOf(rec14));
        out.put("period_30d", periodOf(rec30));
        out.put("note", "Observed history only — kept separate from W3/W4 outlook; "
                + "missing values are null (never zero-filled)");
        return out;
    }

    private static Double blockValue(Map<String, Object> rec, String block, int days) {
        try {
            Object inner = rec.get("observed_last_" + days + "d_mm_by_block");
            if (inner instanceof Map<?, ?> mm) {
                Object v = mm.get(block);
                if (v instanceof Number n) return n.doubleValue();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isAvail(Map<String, Object> rec) {
        return Boolean.TRUE.equals(rec.get("available"));
    }

    private static String periodOf(Map<String, Object> rec) {
        Object s = rec.get("period_start"), e = rec.get("period_end");
        if (s == null || e == null) return null;
        return s + ".." + e;
    }

    // ---- Climate context (read-only, never modifies probabilities) ----

    @SuppressWarnings("unchecked")
    Map<String, Object> climateContext(LocalDate issue) {
        Map<String, Object> sum = climate.getSummary();
        Map<String, Object> out = new LinkedHashMap<>();
        boolean anyStale = false;

        Map<String, Object> mjo = extract(sum, "mjo");
        Map<String, Object> mjoOut = new LinkedHashMap<>();
        boolean mjoStale = mjoStale(mjo, issue);
        anyStale |= mjoStale;
        mjoOut.put("available", mjoAvail(mjo));
        mjoOut.put("stale", mjoStale);
        mjoOut.put("label", "Context only — not used in W3/W4 probability calculation");
        if (mjoAvail(mjo)) {
            mjoOut.put("observation_end", mjo.get("observation_end"));
            Object o = mjo.get("observed");
            if (o instanceof Map<?, ?> om) {
                mjoOut.put("phase", ((Map<String, Object>) om).get("phase"));
                mjoOut.put("amplitude", ((Map<String, Object>) om).get("amplitude"));
            }
        } else {
            mjoOut.put("reason", "Latest RMM observation unavailable");
        }
        out.put("mjo", mjoOut);

        Map<String, Object> enso = extract(sum, "enso");
        Map<String, Object> ensoOut = new LinkedHashMap<>();
        boolean ensoStale = monthStale(enso.get("latest_month"), issue);
        anyStale |= ensoStale || !ensoAvail(enso);
        ensoOut.put("available", ensoAvail(enso));
        ensoOut.put("stale", ensoStale);
        ensoOut.put("label", "Climate regime context — not used directly to calculate W3/W4 probabilities");
        if (ensoAvail(enso)) {
            ensoOut.put("status", enso.get("status"));
            ensoOut.put("nino34_anom", enso.get("nino34_anom"));
            ensoOut.put("latest_month", enso.get("latest_month"));
        }
        out.put("enso", ensoOut);

        Map<String, Object> iod = extract(sum, "iod");
        Map<String, Object> iodOut = new LinkedHashMap<>();
        boolean iodStale = Boolean.TRUE.equals(iod.get("stale"))
                || monthStale(iod.get("data_month"), issue);
        anyStale |= iodStale || !iodAvail(iod);
        iodOut.put("available", iodAvail(iod));
        iodOut.put("stale", iodStale);
        iodOut.put("label", "Context only — not used in W3/W4 probability calculation");
        if (iodAvail(iod)) {
            iodOut.put("phase", iod.get("phase"));
            iodOut.put("dmi", iod.get("dmi"));
            iodOut.put("data_month", iod.get("data_month"));
        }
        out.put("iod", iodOut);
        out.put("any_stale", anyStale);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extract(Map<String, Object> sum, String key) {
        Object v = sum == null ? null : sum.get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        return Map.of("available", false);
    }

    private static boolean mjoAvail(Map<String, Object> mjo) {
        return !Boolean.FALSE.equals(mjo.getOrDefault("available", true))
                && mjo.get("observed") instanceof Map;
    }

    private boolean mjoStale(Map<String, Object> mjo, LocalDate issue) {
        if (Boolean.TRUE.equals(mjo.get("stale"))) return true;
        if (!mjoAvail(mjo)) return true;
        try {
            LocalDate obs = LocalDate.parse(String.valueOf(mjo.get("observation_end")));
            return obs.isBefore(issue.minusDays(MJO_STALE_AFTER_DAYS));
        } catch (Exception e) {
            Object o = mjo.get("observed");
            if (o instanceof Map<?, ?> om && om.get("date") != null) {
                try {
                    LocalDate d = LocalDate.parse(String.valueOf(om.get("date")));
                    return d.isBefore(issue.minusDays(MJO_STALE_AFTER_DAYS));
                } catch (Exception ignored) {
                }
            }
            return true;
        }
    }

    private static boolean ensoAvail(Map<String, Object> enso) {
        return !Boolean.FALSE.equals(enso.getOrDefault("available", true))
                && enso.get("latest_month") != null;
    }

    private static boolean iodAvail(Map<String, Object> iod) {
        return !Boolean.FALSE.equals(iod.getOrDefault("available", true));
    }

    static boolean monthStale(Object ymObj, LocalDate issue) {
        if (ymObj == null) return true;
        try {
            YearMonth ym = YearMonth.parse(String.valueOf(ymObj).substring(0, 7));
            YearMonth cutoff = YearMonth.from(issue).minusMonths(CLIMATE_STALE_AFTER_MONTHS);
            return ym.isBefore(cutoff);
        } catch (Exception e) {
            return true;
        }
    }

    // ---- Confidence ----

    record Confidence(String level, String reason) {}

    Confidence confidence(LocalDate issue, Map<String, Object> rec14,
            Map<String, Object> rec30, Map<String, Object> ctx) {
        boolean jjas = issue.getMonthValue() >= 6 && issue.getMonthValue() <= 9;
        boolean stale = Boolean.TRUE.equals(ctx.get("any_stale"));
        boolean recentOk = isAvail(rec14) || isAvail(rec30);
        // HIGH is never issued in Phase 3B (climatology-only baseline).
        if (!jjas) {
            return new Confidence("LOW", "Outside JJAS monsoon season: climatology-dominated / "
                    + "low information for a monsoon-oriented outlook");
        }
        if (stale) {
            return new Confidence("LOW", "Climate context is stale or unavailable; "
                    + "confidence capped (never HIGH on stale inputs)");
        }
        if (!recentOk) {
            return new Confidence("LOW", "Recent observed rainfall unavailable; "
                    + "climatology-only outlook with reduced context");
        }
        return new Confidence("MODERATE", "JJAS, climatology available, recent observed context "
                + "available, climate context fresh; probabilities remain the climatological prior");
    }

    private String narrative(String block, Map<String, Object> w3, Map<String, Object> w4,
            Map<String, Object> recentMap, Confidence conf, Map<String, Object> ctx, LocalDate issue) {
        StringBuilder sb = new StringBuilder();
        sb.append("Extended climate outlook for ").append(block).append(": ");
        sb.append("W3 (").append(w3.get("period_start")).append("..").append(w3.get("period_end")).append(") ");
        sb.append("climatological normal ").append(w3.get("climatological_normal_mm")).append(" mm; ");
        sb.append("W4 (").append(w4.get("period_start")).append("..").append(w4.get("period_end")).append(") ");
        sb.append("climatological normal ").append(w4.get("climatological_normal_mm")).append(" mm. ");
        Object r14 = recentMap.get("observed_14d_mm");
        if (r14 != null) {
            sb.append("Recent observed 14-day rainfall ").append(r14).append(" mm (context only). ");
        } else {
            sb.append("Recent observed rainfall unavailable (shown as unavailable, not zero). ");
        }
        if (Boolean.TRUE.equals(ctx.get("any_stale"))) {
            sb.append("Climate context is stale; confidence is reduced. ");
        }
        boolean jjas = issue.getMonthValue() >= 6 && issue.getMonthValue() <= 9;
        if (!jjas) {
            sb.append("Outside JJAS: climatology-dominated / low information. ");
        }
        sb.append("This W3/W4 outlook is climatology-based and should not be interpreted "
                + "as a deterministic rainfall forecast. Confidence: ").append(conf.level()).append(".");
        return sb.toString();
    }

    private Map<String, Object> freshnessMap(LocalDate issue, Map<String, Object> rec14,
            Map<String, Object> rec30, Map<String, Object> ctx) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("issue_date", issue.toString());
        f.put("generated_at", Instant.now().toString());
        f.put("climatology_vintage", climatologyVintage);
        f.put("recent_14d", Map.of("available", isAvail(rec14),
                "period", String.valueOf(periodOf(rec14))));
        f.put("recent_30d", Map.of("available", isAvail(rec30),
                "period", String.valueOf(periodOf(rec30))));
        f.put("climate_context", ctx);
        f.put("confidence_cap_note", "HIGH never issued in Phase 3B; stale inputs or "
                + "outside-JJAS cap confidence at LOW");
        return f;
    }

    // ---- Climatology IO ----

    private InputStream openNormals() throws Exception {
        if (externalClimatologyPath != null && !externalClimatologyPath.isBlank()) {
            Path p = Paths.get(externalClimatologyPath.trim());
            Path f = Files.isDirectory(p) ? p.resolve("block_doy_normals_full.csv") : p;
            return Files.newInputStream(f);
        }
        return new ClassPathResource("climatology/block_doy_normals_full.csv").getInputStream();
    }

    static Map<BlockDoy, ClimRow> readNormals(InputStream in) throws Exception {
        Map<BlockDoy, ClimRow> out = new TreeMap<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null) throw new IllegalArgumentException("Empty climatology file");
            String[] cols = header.split(",");
            int bi = idx(cols, "block"), di = idx(cols, "doy"), mi = idx(cols, "daily_mean_mm"),
                    wi = idx(cols, "wet_prob"), t33 = idx(cols, "w3_t33_mm"), t66 = idx(cols, "w3_t66_mm"),
                    u33 = idx(cols, "w4_t33_mm"), u66 = idx(cols, "w4_t66_mm");
            if (bi < 0 || di < 0 || mi < 0) throw new IllegalArgumentException("Climatology CSV lacks block/doy/daily_mean_mm");
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] c = line.split(",", -1);
                String block = c[bi].trim();
                int doy = Integer.parseInt(c[di].trim());
                out.put(new BlockDoy(block, doy), new ClimRow(
                        Double.parseDouble(c[mi].trim()),
                        wi >= 0 ? Double.parseDouble(c[wi].trim()) : Double.NaN,
                        t33 >= 0 ? Double.parseDouble(c[t33].trim()) : Double.NaN,
                        t66 >= 0 ? Double.parseDouble(c[t66].trim()) : Double.NaN,
                        u33 >= 0 ? Double.parseDouble(c[u33].trim()) : Double.NaN,
                        u66 >= 0 ? Double.parseDouble(c[u66].trim()) : Double.NaN));
            }
        }
        return out;
    }

    private static int idx(String[] cols, String name) {
        for (int i = 0; i < cols.length; i++) {
            if (cols[i].trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /** Phase 3A wheel: Feb-29 -> 60, else non-leap reference +1 from Mar-01. */
    static int wheelDoy(LocalDate d) {
        if (d.getMonthValue() == 2 && d.getDayOfMonth() == 29) return 60;
        LocalDate base = LocalDate.of(2021, d.getMonthValue(), d.getDayOfMonth());
        int d0 = base.getDayOfYear();
        return d0 + (d0 >= 60 ? 1 : 0);
    }

    record BlockDoy(String block, int doy) implements Comparable<BlockDoy> {
        @Override
        public int compareTo(BlockDoy o) {
            int c = block.compareTo(o.block);
            return c != 0 ? c : Integer.compare(doy, o.doy);
        }
    }

    record ClimRow(double dailyMeanMm, double wetProb, double w3t33, double w3t66,
            double w4t33, double w4t66) {}

    /** Missing climatology: explicit 503, never synthetic data. */
    public static class OutlookUnavailableException extends RuntimeException {
        public OutlookUnavailableException(String message) {
            super(message);
        }
    }
}
