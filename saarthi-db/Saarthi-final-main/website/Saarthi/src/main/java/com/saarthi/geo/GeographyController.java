package com.saarthi.geo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cascading geography dropdowns: State → District → Block.
 *
 * <p>Serves the bundled {@code GeographyService} registry only. Unknown
 * codes are 404 ({@code unknown_state}/{@code unknown_district}); missing
 * query params are 400. Never a silent fallback to another state/district.
 */
@RestController
@RequestMapping("/api/geography")
public class GeographyController {

    private final GeographyService geography;

    @Autowired(required = false)
    private BlockBoundaryService boundaries;

    @Autowired
    public GeographyController(GeographyService geography) {
        this.geography = geography;
    }

    /** Test seam: inject the boundary service without a Spring context. */
    void setBoundaries(BlockBoundaryService boundaries) {
        this.boundaries = boundaries;
    }

    /** All states in the registry. */
    @GetMapping("/states")
    public ResponseEntity<Map<String, Object>> states() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("states", geography.states());
        out.put("count", geography.states().size());
        return ResponseEntity.ok(out);
    }

    /** Districts of one state ({@code ?state=<state_code>}). */
    @GetMapping("/districts")
    public ResponseEntity<Map<String, Object>> districts(
            @RequestParam(value = "state", required = false) String stateCode) {
        if (stateCode == null || stateCode.isBlank()) {
            throw new IllegalArgumentException("Query parameter 'state' (state_code) is required");
        }
        List<Map<String, String>> districts = geography.districts(stateCode.trim());
        if (districts.isEmpty()) {
            throw new UnknownGeographyException("unknown_state",
                    "Unknown state_code '" + stateCode + "'");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state_code", stateCode.trim());
        out.put("districts", districts);
        out.put("count", districts.size());
        return ResponseEntity.ok(out);
    }

    /** Blocks of one district ({@code ?district=<district_code>}). */    @GetMapping("/blocks")
    public ResponseEntity<Map<String, Object>> blocks(
            @RequestParam(value = "district", required = false) String districtCode) {
        if (districtCode == null || districtCode.isBlank()) {
            throw new IllegalArgumentException(
                    "Query parameter 'district' (district_code) is required");
        }
        List<GeographyService.BlockRef> list = geography.blocks(districtCode.trim());
        if (list.isEmpty()) {
            throw new UnknownGeographyException("unknown_district",
                    "Unknown district_code '" + districtCode + "'");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("district_code", districtCode.trim());
        out.put("blocks", list);
        out.put("count", list.size());
        return ResponseEntity.ok(out);
    }

    /**
     * Name search across registry blocks ({@code ?name=<substring>}).
     * Lets legacy UI names resolve to registry rows (with coordinates)
     * without hardcoding geography in JavaScript. Empty query → empty list.
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(value = "name", required = false) String name) {
        List<GeographyService.BlockRef> list = geography.search(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", name == null ? "" : name.trim());
        out.put("blocks", list);
        out.put("count", list.size());
        return ResponseEntity.ok(out);
    }

    /** Unknown state/district code: explicit 404, never another region's data. */
    public static class UnknownGeographyException extends RuntimeException {
        private final String error;

        public UnknownGeographyException(String error, String message) {
            super(message);
            this.error = error;
        }

        public String getError() {
            return error;
        }
    }

    /**
     * Display boundary for ONE selected block
     * ({@code ?state=&district=&block=} codes). Returns a GeoJSON Feature
     * with the simplified block polygon, or 404 {@code boundary_unavailable}
     * when no compiled geometry exists (geocoded rows, legacy-only ids) —
     * never another block's geometry.
     */
    @GetMapping("/block-boundary")
    public ResponseEntity<Map<String, Object>> blockBoundary(
            @RequestParam(value = "state", required = false) String stateCode,
            @RequestParam(value = "district", required = false) String districtCode,
            @RequestParam(value = "block", required = false) String blockCode) {
        if (stateCode == null || stateCode.isBlank()
                || districtCode == null || districtCode.isBlank()
                || blockCode == null || blockCode.isBlank()) {
            throw new IllegalArgumentException(
                    "Query parameters 'state', 'district' and 'block' (codes) are required");
        }
        if (boundaries == null) {
            throw new UnknownGeographyException("boundary_unavailable",
                    "Block boundary service is not loaded");
        }
        var geom = boundaries.findBoundary(
                stateCode.trim(), districtCode.trim(), blockCode.trim());
        if (geom.isEmpty()) {
            throw new UnknownGeographyException("boundary_unavailable",
                    "No compiled boundary for block '"
                            + stateCode.trim() + ":" + districtCode.trim() + ":"
                            + blockCode.trim() + "'");
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("state_code", stateCode.trim());
        props.put("district_code", districtCode.trim());
        props.put("block_code", blockCode.trim());
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("properties", props);
        feature.put("geometry", geom.get());
        return ResponseEntity.ok(feature);
    }

    @ExceptionHandler(UnknownGeographyException.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(UnknownGeographyException ex) {
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
