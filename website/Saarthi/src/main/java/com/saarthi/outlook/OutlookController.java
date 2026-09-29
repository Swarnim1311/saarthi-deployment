package com.saarthi.outlook;

import com.saarthi.outlook.OutlookService.OutlookUnavailableException;
import com.saarthi.service.RealForecastService;
import com.saarthi.service.RealForecastService.BlockNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Display-only Weeks 3-4 outlook (Phase 3B).
 * Additive tree — the validated 7-day and live weather trees are untouched.
 */
@RestController
@RequestMapping("/api/outlook")
public class OutlookController {

    private final OutlookService outlook;

    @Autowired
    public OutlookController(OutlookService outlook) {
        this.outlook = outlook;
    }

    /** W3/W4 outlook for all six blocks. */
    @GetMapping("/17-30")
    public ResponseEntity<Map<String, Object>> allBlocks() {
        return ResponseEntity.ok(outlook.getOutlook());
    }

    /** W3/W4 outlook for one block (name or Bhuvan id, case-insensitive). */
    @GetMapping("/17-30/{blockId}")
    public ResponseEntity<Map<String, Object>> oneBlock(@PathVariable("blockId") String blockId) {
        return ResponseEntity.ok(outlook.getBlockOutlook(blockId));
    }

    /** Freshness of the outlook inputs (no upstream fetch). */
    @GetMapping("/freshness")
    public ResponseEntity<Map<String, Object>> freshness() {
        return ResponseEntity.ok(outlook.getFreshness());
    }

    @ExceptionHandler(BlockNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUnknownBlock(BlockNotFoundException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "unknown_block");
        err.put("message", ex.getMessage());
        err.put("valid_blocks", RealForecastService.BLOCKS);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err);
    }

    @ExceptionHandler(OutlookUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleUnavailable(OutlookUnavailableException ex) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", "outlook_unavailable");
        err.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(err);
    }
}
