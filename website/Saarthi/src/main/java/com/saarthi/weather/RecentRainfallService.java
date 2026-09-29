package com.saarthi.weather;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Recent OBSERVED rainfall from the local CHIRPS block CSV
 * ({@code data/raw/rainfall/Sangrur_Block_Daily_Rainfall_2010_2025.csv}).
 *
 * <p>Fail-soft by design: when {@code saarthi.chirps.path} is unset or the
 * file is missing, every call reports {@code available:false} with a reason —
 * observed rainfall is NEVER fabricated and NEVER mixed into forecast fields.
 * Consumers must keep {@code observed_last_*_mm} separate from
 * {@code forecast_next_*_mm}.
 */
@Service
public class RecentRainfallService {

    @Value("${saarthi.chirps.path:}")
    private String chirpsPath;

    /** Test seam. */
    public void setChirpsPath(String p) {
        this.chirpsPath = p;
    }

    /**
     * Daily observed rainfall for one block over the inclusive range
     * {@code [start, end]} (used by the IFS shadow ledger for the D-30..D-3
     * antecedent window).
     *
     * <p>Missing days are {@code null} entries — never zero-filled. Returns
     * {@code null} when CHIRPS is unconfigured, missing, or unreadable
     * (fail-soft; the shadow record then marks the antecedent unavailable).
     */
    public java.util.List<Double> dailyWindow(String block,
            LocalDate start, LocalDate end) {
        Path p = configuredPath();
        if (p == null || !Files.isRegularFile(p)) return null;
        try {
            Map<String, TreeMap<LocalDate, Double>> series = readSeries(p);
            TreeMap<LocalDate, Double> s = series.get(block);
            if (s == null) return null;
            java.util.List<Double> out = new java.util.ArrayList<>();
            for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                out.add(s.get(d)); // null when the day is absent: never zero
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Last-N-day observed totals per block, summed over the N days ending at
     * the latest date present in the file (strictly historical, ≤ today).
     */
    public Map<String, Object> observedLast(int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        Path p = configuredPath();
        if (p == null || !Files.isRegularFile(p)) {
            out.put("available", false);
            out.put("reason", "CHIRPS file not configured (saarthi.chirps.path) or missing; "
                    + "no observed rainfall fabricated");
            return out;
        }
        try {
            Map<String, TreeMap<LocalDate, Double>> series = readSeries(p);
            if (series.isEmpty()) {
                out.put("available", false);
                out.put("reason", "CHIRPS file has no usable rows");
                return out;
            }
            LocalDate end = series.values().stream()
                    .map(TreeMap::lastKey).max(LocalDate::compareTo).orElseThrow();
            LocalDate start = end.minusDays(days - 1L);
            Map<String, Object> perBlock = new LinkedHashMap<>();
            boolean[] complete = {true};
            for (Map.Entry<String, TreeMap<LocalDate, Double>> e : series.entrySet()) {
                double sum = 0;
                boolean ok = true;
                for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
                    Double v = e.getValue().get(d);
                    if (v == null) {
                        ok = false;
                        break;
                    }
                    sum += v;
                }
                if (ok) {
                    perBlock.put(e.getKey(), round3(sum));
                } else {
                    complete[0] = false;
                    perBlock.put(e.getKey(), null);
                }
            }
            out.put("available", complete[0]);
            out.put("window_days", days);
            out.put("period_start", start.toString());
            out.put("period_end", end.toString());
            out.put("source", "CHIRPS v3 observed (local file)");
            out.put("observed_last_" + days + "d_mm_by_block", perBlock);
            if (!complete[0]) {
                out.put("reason", "Some blocks miss days in the window; those blocks report null (never zero-filled)");
            }
            return out;
        } catch (Exception e) {
            out.put("available", false);
            out.put("reason", "CHIRPS read failed: " + e.getMessage());
            return out;
        }
    }

    private Path configuredPath() {
        if (chirpsPath == null || chirpsPath.isBlank()) return null;
        return Paths.get(chirpsPath.trim());
    }

    /** block → (date → rainfall_mm). Variant "Lehragaga" normalised to "Lehra". */
    static Map<String, TreeMap<LocalDate, Double>> readSeries(Path p) throws Exception {
        Map<String, TreeMap<LocalDate, Double>> out = new LinkedHashMap<>();
        try (BufferedReader br = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) return out;
            String[] cols = header.split(",");
            int bi = idx(cols, "block"), di = idx(cols, "date"), ri = idx(cols, "rainfall_mm");
            if (bi < 0 || di < 0 || ri < 0) throw new IllegalArgumentException("CHIRPS CSV lacks block/date/rainfall_mm");
            String line;
            while ((line = br.readLine()) != null) {
                String[] c = line.split(",", -1);
                if (c.length <= Math.max(bi, Math.max(di, ri))) continue;
                String block = c[bi].trim();
                if ("Lehragaga".equalsIgnoreCase(block)) block = "Lehra";
                LocalDate date;
                try {
                    date = LocalDate.parse(c[di].trim().substring(0, 10));
                } catch (Exception e) {
                    continue;
                }
                double v;
                try {
                    v = Double.parseDouble(c[ri].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                out.computeIfAbsent(block, k -> new TreeMap<>()).put(date, v);
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

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
