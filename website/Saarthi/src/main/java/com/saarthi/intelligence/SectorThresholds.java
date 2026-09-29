package com.saarthi.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * BUILD 1 threshold holder (additive).
 */
@Component
public class SectorThresholds {

    private static final Logger log = LoggerFactory.getLogger(SectorThresholds.class);
    static final String RESOURCE = "intelligence/sector-thresholds.json";

    double wetMm = 1.0;
    int agriHighWet = 2;
    int agriModWet = 1;
    int logHighWet = 2;
    double logHighMax = 20.0;
    double logHigh3d = 35.0;
    int logModWet = 1;
    double logMod3d = 15.0;
    int whHighWet7 = 4;
    double whHigh7d = 50.0;
    int whModWet7 = 2;
    double whMod7d = 20.0;
    int gridHighDry = 6;
    double gridHighDef = -10.0;
    int gridModDry = 4;
    double gridModDef = -5.0;
    double gridDrySoil = 0.12;
    String methodVersion = "sector-v1";
    String validationNote =
            "Rule-based indicator; current IFS risk validation pending.";

    public SectorThresholds() {
        this(RESOURCE);
    }

    SectorThresholds(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonNode root = new ObjectMapper().readTree(in);
            methodVersion = root.path("method_version").asText("sector-v1");
            validationNote = root.path("validation_note").asText(validationNote);
            wetMm = root.path("wet_day_mm").asDouble(1.0);
            JsonNode a = root.path("agriculture");
            agriHighWet = a.path("high_min_wet_days_d1_d3").asInt(2);
            agriModWet = a.path("moderate_min_wet_days_d1_d3").asInt(1);
            JsonNode l = root.path("logistics");
            logHighWet = l.path("high_min_wet_days_d1_d3").asInt(2);
            logHighMax = l.path("high_max_daily_mm").asDouble(20.0);
            logHigh3d = l.path("high_rain_3d_mm").asDouble(35.0);
            logModWet = l.path("moderate_min_wet_days_d1_d3").asInt(1);
            logMod3d = l.path("moderate_rain_3d_mm").asDouble(15.0);
            JsonNode w = root.path("warehouse");
            whHighWet7 = w.path("high_min_wet_days_d1_d7").asInt(4);
            whHigh7d = w.path("high_rain_7d_mm").asDouble(50.0);
            whModWet7 = w.path("moderate_min_wet_days_d1_d7").asInt(2);
            whMod7d = w.path("moderate_rain_7d_mm").asDouble(20.0);
            JsonNode g = root.path("energy_groundwater");
            gridHighDry = g.path("high_min_dry_days_d1_d7").asInt(6);
            gridHighDef = g.path("high_deficit_7d_mm").asDouble(-10.0);
            gridModDry = g.path("moderate_min_dry_days_d1_d7").asInt(4);
            gridModDef = g.path("moderate_deficit_7d_mm").asDouble(-5.0);
            gridDrySoil = g.path("dry_soil_moisture_vwc").asDouble(0.12);
        } catch (Exception e) {
            log.warn("Sector thresholds unavailable ({}); compiled defaults apply",
                    e.getMessage());
        }
    }
}
