package com.saarthi.intelligence;

import com.saarthi.risks.HeavyRainThresholds;
import com.saarthi.weather.BlockSampler;
import com.saarthi.weather.LiveWeatherService;
import com.saarthi.weather.WeatherProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BUILD 1 intelligence tests (additive; frozen rules untouched).
 */
class SectorRiskTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static ForecastContext ctxOf(Double... rain) {
        return ctxOfEt0(rain, null);
    }

    static ForecastContext ctxOfEt0(Double[] rain, Double[] et0) {
        List<ForecastContext.Day> days = new ArrayList<>();
        LocalDate today = LocalDate.now(IST);
        for (int i = 0; i < rain.length; i++) {
            Double e = (et0 != null && i < et0.length) ? et0[i] : 4.0;
            days.add(new ForecastContext.Day(today.plusDays(i + 1), i + 1, rain[i],
                    20.0, 32.0, 24.0, e, 0.2, rain[i], 0.0, 1));
        }
        Double r3 = sum(rain, 3);
        Double r7 = sum(rain, 7);
        Double e7 = et0 == null ? null : sum(et0, 7);
        Double bal = (r7 != null && e7 != null) ? r7 - e7 : null;
        Double max = null;
        for (Double v : rain) {
            if (v == null) { max = null; break; }
            if (max == null || v > max) max = v;
        }
        Integer w3 = wet(rain, 3);
        Integer w7 = wet(rain, 7);
        Integer d7 = dry(rain, 7);
        return new ForecastContext("Sangrur", "Sangrur", null, null, null, null, null,
                null, null, "test", "Open-Meteo", "ECMWF IFS (ecmwf_ifs)", today,
                Instant.now(), false, null, "test", days, r3, r7, e7, bal,
                max, null, w3, w7, d7, d7, 0, 0.2, false, null, "no soil",
                List.of());
    }

    static Double sum(Double[] v, int n) {
        if (v.length < n) return null;
        double s = 0;
        for (int i = 0; i < n; i++) {
            if (v[i] == null) return null;
            s += v[i];
        }
        return s;
    }

    static Integer wet(Double[] v, int n) {
        if (v.length < n) return null;
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (v[i] == null) return null;
            if (v[i] >= 1.0) c++;
        }
        return c;
    }

    static Integer dry(Double[] v, int n) {
        if (v.length < n) return null;
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (v[i] == null) return null;
            if (v[i] < 1.0) c++;
        }
        return c;
    }

    SectorRiskService svc() {
        return new SectorRiskService(new SectorThresholds(), new HeavyRainThresholds());
    }

    GridGroundwaterService grid() {
        return new GridGroundwaterService(new SectorThresholds());
    }

    @Test
    void agricultureLowModerateHighUnavailable() {
        assertEquals("LOW", svc().agriculture(ctxOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)).state());
        assertEquals("MODERATE", svc().agriculture(ctxOf(2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)).state());
        assertEquals("HIGH", svc().agriculture(ctxOf(2.0, 3.0, 0.0, 0.0, 0.0, 0.0, 0.0)).state());
        SectorResult u = svc().agriculture(ctxOf(2.0, null, 0.0, 0.0, 0.0, 0.0, 0.0));
        assertEquals("UNAVAILABLE", u.state());
        assertFalse(u.available());
    }

    @Test
    void logisticsLowElevatedHighUnavailable() {
        assertEquals("LOW", svc().logistics(ctxOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)).state());
        assertEquals("MODERATE", svc().logistics(ctxOf(2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)).state());
        assertEquals("HIGH", svc().logistics(ctxOf(12.0, 15.0, 10.0, 0.0, 0.0, 0.0, 0.0)).state());
        assertEquals("UNAVAILABLE",
                svc().logistics(ctxOf(null, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)).state());
    }

    @Test
    void warehouseLowWetHighUnavailable() {
        assertEquals("LOW",
                svc().warehouse(ctxOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)).state());
        assertEquals("MODERATE",
                svc().warehouse(ctxOf(8.0, 8.0, 8.0, 0.0, 0.0, 0.0, 0.0)).state());
        assertEquals("HIGH",
                svc().warehouse(ctxOf(12.0, 12.0, 12.0, 12.0, 12.0, 0.0, 0.0)).state());
        assertEquals("UNAVAILABLE",
                svc().warehouse(ctxOf(1.0, null, 1.0, 1.0, 1.0, 1.0, 1.0)).state());
    }

    @Test
    void gridLowModerateHighUnavailable() {
        Double[] dryEt0 = {5.0, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0};
        assertEquals("LOW", grid().assess(
                ctxOfEt0(new Double[]{5.0, 5.0, 5.0, 5.0, 5.0, 4.0, 4.0}, dryEt0)).state());
        assertEquals("MODERATE", grid().assess(
                ctxOfEt0(new Double[]{0.0, 0.0, 0.0, 0.0, 9.0, 9.0, 9.0}, dryEt0)).state());
        assertEquals("HIGH", grid().assess(
                ctxOfEt0(new Double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0}, dryEt0)).state());
        SectorResult u = grid().assess(ctxOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0));
        assertEquals("UNAVAILABLE", u.state());
        assertTrue(u.assumptions().stream().anyMatch(s -> s.contains("not directly measured")));
    }
}
