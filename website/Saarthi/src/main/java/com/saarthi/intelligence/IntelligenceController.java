package com.saarthi.intelligence;

import com.saarthi.service.RealForecastService;
import com.saarthi.weather.WeatherController;
import com.saarthi.weather.WeatherProviderException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BUILD 1 intelligence endpoints (additive {@code /api/intelligence} tree).
 */
@RestController
@RequestMapping("/api/intelligence")
public class IntelligenceController {

    private final ForecastContextService contexts;
    private final SectorRiskService sectors;
    private final GridGroundwaterService grid;
    private final BlockAgriAdvisoryService advisory;

    @Autowired
    public IntelligenceController(ForecastContextService contexts,
            SectorRiskService sectors, GridGroundwaterService grid,
            BlockAgriAdvisoryService advisory) {
        this.contexts = contexts;
        this.sectors = sectors;
        this.grid = grid;
        this.advisory = advisory;
    }

    /** Shared forecast + environment context for one selection. */
    @GetMapping("/context")
    public ResponseEntity<Map<String, Object>> context(
            @RequestParam(value = "block", required = false) String block,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "district", required = false) String district,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lon", required = false) Double lon,
            @RequestParam(value = "name", required = false) String name) {
        ForecastContext ctx = contexts.build(block, state, district, code, lat, lon, name);
        return ResponseEntity.ok(envelope(ctx));
    }

    /** All four sector risks from one shared context. */
    @GetMapping("/risks")
    public ResponseEntity<Map<String, Object>> risks(
            @RequestParam(value = "block", required = false) String block,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "district", required = false) String district,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lon", required = false) Double lon,
            @RequestParam(value = "name", required = false) String name) {
        ForecastContext ctx = contexts.build(block, state, district, code, lat, lon, name);
        return ResponseEntity.ok(sectorsMap(ctx));
    }

    /** Irrigation/grid-pressure indicator only. */
    @GetMapping("/grid-groundwater")
    public ResponseEntity<Map<String, Object>> gridGroundwater(
            @RequestParam(value = "block", required = false) String block,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "district", required = false) String district,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lon", required = false) Double lon,
            @RequestParam(value = "name", required = false) String name) {
        ForecastContext ctx = contexts.build(block, state, district, code, lat, lon, name);
        Map<String, Object> out = envelope(ctx);
        out.put("energy_groundwater", grid.assess(ctx).toMap());
        return ResponseEntity.ok(out);
    }

    /** Logistics + warehouse indicators only. */
    @GetMapping("/logistics-warehouse")
    public ResponseEntity<Map<String, Object>> logisticsWarehouse(
            @RequestParam(value = "block", required = false) String block,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "district", required = false) String district,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "lat", required = false) Double lat,
            @RequestParam(value = "lon", required = false) Double lon,
            @RequestParam(value = "name", required = false) String name) {
        ForecastContext ctx = contexts.build(block, state, district, code, lat, lon, name);
        Map<String, Object> out = envelope(ctx);
        Map<String, Object> sr = new LinkedHashMap<>();
        sr.put("logistics", sectors.logistics(ctx).toMap());
        sr.put("warehouse", sectors.warehouse(ctx).toMap());
        out.put("sectors", sr);
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> sectorsMap(ForecastContext ctx) {
        SectorResult energy = grid.assess(ctx);
        Map<String, SectorResult> all = sectors.assessAll(ctx, energy);
        Map<String, Object> out = envelope(ctx);
        Map<String, Object> sr = new LinkedHashMap<>();
        for (Map.Entry<String, SectorResult> e : all.entrySet()) {
            sr.put(e.getKey(), e.getValue().toMap());
        }
        out.put("sectors", sr);
        // Block-specific agricultural reading (Task 6): derived from the SAME
        // shared context — no extra weather call, no new thresholds.
        out.put("block_advisory", advisory.advise(ctx));
        return out;
    }

    private Map<String, Object> envelope(ForecastContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", ctx.provider());
        out.put("model", ctx.model());
        out.put("stale", ctx.stale());
        out.put("context", ctx.toMap());
        return out;
    }

    @ExceptionHandler(RealForecastService.BlockNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownBlock(
            RealForecastService.BlockNotFoundException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "unknown_block");
        err.put("message", ex.getMessage());
        err.put("valid_blocks", RealForecastService.BLOCKS);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err);
    }

    @ExceptionHandler(WeatherProviderException.class)
    public ResponseEntity<Map<String, Object>> handleProvider(WeatherProviderException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "provider_error");
        err.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(err);
    }

    @ExceptionHandler(WeatherController.WeatherUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUnavailable(
            WeatherController.WeatherUnavailableException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "forecast_unavailable");
        err.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(err);
    }

    @ExceptionHandler(com.saarthi.geo.GeographyController.UnknownGeographyException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownGeo(
            com.saarthi.geo.GeographyController.UnknownGeographyException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", ex.getError());
        err.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "bad_request");
        err.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
    }
}
