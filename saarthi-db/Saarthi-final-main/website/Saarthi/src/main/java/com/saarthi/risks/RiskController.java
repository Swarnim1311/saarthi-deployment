package com.saarthi.risks;

import com.saarthi.risks.FieldWorkService.InvalidWindowException;
import com.saarthi.risks.FieldWorkService.RiskUnavailableException;
import com.saarthi.service.RealForecastService;
import com.saarthi.service.RealForecastService.BlockNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agricultural risk endpoints (Phase 4.3: composite_v1 priority composite).
 * Additive tree — the weather, outlook, and shadow paths are untouched.
 * Risk is derived from the already-retrieved live IFS forecast; no second
 * upstream request is ever made here. Legacy FIELD_WORK_DISRUPTION keys are
 * preserved byte-compatibly; composite keys (overall_risk, primary_concern,
 * risks, context, advisories, composite_method_version) are additive.
 */
@RestController
@RequestMapping("/api/risks")
public class RiskController {

    private final CompositeRiskService risks;

    @Autowired
    public RiskController(CompositeRiskService risks) {
        this.risks = risks;
    }

    /** Composite agricultural risk for all six blocks (D+1–D+3 primary). */
    @GetMapping
    public ResponseEntity<Map<String, Object>> allBlocks(
            @RequestParam(value = "window", required = false) String window) {
        return ResponseEntity.ok(risks.getAllComposites(window));
    }

    /** Composite agricultural risk for one block (id or name, case-insensitive). */
    @GetMapping("/{blockId}")
    public ResponseEntity<Map<String, Object>> oneBlock(
            @PathVariable("blockId") String blockId,
            @RequestParam(value = "window", required = false) String window) {
        return ResponseEntity.ok(risks.getBlockComposite(blockId, window));
    }

    @ExceptionHandler(BlockNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownBlock(BlockNotFoundException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "unknown_block");
        err.put("message", ex.getMessage());
        err.put("valid_blocks", RealForecastService.BLOCKS);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err);
    }

    @ExceptionHandler(InvalidWindowException.class)
    public ResponseEntity<Map<String, Object>> handleWindow(InvalidWindowException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "invalid_window");
        err.put("message", ex.getMessage());
        err.put("supported_windows", java.util.List.of(FieldWorkService.WINDOW_3D));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(err);
    }

    @ExceptionHandler(RiskUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUnavailable(RiskUnavailableException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "risk_unavailable");
        err.put("message", ex.getMessage());
        err.put("retry", "Retry later; no fallback risk is synthesised");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(err);
    }
}
