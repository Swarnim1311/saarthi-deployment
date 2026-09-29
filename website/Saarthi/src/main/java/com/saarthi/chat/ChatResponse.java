package com.saarthi.chat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Response from {@code POST /api/chat}.
 *
 * <p>Carries only what the browser needs. It deliberately has no field for the
 * API key, the provider request, or an exception: {@code mode} says which path
 * answered, and nothing else is exposed.
 *
 * @param reply       the answer, already in the requested language
 * @param mode        {@code gemini} or {@code local_fallback}
 * @param language    the language actually used
 * @param contextUsed what SAARTHI context was available for this answer
 */
public record ChatResponse(String reply, String mode, String language,
                           Map<String, Object> contextUsed) {

    public static final String MODE_GEMINI = "gemini";
    public static final String MODE_LOCAL = "local_fallback";

    public boolean isGemini() {
        return MODE_GEMINI.equals(mode);
    }

    /** Wire form. Field order is stable so the client can read it predictably. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reply", reply);
        m.put("mode", mode);
        m.put("language", language);
        m.put("contextUsed", contextUsed == null ? new LinkedHashMap<>() : contextUsed);
        return m;
    }
}
