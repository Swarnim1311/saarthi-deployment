package com.saarthi.market;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Official mandi-price endpoints (CropAtlas Task 12).
 *
 * <ul>
 *   <li>{@code /api/market/price?crop=&state=[&district=]} — latest verified
 *       official price for one crop, or an honest unavailable state.</li>
 *   <li>{@code /api/market/status} — which official source is configured and
 *       whether a key is present (presence only, never the value).</li>
 * </ul>
 *
 * <p>Both endpoints are read-only, cached, and fail-soft: they never throw a
 * 500 for an upstream problem, and they never return a price that the official
 * feed did not serve with a real record date.
 */
@RestController
@RequestMapping("/api/market")
public class MarketPriceController {

    private final MandiPriceService prices;

    @Autowired
    public MarketPriceController(MandiPriceService prices) {
        this.prices = prices;
    }

    /** Latest verified official price for one crop/state (optionally district). */
    @GetMapping("/price")
    public ResponseEntity<Map<String, Object>> price(
            @RequestParam(value = "crop", required = false) String crop,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "district", required = false) String district) {
        if (crop == null || crop.isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("available", false);
            err.put("reason", "bad_request");
            err.put("message", "A crop id is required, e.g. ?crop=maize&state=Punjab.");
            return ResponseEntity.badRequest().body(err);
        }
        return ResponseEntity.ok(prices.latest(crop, state, district));
    }

    /** Source configuration state: source id, key presence, never the key. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", "Agmarknet via data.gov.in (Ministry of Agriculture and Farmers Welfare)");
        out.put("source_url", "https://www.data.gov.in/resource/"
                + "current-daily-price-various-commodities-various-markets-mandi");
        out.put("api_key_configured", prices.resolveApiKey() != null);
        out.put("note", "Without a configured data.gov.in API key, /api/market/price serves "
                + "available:false with the reason not_configured. No price is ever fabricated.");
        return ResponseEntity.ok(out);
    }
}
