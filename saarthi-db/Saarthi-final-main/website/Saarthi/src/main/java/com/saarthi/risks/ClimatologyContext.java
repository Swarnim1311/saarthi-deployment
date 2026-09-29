package com.saarthi.risks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Display-only climatology context from the frozen Phase 3A deployment
 * normals ({@code classpath:/climatology/block_doy_normals_full.csv}).
 *
 * <p>Reports the summed train-based daily-mean rainfall over a date list
 * (used for the D+1..D+3 context line: "normal for these calendar days:
 * X mm"). A normal is reference context, never a deterministic risk input —
 * it cannot affect severity or confidence. DOY follows the Phase 3A wheel
 * (Feb-29 → 60, else non-leap reference +1 from Mar-01), mirroring
 * {@code OutlookService.wheelDoy}. Fail-soft: {@code null} when unavailable.
 */
@Component
public class ClimatologyContext {

    private static final Logger log = LoggerFactory.getLogger(ClimatologyContext.class);
    static final String RESOURCE = "climatology/block_doy_normals_full.csv";
    static final String VINTAGE =
            "Phase 3A deployment normals (block_doy_normals_full.csv, train-based)";

    private final Map<String, Double> dailyMeanByBlockDoy = new HashMap<>();

    public ClimatologyContext() {
        this(RESOURCE);
    }

    /** Test seam: load from an alternate classpath resource. */
    ClimatologyContext(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream();
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = br.readLine();
            if (header == null) return;
            String[] cols = header.split(",");
            int bi = idx(cols, "block"), di = idx(cols, "doy"), mi = idx(cols, "daily_mean_mm");
            if (bi < 0 || di < 0 || mi < 0) return;
            String line;
            while ((line = br.readLine()) != null) {
                String[] c = line.split(",", -1);
                if (c.length <= Math.max(bi, Math.max(di, mi))) continue;
                try {
                    dailyMeanByBlockDoy.put(c[bi].trim() + "|" + Integer.parseInt(c[di].trim()),
                            Double.parseDouble(c[mi].trim()));
                } catch (NumberFormatException e) {
                    continue; // skip malformed rows, keep the rest honest
                }
            }
        } catch (Exception e) {
            log.warn("Climatology context unavailable ({}); line will be omitted", e.getMessage());
        }
    }

    /**
     * Summed daily-mean normal over the given dates, or {@code null} when any
     * date lacks a row (never zero-filled, never partial).
     */
    public Double normalSumMm(String block, List<LocalDate> dates) {
        double sum = 0;
        for (LocalDate d : dates) {
            Double v = dailyMeanByBlockDoy.get(block + "|" + wheelDoy(d));
            if (v == null) return null;
            sum += v;
        }
        return Math.round(sum * 1000.0) / 1000.0;
    }

    public String vintage() {
        return VINTAGE;
    }

    /** Phase 3A wheel: Feb-29 → 60, else non-leap reference +1 from Mar-01. */
    static int wheelDoy(LocalDate d) {
        if (d.getMonthValue() == 2 && d.getDayOfMonth() == 29) return 60;
        LocalDate base = LocalDate.of(2021, d.getMonthValue(), d.getDayOfMonth());
        int d0 = base.getDayOfYear();
        return d0 + (d0 >= 60 ? 1 : 0);
    }

    private static int idx(String[] cols, String name) {
        for (int i = 0; i < cols.length; i++) {
            if (cols[i].trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }
}
