package com.saarthi.farmer;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Farmer profiles.
 *
 * <ul>
 *   <li>{@code GET /api/farmers} — all stored profiles.</li>
 *   <li>{@code GET /api/farmers/{id}} — exactly one profile; unknown ids are
 *       404 {@code unknown_farmer}. Only the requested record is returned.</li>
 *   <li>{@code POST /api/farmers} — create or update; missing required fields
 *       are 400. Returns 201 with the stored profile.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/farmers")
public class FarmerController {

    private final FarmerService farmers;

    public FarmerController(FarmerService farmers) {
        this.farmers = farmers;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> all() {
        List<Farmer> list = farmers.all();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("farmers", list);
        out.put("count", list.size());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> one(@PathVariable("id") String id) {
        Farmer farmer = farmers.byId(id);
        if (farmer == null) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "unknown_farmer");
            err.put("message", "No farmer profile found for id '"
                    + (id == null ? "" : id.trim()) + "'.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(err);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("farmer", farmer);
        return ResponseEntity.ok(out);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> save(
            @RequestBody(required = false) Farmer farmer) {
        try {
            Farmer stored = farmers.save(farmer);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("message", "Farmer profile saved successfully.");
            out.put("farmer", stored);
            return ResponseEntity.status(HttpStatus.CREATED).body(out);
        } catch (IllegalArgumentException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "bad_request");
            err.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(err);
        }
    }
}
