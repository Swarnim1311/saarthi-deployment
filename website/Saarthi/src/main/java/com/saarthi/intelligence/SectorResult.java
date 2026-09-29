package com.saarthi.intelligence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BUILD 1 sector result envelope (shared by all four sectors).
 */
public record SectorResult(
        String sector,
        String state,
        List<String> reasons,
        Map<String, Object> evidence,
        List<String> actions,
        List<String> assumptions,
        String validationNote,
        boolean available,
        String unavailableReason) {

    static SectorResult of(String sector, String state, List<String> reasons,
            Map<String, Object> evidence, List<String> actions,
            List<String> assumptions, String validationNote) {
        return new SectorResult(sector, state, List.copyOf(reasons),
                new LinkedHashMap<>(evidence), List.copyOf(actions),
                List.copyOf(assumptions), validationNote, true, null);
    }

    static SectorResult unavailable(String sector, String reason, List<String> assumptions,
            String validationNote) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("unavailable_reason", reason);
        List<String> r = new ArrayList<>();
        r.add("Required forecast data is incomplete, so no sector verdict is given.");
        return new SectorResult(sector, "UNAVAILABLE", r, ev, List.of(
                "Retry when the live forecast is complete; do not treat missing data as low risk."),
                List.copyOf(assumptions), validationNote, false, reason);
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sector", sector);
        out.put("state", state);
        out.put("available", available);
        if (!available) out.put("unavailable_reason", unavailableReason);
        out.put("reasons", reasons);
        out.put("evidence", evidence);
        out.put("actions", actions);
        out.put("assumptions", assumptions);
        out.put("validation_note", validationNote);
        return out;
    }
}
