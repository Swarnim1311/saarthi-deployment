package com.saarthi.voice;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-side voice endpoints backed by Bhashini (ULCA).
 *
 * <ul>
 *   <li>{@code POST /api/voice/transcribe} — Bhashini ASR: base64 WAV audio in,
 *       recognized text out.</li>
 *   <li>{@code POST /api/voice/speak} — Bhashini Indic TTS: text in, base64 WAV
 *       audio out.</li>
 * </ul>
 *
 * <p><b>Fail-soft, never 500 for provider problems.</b> Missing credentials,
 * unsupported languages, timeouts and provider errors return HTTP 200 with
 * {@code available:false} (plus a {@code fallback} hint), so the browser
 * Web Speech path takes over and text chat is never affected. Only malformed
 * client input (missing/oversized audio or text) is a 4xx.
 *
 * <p><b>No credentials leave the server.</b> Responses carry audio/text and
 * status only — the same guarantee as {@code POST /api/chat}.
 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final BhashiniService bhashini;

    public VoiceController(BhashiniService bhashini) {
        this.bhashini = bhashini;
    }

    /** Transcribe base64 WAV audio with Bhashini ASR (hi/pa/te voice layer). */
    @PostMapping("/transcribe")
    public ResponseEntity<Map<String, Object>> transcribe(
            @RequestBody(required = false) Map<String, Object> body) {
        String audio = stringField(body, "audio");
        String language = stringField(body, "language");
        if (language == null) language = "hi-IN";
        if (audio == null || audio.isBlank()) {
            return badRequest("audio_required",
                    "Audio data (base64) is required.");
        }
        if (audio.length() > BhashiniService.MAX_AUDIO_CHARS) {
            return ResponseEntity.status(413).body(Map.of(
                    "error", "audio_too_large",
                    "message", "Audio payload exceeds the accepted size.",
                    "maxChars", BhashiniService.MAX_AUDIO_CHARS));
        }
        String lang = BhashiniService.normaliseLang(language);
        if (!BhashiniService.SUPPORTED_LANGS.contains(lang)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("text", "");
            out.put("language", language);
            out.put("available", false);
            out.put("source", "bhashini");
            out.put("fallback", "browser");
            out.put("message", "Bhashini transcription covers Hindi, Punjabi and "
                    + "Telugu; use browser speech recognition for other languages.");
            return ResponseEntity.ok(out);
        }
        String text = bhashini.transcribe(audio, lang);
        Map<String, Object> out = new LinkedHashMap<>();
        if (text == null) {
            out.put("text", "");
            out.put("language", language);
            out.put("available", false);
            out.put("source", "bhashini");
            out.put("fallback", "browser");
            out.put("message", "Voice transcription is currently unavailable. "
                    + "Please type your question instead.");
            return ResponseEntity.ok(out);
        }
        out.put("text", text);
        out.put("language", language);
        out.put("available", true);
        out.put("source", "bhashini");
        return ResponseEntity.ok(out);
    }

    /** Synthesize speech with Bhashini Indic TTS (hi/pa/te voice layer). */
    @PostMapping("/speak")
    public ResponseEntity<Map<String, Object>> speak(
            @RequestBody(required = false) Map<String, Object> body) {
        String text = stringField(body, "text");
        String language = stringField(body, "language");
        if (language == null) language = "hi-IN";
        if (text == null || text.isBlank()) {
            return badRequest("text_required", "Text is required.");
        }
        if (text.length() > BhashiniService.MAX_TEXT_CHARS) {
            return ResponseEntity.status(413).body(Map.of(
                    "error", "text_too_long",
                    "message", "Text exceeds the accepted length for speech.",
                    "maxChars", BhashiniService.MAX_TEXT_CHARS));
        }
        String lang = BhashiniService.normaliseLang(language);
        if (!BhashiniService.SUPPORTED_LANGS.contains(lang)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("audio", null);
            out.put("text", text);
            out.put("language", language);
            out.put("available", false);
            out.put("source", "bhashini");
            out.put("fallback", "browser");
            out.put("message", "Using browser voice for this language.");
            return ResponseEntity.ok(out);
        }
        String audio = bhashini.synthesize(text, lang);
        Map<String, Object> out = new LinkedHashMap<>();
        if (audio == null) {
            out.put("audio", null);
            out.put("text", text);
            out.put("language", language);
            out.put("available", false);
            out.put("source", "bhashini");
            out.put("fallback", "browser");
            out.put("message", "Server voice is currently unavailable. "
                    + "Using browser voice instead.");
            return ResponseEntity.ok(out);
        }
        out.put("audio", audio);
        out.put("text", text);
        out.put("language", language);
        out.put("available", true);
        out.put("source", "bhashini");
        out.put("contentType", "audio/wav");
        return ResponseEntity.ok(out);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String error,
            String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", error);
        out.put("message", message);
        return ResponseEntity.badRequest().body(out);
    }

    private static String stringField(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        if (!(v instanceof String s)) return null;
        String t = s.trim();
        return t.isEmpty() ? null : s;
    }
}
