package com.saarthi.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal Gemini caller over the REST generateContent endpoint.
 *
 * <p><b>No new dependency.</b> This deliberately uses the JDK's
 * {@link HttpClient} for the same reason {@code OpenMeteoProvider} and
 * {@code SoilGridsClient} do: the platform already integrates two external
 * services this way, so adding a Gemini SDK would be a heavier change with no
 * benefit for a single JSON call.
 *
 * <p><b>Bounded on every axis.</b> Request timeout and connect timeout are set
 * explicitly; the outgoing payload is capped; the response is read with a hard
 * size limit; and the API key travels only in a request header, never in a URL,
 * a log line, or an exception.
 *
 * <p><b>Total failure containment.</b> Any problem — no key, timeout, HTTP error,
 * oversized body, malformed JSON, empty content — returns {@code null} rather
 * than throwing, and the raw provider error is logged at debug level only. The
 * caller then uses the local corpus. Nothing from Gemini's response other than
 * the assistant's text ever leaves this class.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);

    /**
     * Default model. {@code gemini-1.5-flash} was shut down by Google, and older
     * 2.x Flash models answer new API keys with 404 ("no longer available to
     * new users"), so the default is {@code gemini-3.8-flash} — verified live
     * against the generateContent contract. Override without a rebuild via
     * {@code -Dsaarthi.chat.gemini.model=<id>} when Google retires it.
     */
    static final String DEFAULT_MODEL = "gemini-3.8-flash";
    private static final String ENDPOINT_PREFIX =
            "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String ENDPOINT_SUFFIX = ":generateContent";

    /** Hard cap on the request we will build, to bound cost and latency. */
    static final int MAX_REQUEST_CHARS = 24_000;

    /** Hard cap on the response we will read into memory. */
    static final int MAX_RESPONSE_BYTES = 256 * 1024;

    private final GeminiKeyResolver keys;
    private final HttpClient http;
    private final int timeoutSeconds;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    public GeminiClient(GeminiKeyResolver keys,
            @Value("${saarthi.chat.gemini.timeout-seconds:20}") int timeoutSeconds,
            @Value("${saarthi.chat.gemini.model:gemini-3.8-flash}") String model) {
        this.keys = keys;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 20 : timeoutSeconds;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Backwards-compatible construction with the default model. */
    GeminiClient(GeminiKeyResolver keys, int timeoutSeconds) {
        this(keys, timeoutSeconds, DEFAULT_MODEL, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /** Test seam: explicit model without Spring. */
    GeminiClient(GeminiKeyResolver keys, int timeoutSeconds, String model, HttpClient http) {
        this.keys = keys;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 20 : timeoutSeconds;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
        this.http = http;
    }

    public boolean isConfigured() {
        return keys.isConfigured();
    }

    /**
     * Ask Gemini. Returns the assistant's text, or {@code null} if Gemini cannot
     * be used for any reason — in which case the caller must fall back.
     */
    public String ask(String systemInstruction, String userText) {
        if (!keys.isConfigured()) {
            log.debug("GEMINI FALLBACK: NO_API_KEY");
            return null;
        }
        String endpoint = ENDPOINT_PREFIX + model + ENDPOINT_SUFFIX;
        try {
            String payload = buildPayload(systemInstruction, userText);
            if (payload == null) return null;
            log.debug("GEMINI REQUEST START model={} payloadChars={}", model, payload.length());

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    // The key is a header, so it can never land in a proxy log or a URL.
                    .header("x-goog-api-key", keys.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<byte[]> response =
                    http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            log.debug("GEMINI HTTP STATUS: {}", response.statusCode());
            if (response.statusCode() == 400 || response.statusCode() == 401
                    || response.statusCode() == 403) {
                log.debug("GEMINI FALLBACK: INVALID_API_KEY");
                return null;
            }
            if (response.statusCode() == 404) {
                log.debug("GEMINI FALLBACK: INVALID_MODEL");
                return null;
            }
            if (response.statusCode() == 429) {
                log.debug("GEMINI FALLBACK: RATE_LIMITED");
                return null;
            }
            if (response.statusCode() != 200) {
                log.debug("GEMINI FALLBACK: HTTP_{}", response.statusCode());
                return null;
            }
            byte[] body = response.body();
            if (body == null || body.length == 0) {
                log.debug("GEMINI FALLBACK: EMPTY_BODY");
                return null;
            }
            if (body.length > MAX_RESPONSE_BYTES) {
                log.debug("GEMINI FALLBACK: RESPONSE_TOO_LARGE");
                return null;
            }
            String text = extractText(new String(body, StandardCharsets.UTF_8));
            log.debug("GEMINI RESPONSE PARSED: {}", text != null);
            if (text == null) log.debug("GEMINI EMPTY CANDIDATES");
            return text;
        } catch (java.net.http.HttpTimeoutException e) {
            log.debug("GEMINI FALLBACK: TIMEOUT after {}s", timeoutSeconds);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("GEMINI FALLBACK: INTERRUPTED");
            return null;
        } catch (Exception e) {
            // Never surface a provider exception, its message, or its URL.
            log.debug("GEMINI FALLBACK: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    /** The model this client calls. Visible for diagnostics, never the key. */
    String model() {
        return model;
    }

    private String buildPayload(String systemInstruction, String userText) {
        try {
            Map<String, Object> systemPart = new LinkedHashMap<>();
            systemPart.put("text", systemInstruction);

            Map<String, Object> systemInstructionObj = new LinkedHashMap<>();
            systemInstructionObj.put("parts", java.util.List.of(systemPart));

            Map<String, Object> userPart = new LinkedHashMap<>();
            userPart.put("text", userText);

            Map<String, Object> content = new LinkedHashMap<>();
            content.put("role", "user");
            content.put("parts", java.util.List.of(userPart));

            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("temperature", 0.2);
            generationConfig.put("maxOutputTokens", 900);
            generationConfig.put("topP", 0.9);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("systemInstruction", systemInstructionObj);
            body.put("contents", java.util.List.of(content));
            body.put("generationConfig", generationConfig);

            String json = mapper.writeValueAsString(body);
            if (json.length() > MAX_REQUEST_CHARS) {
                log.debug("Gemini request exceeded the size cap; using local fallback");
                return null;
            }
            return json;
        } catch (Exception e) {
            log.debug("Could not build the Gemini request; using local fallback");
            return null;
        }
    }

    /** Pull the first text block out of the Gemini envelope. */
    private String extractText(String json) {
        try {
            return extractReplyText(mapper.readTree(json));
        } catch (Exception e) {
            log.debug("GEMINI FALLBACK: MALFORMED_RESPONSE");
            return null;
        }
    }

    /**
     * Pull usable assistant text out of a parsed {@code generateContent}
     * envelope. Package-visible for tests. Concatenates every non-blank text
     * part across candidates (never assuming exactly one candidate or one
     * part); returns {@code null} for empty candidates, missing content/parts,
     * missing text, safety blocks, or any non-text shape.
     */
    static String extractReplyText(JsonNode root) {
        if (root == null || root.isMissingNode()) return null;
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.isEmpty()) return null;
        for (JsonNode c : candidates) {
            if (c == null || !c.isObject()) continue;
            JsonNode parts = c.path("content").path("parts");
            if (!parts.isArray() || parts.isEmpty()) continue;
            StringBuilder sb = new StringBuilder();
            for (JsonNode p : parts) {
                if (p == null || !p.isObject()) continue;
                String t = p.path("text").asText(null);
                if (t != null && !t.isBlank()) sb.append(t);
            }
            if (sb.length() > 0) return sb.toString().trim();
        }
        return null;
    }
}
