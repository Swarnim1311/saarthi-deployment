package com.saarthi.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SAARTHI Agri-Advisor chat endpoint.
 *
 * <p>{@code POST /api/chat}. One endpoint, two possible modes. The response
 * always carries {@code reply} and {@code mode}, so the client can tell the
 * farmer whether the answer came from Gemini or from the local reference
 * corpus — it is never left ambiguous.
 *
 * <p><b>What this endpoint does not do.</b> It does not accept or return an API
 * key, it does not proxy arbitrary URLs, and it does not surface a provider
 * error. A Gemini failure is not a client error: the request still succeeds with
 * a local-fallback answer, because a working advisory is more useful than a 5xx.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatbotService chatbot;

    public ChatController(ChatbotService chatbot) {
        this.chatbot = chatbot;
    }

    /** Answer one agricultural question, in the requested language. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(@RequestBody(required = false) ChatRequest request) {
        ChatResponse response = chatbot.answer(request);
        return ResponseEntity.ok(response.toMap());
    }

    // ---- errors: never a stack trace, never provider internals ----

    @ExceptionHandler(ChatbotService.ChatMessageTooLargeException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(
            ChatbotService.ChatMessageTooLargeException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "message_too_long");
        body.put("message", "Please keep your question under "
                + ChatRequest.MAX_MESSAGE_CHARS + " characters.");
        body.put("maxChars", ChatRequest.MAX_MESSAGE_CHARS);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "bad_request");
        body.put("message", ex.getMessage() == null ? "Invalid request." : ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Malformed JSON is reported as a clean 400. The parser's own message can
     * quote the offending payload, so it is deliberately not echoed.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "malformed_request");
        body.put("message", "Request body must be JSON of the form {\"message\": \"...\"}.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
