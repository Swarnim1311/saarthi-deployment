package com.saarthi.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Weather API parsing, units and missing-data behaviour (mocked responses only).
 */
class OpenMeteoParserTest {

    private final OpenMeteoProvider provider =
            new OpenMeteoProvider("https://api.open-meteo.com", 25, null);

    static final String SINGLE = """
            {
              "latitude": 30.24, "longitude": 75.89, "generationtime_ms": 1.2,
              "utc_offset_seconds": 19800, "timezone": "Asia/Kolkata",
              "daily_units": {"precipitation_sum": "mm"},
              "hourly": {
                "time": ["2026-09-18T10:00", "2026-09-18T11:00",
                         "2026-09-19T13:00", "2026-09-19T14:00"],
                "relative_humidity_2m": [80.0, 82.0, 70.0, null],
                "wind_speed_10m": [10.0, 12.0, 9.0, 11.0],
                "soil_moisture_0_to_7cm": [0.30, 0.32, 0.28, 0.30]
              },
              "daily": {
                "time": ["2026-09-18", "2026-09-19", "2026-09-20"],
                "precipitation_sum": [0.0, 12.4, null],
                "precipitation_probability_max": [5.0, 68.0, null],
                "temperature_2m_max": [33.1, 31.0, 30.0],
                "temperature_2m_min": [24.0, 23.5, 23.0],
                "rain_sum": [0.0, 10.0, null],
                "showers_sum": [0.0, 2.0, 1.0],
                "weather_code": [0, 95, null],
                "et0_fao_evapotranspiration": [4.2, 3.9, null]
              }
            }
            """;

    /** Daily block WITHOUT the agronomic keys and with no hourly block at all. */
    static final String DAILY_ONLY = """
            {
              "latitude": 30.24, "longitude": 75.89,
              "daily": {
                "time": ["2026-09-18"],
                "precipitation_sum": [3.0],
                "precipitation_probability_max": [40.0],
                "temperature_2m_max": [32.0],
                "temperature_2m_min": [24.0]
              }
            }
            """;

    static final String MULTI = """
            [
              {"latitude": 30.39, "longitude": 75.80,
               "daily": {"time": ["2026-09-18"], "precipitation_sum": [1.5],
                         "precipitation_probability_max": [40.0],
                         "temperature_2m_max": [32.0], "temperature_2m_min": [24.0]}},
              {"latitude": 29.93, "longitude": 75.81,
               "daily": {"time": ["2026-09-18"], "precipitation_sum": [2.5],
                         "precipitation_probability_max": [50.0],
                         "temperature_2m_max": [33.0], "temperature_2m_min": [25.0]}}
            ]
            """;

    @Test
    void singleLocationParsesWithUnitsIntact() {
        List<WeatherProvider.LocationDailyForecast> locs = provider.parseResponse(SINGLE, 1);
        assertEquals(1, locs.size());
        assertEquals(3, locs.get(0).days().size());
        assertEquals("2026-09-18", locs.get(0).days().get(0).date().toString());
        assertEquals(0.0, locs.get(0).days().get(0).precipitationMm());
        assertEquals(12.4, locs.get(0).days().get(1).precipitationMm());
        assertEquals(68.0, locs.get(0).days().get(1).precipitationProbability());
        assertEquals(31.0, locs.get(0).days().get(1).temperatureMaxC());
    }

    @Test
    void nullPrecipitationIsPreservedNeverZeroFilled() {
        List<WeatherProvider.LocationDailyForecast> locs = provider.parseResponse(SINGLE, 1);
        assertNull(locs.get(0).days().get(2).precipitationMm(),
                "missing precipitation must stay null, not 0.0");
    }

    @Test
    void multiCoordinateArrayResponseParses() {
        List<WeatherProvider.LocationDailyForecast> locs = provider.parseResponse(MULTI, 2);
        assertEquals(2, locs.size());
        assertEquals(1.5, locs.get(0).days().get(0).precipitationMm());
        assertEquals(2.5, locs.get(1).days().get(0).precipitationMm());
    }

