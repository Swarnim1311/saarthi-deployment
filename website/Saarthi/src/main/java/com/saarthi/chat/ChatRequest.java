package com.saarthi.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request body for {@code POST /api/chat}.
 *
 * <p>Every field is optional except a usable {@code message}; {@code context} is
 * best-effort and every one of its fields may be absent, because the assistant must
 * work before a farmer has picked a block.
 *
 * <p><b>Strict message typing.</b> {@code message} is bound as a raw
 * {@link JsonNode} rather than a {@code String}, because Jackson would otherwise
 * silently coerce a number, boolean, array or object into text — so
 * {@code {"message": 123}} would be answered as if the farmer had typed
 * {@code "123"}. A question that is not a JSON string is not a question, and is
 * rejected with HTTP 400 instead. The same applies to a JSON {@code null} and to
 * an absent field.
 *
 * <p>Untrusted input only ever reaches the model as <em>data</em> inside a
 * delimited block in the user turn, never as instructions, and is never
 * interpolated into a shell, a path, or a template that gets evaluated.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatRequest {

    /** Longest accepted message. Longer input is rejected with HTTP 413. */
    public static final int MAX_MESSAGE_CHARS = 2000;

    /**
     * Bound by Jackson as the raw node so a wrong JSON type can be rejected.
     *
     * <p>{@code @JsonProperty} is explicit here to force <b>field</b> binding. Left
     * implicit, Jackson prefers the public {@code setMessage(String)} and coerces a
     * number, boolean, array or object into text.
     */
    @JsonProperty("message")
    private JsonNode messageNode;

    private Map<String, Object> context = new LinkedHashMap<>();

    /**
     * Optional farmer profile id. Top-level (preferred) or inside
     * {@code context.farmerId}; when present the stored crop/location fills any
     * blank context field and is attached to the SAARTHI context. Absent means
     * the request is answered exactly as before.
     */
    @JsonProperty("farmerId")
    private String farmerId;

    /** Why the request is unusable, or {@code null} when it is fine. */
    public enum MessageProblem {
        NONE(null),
        MISSING("A non-empty 'message' string is required"),
        NOT_A_STRING("'message' must be a JSON string"),
        BLANK("A non-empty 'message' string is required");

        private final String detail;

        MessageProblem(String detail) {
            this.detail = detail;
        }

        public String detail() {
            return detail;
        }
    }

    /** The message text when it is a JSON string, otherwise {@code null}. */
    public String getMessage() {
        return messageNode != null && messageNode.isTextual() ? messageNode.asText() : null;
    }

    /**
     * Used by tests and by any in-process caller.
     *
     * <p>Hidden from Jackson on purpose: if a public {@code String} setter were
     * visible, Jackson would prefer it and silently coerce {@code 123} into
     * {@code "123"}, defeating the strict typing above. The field is bound instead.
     */
    @JsonIgnore
    public void setMessage(String message) {
        this.messageNode = message == null ? null : TextNode.valueOf(message);
    }

    public String getFarmerId() {
        return farmerId;
    }

    public void setFarmerId(String farmerId) {
        this.farmerId = farmerId;
    }

    /**
     * The effective farmer id: top-level {@code farmerId} first, then
     * {@code context.farmerId} for older clients. Trimmed, or {@code null}.
     */
    public String effectiveFarmerId() {
        if (farmerId != null && !farmerId.isBlank()) return farmerId.trim();
        if (context != null) {
            Object v = context.get("farmerId");
            if (v != null) {
                String s = String.valueOf(v).trim();
                if (!s.isEmpty()) return s.length() > 64 ? s.substring(0, 64) : s;
            }
        }
        return null;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    /** The raw bound node; used only to distinguish JSON types. */
    JsonNode messageNode() {
        return messageNode;
    }

    /**
     * Why this request cannot be answered, or {@link MessageProblem#NONE}.
     *
     * <p>Order matters: a present-but-wrong-type value is reported as
     * {@code NOT_A_STRING} rather than as missing, so the client learns the actual
     * problem instead of a generic one.
     */
    public MessageProblem messageProblem() {
        if (messageNode == null || messageNode.isNull() || messageNode.isMissingNode()) {
            return MessageProblem.MISSING;
        }
        if (!messageNode.isTextual()) {
            return MessageProblem.NOT_A_STRING;
        }
        String text = messageNode.asText();
        if (text == null || text.trim().isEmpty()) {
            return MessageProblem.BLANK;
        }
        return MessageProblem.NONE;
    }

    /** True when a message is present, a string, and carries actual content. */
    public boolean hasMessage() {
        return messageProblem() == MessageProblem.NONE;
    }

    /** True when the message exceeds the accepted length. */
    public boolean isOversized() {
        String m = getMessage();
        return m != null && m.length() > MAX_MESSAGE_CHARS;
    }

    /** The trimmed message, or {@code null} when absent or blank. */
    public String trimmedMessage() {
        String m = getMessage();
        return m == null ? null : m.trim();
    }

    /**
     * A context value as a clean string, or {@code null} when it is absent, blank
     * or structurally unusable. Used for identity fields only.
     */
    public String contextString(String key) {
        if (context == null) return null;
        Object v = context.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        // Defensive: a client must not be able to smuggle prompt scaffolding
        // through a "location" field.
        if (s.length() > 120) s = s.substring(0, 120);
        return s.isEmpty() ? null : s.replaceAll("[\\r\\n\\t]", " ");
    }
}
