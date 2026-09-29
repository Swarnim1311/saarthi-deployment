package com.saarthi.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side Bhashini (ULCA) client, ported from the teammate's Node backend
 * ({@code SAARTHI-VOICE/backend/bhashini.js}) without its standalone server.
 *
 * <p>Same two operations: Indic TTS ({@code synthesize}) and ASR
 * ({@code transcribe}), with the same pipeline-discovery flow
 * ({@code getModelsPipeline} → per-task inference endpoint cached in memory).
 *
 * <p><b>Fail-soft by contract.</b> Unconfigured credentials, timeouts, HTTP
 * errors and malformed responses all yield {@code null} — never an exception
 * to callers and never provider internals. The controller turns {@code null}
 * into a structured fallback the browser can handle (browser speech
 * synthesis/recognition), so voice degradation never breaks text chat.
 *
 * <p><b>Credentials never leave the server.</b> The key travels only in the
 * outbound {@code ulcaApiKey} discovery header and the per-pipeline inference
 * auth header. Responses carry audio/text only.
 *
 * <p>Uses the JDK {@link HttpClient}, like {@code GeminiClient},
 * {@code OpenMeteoProvider} and {@code SoilGridsClient} — no new dependency.
 */
@Service
public class BhashiniService {

    private static final Logger log = LoggerFactory.getLogger(BhashiniService.class);

    /**
     * Voice-layer languages with Bhashini Indic coverage (ported from
     * {@code bhashini.js}; English keeps the browser speech fallback).
     * Telugu/Punjabi live here in the voice layer only — the visible UI
     * language switcher stays English + Hindi.
     */
    public static final Set<String> SUPPORTED_LANGS = Set.of("hi", "pa", "te");

    /** Hard cap on text sent for synthesis (bounds cost and latency). */
    static final int MAX_TEXT_CHARS = 2000;

    /** Hard cap on audio accepted for transcription (~10 MB of base64). */
    static final int MAX_AUDIO_CHARS = 13_500_000;

    private final BhashiniProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedPipeline> cache = new ConcurrentHashMap<>();

