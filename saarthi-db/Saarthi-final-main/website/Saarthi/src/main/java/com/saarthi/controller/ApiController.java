package com.saarthi.controller;

import com.saarthi.model.*;
import com.saarthi.service.AgronomyService;
import com.saarthi.service.DistrictDataService;
import com.saarthi.service.RealForecastService;
import com.saarthi.service.RealForecastService.BlockNotFoundException;
import com.saarthi.weather.WeatherController.WeatherUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ApiController {

    @Autowired
    private DistrictDataService districtService;

    @Autowired
    private RealForecastService forecastService;

    @Autowired
    private AgronomyService agronomyService;

    @Autowired
    private com.saarthi.service.ClimateContextService climateService;

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        Map<String, Object> latest = forecastService.getLatest();
        @SuppressWarnings("unchecked")
        Map<String, Object> forecast = (Map<String, Object>) latest.get("forecast");
        Map<String, Object> freshness = forecastService.getFreshness();
        HealthResponse resp = new HealthResponse();
        resp.setStatus("ok");
        resp.setForecastAvailable(true);
        resp.setIssueDate(Objects.toString(forecast.get("issue_date"), null));
        List<Map<String, Object>> blocks = forecastService.getForecastBlocks();
        resp.setValidFrom(dateOf(blocks, 0));
        resp.setValidTo(dateOf(blocks, RealForecastService.HORIZON_DAYS - 1));
        resp.setBlocksAvailable(blocks.size());
        resp.setForecastHorizonDays(RealForecastService.HORIZON_DAYS);
        resp.setModel(RealForecastService.MODEL_TYPE);
        resp.setGeneratedAt(Objects.toString(forecast.get("generated_at"), null));
        resp.setSource(Objects.toString(freshness.get("source"), "Raw CHIRPS-GEFS (model_type=raw_gefs)"));
        resp.setStale(Boolean.TRUE.equals(freshness.get("stale")));
        Object age = freshness.get("age_days");
        resp.setAgeDays(age instanceof Number ? ((Number) age).intValue() : -1);
        resp.setExpiresAt(Objects.toString(freshness.get("expires_at"), null));
        return ResponseEntity.ok(resp);
    }

    private String dateOf(List<Map<String, Object>> blocks, int leadIndex) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daily = (List<Map<String, Object>>) blocks.get(0).get("daily_forecast");
        return Objects.toString(daily.get(leadIndex).get("date"), null);
    }

    @GetMapping("/panchayats")
    public ResponseEntity<Map<String, Object>> getPanchayats(@RequestParam(value = "block", required = false) String block) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (block != null && !block.isEmpty()) {
            if (!districtService.isKnownBlock(block)) {
                throw new BlockNotFoundException(block);
            }
            List<String> list = districtService.getPanchayats(block);
            resp.put("block", block);
            resp.put("panchayats", list);
            resp.put("total_in_block", list.size());
            return ResponseEntity.ok(resp);
        }
        resp.put("district", "Sangrur");
        resp.put("blocks", districtService.getBlocks());
        resp.put("panchayats_by_block", DistrictDataService.BLOCK_PANCHAYATS);
        resp.put("total_indexed", districtService.getTotalPanchayats());
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/blocks")
    public ResponseEntity<Map<String, Object>> getBlocks() {
        return ResponseEntity.ok(forecastService.getBlocksDoc());
    }

    @GetMapping(value = "/blocks/geojson", produces = "application/geo+json")
    public ResponseEntity<String> getBlocksGeoJson() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .body(forecastService.getGeoJson());
    }

    @GetMapping("/forecast/latest")
    public ResponseEntity<Map<String, Object>> getLatestForecast() {
        return ResponseEntity.ok(forecastService.getLatest());
    }

    /**
     * P0: re-read the NB06 application package without restarting the JVM.
     * Serve a refreshed forecast after: re-run NB05 -&gt; NB06 -&gt; copy package
     * into {@code src/main/resources/forecast/} (or the {@code saarthi.forecast.path}
     * directory), then call this endpoint. Never fabricates data — on a
     * missing/invalid package the previous forecast keeps being served and this
     * endpoint returns HTTP 500 with the cause.
     */
    @PostMapping("/forecast/reload")
    public ResponseEntity<Map<String, Object>> reloadForecast() {
        return ResponseEntity.ok(forecastService.reload());
    }

    @GetMapping("/forecast/freshness")
    public ResponseEntity<Map<String, Object>> getFreshness() {
        return ResponseEntity.ok(forecastService.getFreshness());
    }

    @GetMapping("/forecast/{blockId}")
    public ResponseEntity<BlockForecastResponse> getBlockForecast(@PathVariable("blockId") String blockId) {
        Map<String, Object> b = forecastService.findBlock(blockId)
                .orElseThrow(() -> new BlockNotFoundException(blockId));
        return ResponseEntity.ok(toBlockForecast(b));
    }

    @GetMapping("/forecast/summary")
    public ResponseEntity<SummaryResponse> getSummary() {
        Map<String, Object> s = forecastService.getSummary();
        SummaryResponse resp = new SummaryResponse();
        resp.setDistrict(Objects.toString(s.get("district"), "Sangrur"));
        resp.setIssueDate(Objects.toString(s.get("issue_date"), null));
        resp.setValidFrom(Objects.toString(s.get("valid_from"), null));
        resp.setValidTo(Objects.toString(s.get("valid_to"), null));
        resp.setHorizonDays(((Number) s.getOrDefault("forecast_horizon_days", 7)).intValue());
        resp.setBlocksCount(((Number) s.getOrDefault("blocks_count", 6)).intValue());
        resp.setHighestBlock(Objects.toString(s.get("highest_rainfall_block"), null));
        resp.setHighestMm(((Number) s.get("highest_rainfall_mm")).doubleValue());
        resp.setLowestBlock(Objects.toString(s.get("lowest_rainfall_block"), null));
        resp.setLowestMm(((Number) s.get("lowest_rainfall_mm")).doubleValue());
        resp.setHighBlocks(castStringList(s.get("high_risk_blocks")));
        resp.setNormalBlocks(castStringList(s.get("normal_blocks")));
        resp.setLowBlocks(castStringList(s.get("low_risk_blocks")));
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/advisories/{blockId}")
    public ResponseEntity<AdvisoryResponse> getAdvisories(@PathVariable("blockId") String blockId) {
        Map<String, Object> b = forecastService.findBlock(blockId)
                .orElseThrow(() -> new BlockNotFoundException(blockId));
        AdvisoryResponse resp = new AdvisoryResponse();
        resp.setBlockId(Objects.toString(b.get("block_id"), null));
        resp.setBlockName(Objects.toString(b.get("block_name"), null));
        resp.setCategory(Objects.toString(b.get("category"), null));
        @SuppressWarnings("unchecked")
        Map<String, Object> prob = (Map<String, Object>) b.get("probability");
        resp.setProbLow(((Number) prob.get("low")).doubleValue());
        resp.setProbNormal(((Number) prob.get("normal")).doubleValue());
        resp.setProbHigh(((Number) prob.get("high")).doubleValue());
        resp.setTotalMm(((Number) b.get("forecast_7d_total_rainfall_mm")).doubleValue());
        resp.setAdvisories(castMapList(b.get("advisories")));
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/farmer-analysis")
    public ResponseEntity<FarmerAnalysisResponse> farmerAnalysis(@RequestBody FarmerAnalysisRequest req) {
        return ResponseEntity.ok(agronomyService.computeFarmerAnalysis(req));
    }

    @GetMapping("/climate-context")
    public ResponseEntity<Map<String, Object>> getClimateContext() {
        return ResponseEntity.ok(climateService.getSummary());
    }

    @GetMapping("/mjo/latest")
    public ResponseEntity<Map<String, Object>> getMjoLatest() {
        return ResponseEntity.ok(climateService.getMjo());
    }

    /**
     * P0: re-read the climate context package without restarting the JVM.
     * Fail-soft (mirrors startup): on a missing/invalid package returns
     * {@code available=false} with HTTP 200 — the rainfall forecast keeps
     * working. DMI/IOD values pass through as numeric JSON numbers.
     */
    @PostMapping("/climate-context/reload")
    public ResponseEntity<Map<String, Object>> reloadClimateContext() {
        return ResponseEntity.ok(climateService.reload());
    }

    @SuppressWarnings("unchecked")
    private BlockForecastResponse toBlockForecast(Map<String, Object> b) {
        Map<String, Object> latest = forecastService.getLatest();
        Map<String, Object> forecast = (Map<String, Object>) latest.get("forecast");
        Map<String, Object> prob = (Map<String, Object>) b.get("probability");
        BlockForecastResponse resp = new BlockForecastResponse();
        resp.setBlockId(Objects.toString(b.get("block_id"), null));
        resp.setBlockName(Objects.toString(b.get("block_name"), null));
        resp.setIssueDate(Objects.toString(forecast.get("issue_date"), null));
        List<Map<String, Object>> daily = (List<Map<String, Object>>) b.get("daily_forecast");
        resp.setValidFrom(Objects.toString(daily.get(0).get("date"), null));
        resp.setValidTo(Objects.toString(daily.get(daily.size() - 1).get("date"), null));
        resp.setTotalMm(((Number) b.get("forecast_7d_total_rainfall_mm")).doubleValue());
        resp.setCategory(Objects.toString(b.get("category"), null));
        resp.setProbLow(((Number) prob.get("low")).doubleValue());
        resp.setProbNormal(((Number) prob.get("normal")).doubleValue());
        resp.setProbHigh(((Number) prob.get("high")).doubleValue());
        resp.setDaily(daily);
        resp.setIndicators((Map<String, Object>) b.get("indicators"));
        resp.setAdvisories(castMapList(b.get("advisories")));
        return resp;
    }

    @SuppressWarnings("unchecked")
    private List<String> castStringList(Object o) {
        if (o instanceof List) {
            List<String> out = new ArrayList<>();
            for (Object v : (List<?>) o) out.add(Objects.toString(v, null));
            return out;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castMapList(Object o) {
        if (o instanceof List) return (List<Map<String, Object>>) o;
        return Collections.emptyList();
    }

    @ExceptionHandler(BlockNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownBlock(BlockNotFoundException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "unknown_block");
        err.put("message", ex.getMessage());
        err.put("valid_blocks", RealForecastService.BLOCKS);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err);
    }

    @ExceptionHandler(WeatherUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleForecastUnavailable(WeatherUnavailableException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "forecast_unavailable");
        err.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(err);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleReloadFailure(IllegalStateException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "forecast_reload_failed");
        err.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "bad_request");
        err.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
    }
}
