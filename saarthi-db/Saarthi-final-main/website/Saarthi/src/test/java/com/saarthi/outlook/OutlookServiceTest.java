package com.saarthi.outlook;

import com.saarthi.outlook.OutlookService.OutlookUnavailableException;
import com.saarthi.service.ClimateContextService;
import com.saarthi.service.RealForecastService;
import com.saarthi.weather.RecentRainfallService;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Phase 3B outlook tests. No live network in any test.
 */
class OutlookServiceTest {

    private OutlookService serviceWith(RecentRainfallService recent, Map<String, Object> climateSummary) {
        ClimateContextService climate = mock(ClimateContextService.class);
        when(climate.getSummary()).thenReturn(climateSummary);
        RealForecastService blocks = mock(RealForecastService.class);
        when(blocks.findBlock(anyString())).thenAnswer(inv -> {
            String q = inv.getArgument(0);
            return RealForecastService.BLOCKS.stream()
                    .filter(b -> b.equalsIgnoreCase(q))
                    .findFirst()
                    .map(b -> Map.<String, Object>of("block_name", b));
        });
        OutlookService svc = new OutlookService(recent, climate, blocks);
        svc.load();
        assertNotNull(svc.getFreshness().get("issue_date"));
        return svc;
    }

    private static RecentRainfallService unconfiguredRecent() {
        RecentRainfallService r = new RecentRainfallService();
        try {
            var m = RecentRainfallService.class.getDeclaredMethod("setChirpsPath", String.class);
            m.setAccessible(true);
            m.invoke(r, "");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return r;
    }

    private static Map<String, Object> freshClimate() {
        LocalDate today = LocalDate.now(OutlookService.IST);
        Map<String, Object> mjoObs = new LinkedHashMap<>();
        mjoObs.put("date", today.toString());
        mjoObs.put("phase", 2);
        mjoObs.put("amplitude", 1.5);
        Map<String, Object> mjo = new LinkedHashMap<>();
        mjo.put("available", true);
        mjo.put("stale", false);
        mjo.put("observation_end", today.toString());
        mjo.put("observed", mjoObs);
        Map<String, Object> enso = new LinkedHashMap<>();
        enso.put("available", true);
        enso.put("status", "Neutral");
        enso.put("nino34_anom", 0.1);
        enso.put("latest_month", today.toString().substring(0, 7));
        Map<String, Object> iod = new LinkedHashMap<>();
        iod.put("available", true);
        iod.put("stale", false);
        iod.put("phase", "Neutral");
        iod.put("dmi", 0.0);
        iod.put("data_month", today.toString().substring(0, 7));
        Map<String, Object> sum = new LinkedHashMap<>();
        sum.put("mjo", mjo);
        sum.put("enso", enso);
        sum.put("iod", iod);
        return sum;
    }

    private static Map<String, Object> staleClimate() {
        Map<String, Object> sum = freshClimate();
        @SuppressWarnings("unchecked")
        Map<String, Object> mjo = (Map<String, Object>) sum.get("mjo");
        mjo.put("stale", true);
        mjo.put("observation_end", "2020-01-01");
        return sum;
    }

    @Test
    void packagedClimatologyLoads2196Rows() throws Exception {
        try (InputStream in = new org.springframework.core.io.ClassPathResource(
                "climatology/block_doy_normals_full.csv").getInputStream()) {
            var normals = OutlookService.readNormals(in);
            assertEquals(2196, normals.size(), "6 blocks x 366 DOY");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void w3W4DatesAndSixBlocks() {
        OutlookService svc = serviceWith(unconfiguredRecent(), freshClimate());
        Map<String, Object> out = svc.getOutlook();
        LocalDate issue = LocalDate.parse(out.get("issue_date").toString());
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) out.get("blocks");
        assertEquals(6, blocks.size());
        for (Map<String, Object> b : blocks) {
            Map<String, Object> w3 = (Map<String, Object>) b.get("w3");
            Map<String, Object> w4 = (Map<String, Object>) b.get("w4");
            assertEquals(issue.plusDays(17).toString(), w3.get("period_start"));
            assertEquals(issue.plusDays(23).toString(), w3.get("period_end"));
            assertEquals(issue.plusDays(24).toString(), w4.get("period_start"));
            assertEquals(issue.plusDays(30).toString(), w4.get("period_end"));
            assertTrue(w3.get("horizon_label").toString().contains("W3"));
            assertTrue(w4.get("horizon_label").toString().contains("W4"));
        }
    }

    @Test
    void invalidBlockThrowsUnknownBlock() {
        OutlookService svc = serviceWith(unconfiguredRecent(), freshClimate());
        assertThrows(RealForecastService.BlockNotFoundException.class,
                () -> svc.getBlockOutlook("NoSuchBlock"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void probabilitiesSumToOneAndLabelledPrior() {
        OutlookService svc = serviceWith(unconfiguredRecent(), freshClimate());
        Map<String, Object> out = svc.getOutlook();
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) out.get("blocks");
        for (Map<String, Object> b : blocks) {
            for (String k : List.of("w3", "w4")) {
                Map<String, Object> p = (Map<String, Object>) b.get(k);
                double s = ((Number) p.get("below_probability")).doubleValue()
                        + ((Number) p.get("near_probability")).doubleValue()
                        + ((Number) p.get("above_probability")).doubleValue();
                assertEquals(1.0, s, 1e-9);
                assertTrue(p.get("probability_method").toString().contains("tercile_prior"));
                assertTrue(p.get("climatological_normal_label").toString().toLowerCase().contains("not forecast"));
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentUnavailableIsNullNotZero() {
        OutlookService svc = serviceWith(unconfiguredRecent(), freshClimate());
        Map<String, Object> b = svc.getBlockOutlook("Sangrur");
        Map<String, Object> rec = (Map<String, Object>) b.get("recent_observed");
        assertNull(rec.get("observed_14d_mm"));
        assertNull(rec.get("observed_30d_mm"));
        assertEquals(false, rec.get("available_14d"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleClimateCapsConfidenceAndLeavesProbsUntouched() {
        OutlookService fresh = serviceWith(unconfiguredRecent(), freshClimate());
        OutlookService stale = serviceWith(unconfiguredRecent(), staleClimate());
        Map<String, Object> bf = fresh.getBlockOutlook("Dhuri");
        Map<String, Object> bs = stale.getBlockOutlook("Dhuri");
        // MJO/IOD/ENSO must not alter mathematical probabilities.
        for (String k : List.of("w3", "w4")) {
            Map<String, Object> pf = (Map<String, Object>) bf.get(k);
            Map<String, Object> ps = (Map<String, Object>) bs.get(k);
            assertEquals(pf.get("below_probability"), ps.get("below_probability"));
            assertEquals(pf.get("near_probability"), ps.get("near_probability"));
            assertEquals(pf.get("above_probability"), ps.get("above_probability"));
        }
        assertEquals("LOW", bs.get("confidence"), "stale inputs must cap confidence");
        assertNotEquals("HIGH", bf.get("confidence"), "HIGH never issued in Phase 3B");
        Map<String, Object> ctx = (Map<String, Object>) bs.get("climate_context");
        assertEquals(true, ctx.get("any_stale"));
        assertTrue(((Map<String, Object>) ctx.get("mjo")).get("label").toString().contains("not used"));
    }

    @Test
    void outsideJjasConfidenceIsLow() {
        assertTrue(OutlookService.monthStale("2020-01", LocalDate.of(2026, 9, 20)));
        assertFalse(OutlookService.monthStale("2026-09", LocalDate.of(2026, 9, 20)));
        assertEquals(59, OutlookService.wheelDoy(LocalDate.of(2026, 2, 28)));
        assertEquals(60, OutlookService.wheelDoy(LocalDate.of(2024, 2, 29)));
        assertEquals(61, OutlookService.wheelDoy(LocalDate.of(2026, 3, 1)));
    }

    @Test
    void narrativeMakesNoCausalClaims() {
        OutlookService svc = serviceWith(unconfiguredRecent(), staleClimate());
        String n = svc.getBlockOutlook("Moonak").get("narrative").toString();
        assertTrue(n.contains("climatology-based"));
        assertTrue(n.contains("should not be interpreted as a deterministic rainfall forecast"));
        assertFalse(n.contains("caused"));
        assertFalse(n.contains("MJO caused"));
        assertFalse(n.contains("ENSO caused"));
    }

    @Test
    void missingClimatologyIsExplicitFailure() {
        ClimateContextService climate = mock(ClimateContextService.class);
        when(climate.getSummary()).thenReturn(freshClimate());
        RealForecastService blocks = mock(RealForecastService.class);
        OutlookService svc = new OutlookService(unconfiguredRecent(), climate, blocks);
        svc.setNormals(null);
        assertThrows(OutlookUnavailableException.class, svc::getOutlook);
        assertEquals(false, svc.getFreshness().get("available"));
    }
}