    @Autowired
    public BhashiniService(BhashiniProperties props) {
        this(props, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /** Test seam: explicit properties and client without Spring. */
    BhashiniService(BhashiniProperties props, HttpClient http) {
        this.props = props;
        this.http = http;
    }

    /** True when both Bhashini credentials are configured. */
    public boolean isConfigured() {
        return props.isConfigured();
    }

    /**
     * Synthesize speech. Returns base64 WAV audio, or {@code null} when
     * synthesis is unavailable (unconfigured, unsupported language, timeout,
     * provider error) — the caller must use the browser-voice fallback.
     */
    public String synthesize(String text, String language) {
        String clean = cleanText(text);
        if (clean == null) return null;
        String lang = normaliseLang(language);
        if (!SUPPORTED_LANGS.contains(lang)) return null;
        if (!props.isConfigured()) {
            log.debug("BHASHINI TTS unavailable: credentials not configured");
            return null;
        }
        try {
            Pipeline pipeline = pipelineFor("tts", lang);
            if (pipeline == null) return null;
            ObjectNode task = mapper.createObjectNode();
            task.put("taskType", "tts");
            ObjectNode config = task.putObject("config");
            config.putObject("language").put("sourceLanguage", lang);
            config.put("serviceId", pipeline.serviceId());
            config.put("gender", "female");
            ObjectNode body = mapper.createObjectNode();
            ArrayNode tasks = body.putArray("pipelineTasks");
            tasks.add(task);
            ObjectNode inputData = body.putObject("inputData");
            ObjectNode input = inputData.putArray("input").addObject();
            input.put("source", clean);
            JsonNode resp = postJson(pipeline.callbackUrl(), pipeline.authHeaderName(),
                    pipeline.authHeaderValue(), body);
            if (resp == null) return null;
            JsonNode audio = resp.path("pipelineResponse").path(0).path("audio").path(0)
                    .path("audioContent");
            if (!audio.isTextual() || audio.asText().isBlank()) {
                log.debug("BHASHINI TTS: no audio in response for {}", lang);
                return null;
            }
            return audio.asText();
        } catch (RuntimeException e) {
            log.debug("BHASHINI TTS failed ({}); using browser fallback", e.getMessage());
            return null;
        }
    }

    /**
     * Transcribe base64 WAV audio. Returns the recognized text (possibly empty
     * when the provider heard nothing), or {@code null} when transcription is
     * unavailable — the caller must report fail-soft, never a 500.
     */
    public String transcribe(String audioBase64, String language) {
        if (audioBase64 == null || audioBase64.isBlank()
                || audioBase64.length() > MAX_AUDIO_CHARS) {
            return null;
        }
        String lang = normaliseLang(language);
        if (!SUPPORTED_LANGS.contains(lang)) return null;
        if (!props.isConfigured()) {
            log.debug("BHASHINI ASR unavailable: credentials not configured");
            return null;
        }
        try {
            Pipeline pipeline = pipelineFor("asr", lang);
            if (pipeline == null) return null;
            ObjectNode task = mapper.createObjectNode();
            task.put("taskType", "asr");
            ObjectNode config = task.putObject("config");
            config.putObject("language").put("sourceLanguage", lang);
            config.put("serviceId", pipeline.serviceId());
            config.put("audioFormat", "wav");
            ObjectNode body = mapper.createObjectNode();
            ArrayNode tasks = body.putArray("pipelineTasks");
            tasks.add(task);
            ObjectNode inputData = body.putObject("inputData");
            ObjectNode audio = inputData.putArray("audio").addObject();
            audio.put("audioContent", audioBase64.trim());
            JsonNode resp = postJson(pipeline.callbackUrl(), pipeline.authHeaderName(),
                    pipeline.authHeaderValue(), body);
            if (resp == null) return null;
            JsonNode out = resp.path("pipelineResponse").path(0).path("output").path(0)
                    .path("source");
            return out.isTextual() ? out.asText() : "";
        } catch (RuntimeException e) {
            log.debug("BHASHINI ASR failed ({}); reporting unavailable", e.getMessage());
            return null;
        }
    }

    /** Normalize {@code hi-IN} / {@code HI} → {@code hi}; blank → {@code hi}. */
    public static String normaliseLang(String language) {
        if (language == null || language.isBlank()) return "hi";
        String code = language.trim().toLowerCase(Locale.ROOT).split("[_-]")[0];
        return code.isEmpty() ? "hi" : code;
    }

    // ---- pipeline discovery (cached) ----

    private Pipeline pipelineFor(String taskType, String lang) {
        String key = taskType + "_" + lang;
        CachedPipeline hit = cache.get(key);
        if (hit != null && !isExpired(hit)) return hit.pipeline();
        Pipeline fresh = fetchPipeline(taskType, lang);
        if (fresh != null) cache.put(key, new CachedPipeline(fresh, Instant.now()));
        return fresh;
    }

    private boolean isExpired(CachedPipeline c) {
        return Duration.between(c.cachedAt(), Instant.now()).toMinutes()
                >= props.cacheTtlMinutes();
    }

    private Pipeline fetchPipeline(String taskType, String lang) {
        try {
            ObjectNode task = mapper.createObjectNode();
            task.put("taskType", taskType);
            task.putObject("config").putObject("language").put("sourceLanguage", lang);
            ObjectNode body = mapper.createObjectNode();
            ArrayNode tasks = body.putArray("pipelineTasks");
            tasks.add(task);
            body.putObject("pipelineRequestConfig").put("pipelineId", props.pipelineId());
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.pipelineUrl()))
                    .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("ulcaApiKey", props.apiKey())
                    .header("userID", props.userId())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300 || resp.body() == null
                    || resp.body().isBlank()) {
                log.debug("BHASHINI pipeline discovery HTTP {}", resp.statusCode());
                return null;
            }
            JsonNode data = mapper.readTree(resp.body());
            JsonNode endpoint = data.path("pipelineInferenceAPIEndPoint");
            JsonNode taskConfig = data.path("pipelineResponseConfig").path(0)
                    .path("config").path(0);
            String callback = endpoint.path("callbackUrl").asText(null);
            String serviceId = taskConfig.path("serviceId").asText(null);
            if (callback == null || callback.isBlank() || serviceId == null
                    || serviceId.isBlank()) {
                log.debug("BHASHINI pipeline discovery: invalid response shape");
                return null;
            }
            String authName = endpoint.path("inferenceApiKey").path("name").asText(null);
            String authValue = endpoint.path("inferenceApiKey").path("value").asText(null);
            return new Pipeline(callback, (authName == null || authName.isBlank())
                    ? "Authorization" : authName, authValue, serviceId);
        } catch (Exception e) {
            // Network, timeout, malformed JSON: voice degrades, chat continues.
            log.debug("BHASHINI pipeline discovery failed ({}); voice unavailable",
                    e.getMessage());
            return null;
        }
    }

    private JsonNode postJson(String url, String authName, String authValue, ObjectNode body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if (authValue != null && !authValue.isBlank()) {
                builder.header(authName == null || authName.isBlank()
                        ? "Authorization" : authName, authValue);
            }
            HttpResponse<String> resp =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300 || resp.body() == null
                    || resp.body().isBlank()) {
                log.debug("BHASHINI compute HTTP {}", resp.statusCode());
                return null;
            }
            return mapper.readTree(resp.body());
        } catch (Exception e) {
            log.debug("BHASHINI compute failed ({}); voice unavailable", e.getMessage());
            return null;
        }
    }

    private static String cleanText(String text) {
        if (text == null) return null;
        String s = text.trim();
        if (s.isEmpty() || s.length() > MAX_TEXT_CHARS) return null;
        return s;
    }

    private record Pipeline(String callbackUrl, String authHeaderName,
            String authHeaderValue, String serviceId) {
    }

    private record CachedPipeline(Pipeline pipeline, Instant cachedAt) {
    }
}