    @Test
    void providerErrorObjectThrows() {
        String err = "{\"error\": true, \"reason\": \"bad param\"}";
        WeatherProviderException e = assertThrows(WeatherProviderException.class,
                () -> provider.parseResponse(err, 1));
        assertTrue(e.getMessage().contains("bad param"));
    }

    @Test
    void missingDailyThrows() {
        assertThrows(WeatherProviderException.class,
                () -> provider.parseResponse("{\"latitude\": 1.0}", 1));
    }

    @Test
    void malformedJsonThrows() {
        assertThrows(WeatherProviderException.class,
                () -> provider.parseResponse("not json", 1));
    }

    @Test
    void requestUrlPinsModelTimezoneAndHorizon() {
        String url = provider.buildUrl(List.of(
                new WeatherProvider.SamplePoint(30.23743, 75.89491)));
        assertTrue(url.contains("models=ecmwf_ifs"), url);
        assertTrue(url.contains("timezone=Asia%2FKolkata"), url);
        assertTrue(url.contains("forecast_days=16"), url);
        assertTrue(url.contains("precipitation_sum"), url);
        assertTrue(url.contains("/v1/forecast?"), url);
    }

    @Test
    void noSecretsInRequest() {
        String url = provider.buildUrl(List.of(
                new WeatherProvider.SamplePoint(30.0, 75.8)));
        assertFalse(url.toLowerCase().contains("key="));
        assertFalse(url.toLowerCase().contains("token="));
    }

    @Test
    void mapperAvailable() {
        assertNotNull(new ObjectMapper());
    }

    @Test
    void agronomicDailyFieldsParse() {
        List<WeatherProvider.LocationDailyForecast> locs = provider.parseResponse(SINGLE, 1);
        WeatherProvider.DailyPointValues d1 = locs.get(0).days().get(1);
        assertEquals(3.9, d1.et0Mm(), "ET0 (FAO-56) mm/day");
        assertEquals(10.0, d1.rainMm());
        assertEquals(2.0, d1.showersMm());
        assertEquals(95, d1.weatherCode(), "WMO weather code");
        // 2026-09-19 soil moisture hours: 0.28 and 0.30 -> mean 0.29
        assertEquals(0.29, d1.soilMoisture0To7CmVwc(), 1e-9,
                "0-7 cm VWC from hourly daily mean");
        // 2026-09-19 humidity hours: 70 and null -> mean 70 (null hour skipped)
        assertEquals(70.0, d1.humidityPct(), 1e-9);
        assertEquals(10.0, d1.windSpeedKmh(), 1e-9);
    }

    @Test
    void missingAgronomicFieldsStayNullNeverZeroFilled() {
        List<WeatherProvider.LocationDailyForecast> locs = provider.parseResponse(SINGLE, 1);
        WeatherProvider.DailyPointValues d3 = locs.get(0).days().get(2);
        assertNull(d3.et0Mm(), "ET0 absent for a day must stay null");
        assertNull(d3.soilMoisture0To7CmVwc(),
                "a local day with no valid soil-moisture hour must be null, not zero");
        assertNull(d3.weatherCode());
    }

    @Test
    void dailyOnlyLegacyShapeStillParsesWithNullExtras() {
        List<WeatherProvider.LocationDailyForecast> locs = provider.parseResponse(DAILY_ONLY, 1);
        assertEquals(1, locs.size());
        WeatherProvider.DailyPointValues d = locs.get(0).days().get(0);
        assertEquals(3.0, d.precipitationMm());
        assertNull(d.et0Mm());
        assertNull(d.soilMoisture0To7CmVwc());
        assertNull(d.weatherCode());
        assertNull(d.rainMm());
    }

    @Test
    void requestUrlRequestsAgronomicVariables() {
        String url = provider.buildUrl(List.of(
                new WeatherProvider.SamplePoint(30.0, 75.8)));
        assertTrue(url.contains("et0_fao_evapotranspiration"), url);
        assertTrue(url.contains("soil_moisture_0_to_7cm"), url);
        assertTrue(url.contains("rain_sum"), url);
        assertTrue(url.contains("showers_sum"), url);
        assertTrue(url.contains("weather_code"), url);
    }
}
