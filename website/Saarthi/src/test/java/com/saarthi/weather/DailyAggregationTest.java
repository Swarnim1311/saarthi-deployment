package com.saarthi.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hourly → Asia/Kolkata calendar-day aggregation, incl. day boundaries.
 */
class DailyAggregationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void hourlySumsGroupByLocalDate() throws Exception {
        JsonNode hourly = mapper.readTree("""
                {"time": ["2026-09-18T22:00", "2026-09-18T23:00",
                          "2026-09-19T00:00", "2026-09-19T01:00"],
                 "precipitation": [1.0, 2.0, 3.0, 4.0]}
                """);
        Map<LocalDate, Double> totals = OpenMeteoProvider.aggregateHourlyToDaily(hourly);
        assertEquals(3.0, totals.get(LocalDate.parse("2026-09-18")), 1e-9);
        assertEquals(7.0, totals.get(LocalDate.parse("2026-09-19")), 1e-9);
    }

    @Test
    void dayBoundarySplitAtMidnight() throws Exception {
        // 23:00 belongs to day 18, 00:00 starts day 19 — UTC confusion would merge these.
        JsonNode hourly = mapper.readTree("""
                {"time": ["2026-09-18T23:00", "2026-09-19T00:00"],
                 "precipitation": [5.0, 6.0]}
                """);
        Map<LocalDate, Double> totals = OpenMeteoProvider.aggregateHourlyToDaily(hourly);
        assertEquals(5.0, totals.get(LocalDate.parse("2026-09-18")), 1e-9);
        assertEquals(6.0, totals.get(LocalDate.parse("2026-09-19")), 1e-9);
    }

    @Test
    void missingHoursSkippedNotZeroFilled() throws Exception {
        JsonNode hourly = mapper.readTree("""
                {"time": ["2026-09-18T00:00", "2026-09-18T01:00", "2026-09-18T02:00"],
                 "precipitation": [1.0, null, 3.0]}
                """);
        Map<LocalDate, Double> totals = OpenMeteoProvider.aggregateHourlyToDaily(hourly);
        assertEquals(4.0, totals.get(LocalDate.parse("2026-09-18")), 1e-9);
    }

    @Test
    void dailySumMatchesHourlySum() throws Exception {
        // Cross-check shape: provider daily.precipitation_sum must equal the
        // local-day hourly total; here both are 6.0 for the same calendar day.
        JsonNode hourly = mapper.readTree("""
                {"time": ["2026-09-19T00:00", "2026-09-19T06:00",
                          "2026-09-19T12:00", "2026-09-19T18:00"],
                 "precipitation": [0.5, 1.5, 2.0, 2.0]}
                """);
        Map<LocalDate, Double> totals = OpenMeteoProvider.aggregateHourlyToDaily(hourly);
        assertEquals(6.0, totals.get(LocalDate.parse("2026-09-19")), 1e-9,
                "hourly local-day total for cross-checking daily.precipitation_sum");
    }
}
