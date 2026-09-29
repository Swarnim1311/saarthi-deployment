package com.saarthi.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gemini response-envelope parsing and client configuration.
 *
 * <p>No network: every case parses a canned {@code generateContent} envelope
 * through the same code the live path uses.
 */
class GeminiClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String parse(String json) throws Exception {
        return GeminiClient.extractReplyText(MAPPER.readTree(json));
    }

    @Test
    void aSingleCandidateWithASinglePartParses() throws Exception {
        String json = """
                {"candidates": [{"content": {"parts": [{"text": "Hello, farmer."}]},
                  "finishReason": "STOP"}]}""";
        assertEquals("Hello, farmer.", parse(json));
    }

    @Test
    void multiplePartsAreConcatenated() throws Exception {
        String json = """
                {"candidates": [{"content": {"parts": [
                  {"text": "First. "}, {"text": "Second."}]}}]}""";
        assertEquals("First. Second.", parse(json));
    }

    @Test
    void multipleCandidatesFallThroughToTheFirstUsableOne() throws Exception {
        String json = """
                {"candidates": [
                  {"content": {"parts": []}, "finishReason": "SAFETY"},
                  {"content": {"parts": [{"text": "Safe answer."}]}}]}""";
        assertEquals("Safe answer.", parse(json));
    }

    @Test
    void emptyCandidatesYieldNull() throws Exception {
        assertNull(parse("{\"candidates\": []}"));
    }

    @Test
    void missingContentOrPartsYieldNull() throws Exception {
        assertNull(parse("{\"candidates\": [{}]}"));
        assertNull(parse("{\"candidates\": [{\"content\": {}}]}"));
        assertNull(parse("{\"candidates\": [{\"content\": {\"parts\": []}}]}"));
    }

    @Test
    void missingOrBlankTextYieldsNull() throws Exception {
        assertNull(parse("{\"candidates\": [{\"content\": {\"parts\": [{}]}}]}"));
        assertNull(parse(
                "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"   \"}]}}]}"));
    }

    @Test
    void aSafetyBlockWithNoTextYieldsNull() throws Exception {
        assertNull(parse("""
                {"promptFeedback": {"blockReason": "SAFETY"},
                 "candidates": [{"finishReason": "SAFETY"}]}"""));
    }

    @Test
    void malformedEnvelopesYieldNull() throws Exception {
        assertNull(GeminiClient.extractReplyText(MAPPER.readTree("{}")));
        assertNull(GeminiClient.extractReplyText(MAPPER.readTree(
                "{\"candidates\": \"not-an-array\"}")));
        assertNull(GeminiClient.extractReplyText(null));
    }

    @Test
    void nonTextPartsAreSkippedButTextSurvives() throws Exception {
        String json = """
                {"candidates": [{"content": {"parts": [
                  {"inlineData": {"mimeType": "image/png", "data": "AAA"}},
                  {"text": "Still text."}]}}]}""";
        assertEquals("Still text.", parse(json));
    }

    @Test
    void theDefaultModelIsNotTheRetiredOne() {
        GeminiClient client = new GeminiClient(new GeminiKeyResolver(Map.of(), null), 1);
        assertEquals(GeminiClient.DEFAULT_MODEL, client.model());
        assertFalse(client.model().startsWith("gemini-1.5"),
                "gemini-1.5 models are shut down and always answer 404");
        assertTrue(client.model().contains("flash"));
    }
}
