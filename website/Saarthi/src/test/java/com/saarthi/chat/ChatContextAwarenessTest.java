package com.saarthi.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Follow-up coverage for three behaviours:
 *
 * <ol>
 *   <li>the local fallback now quotes the SAARTHI context it is given;</li>
 *   <li>Climate Intelligence sector verdicts reach the assistant;</li>
 *   <li>a non-string {@code message} is rejected with HTTP 400.</li>
 * </ol>
 *
 * <p>No network and no Spring context. Gemini is disabled throughout, so every
 * assertion here is about the deterministic local path.
 */
class ChatContextAwarenessTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------
    // context fixtures
    // ------------------------------------------------------------------

    private static Map<String, Object> forecast(Object... kv) {
        Map<String, Object> f = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) f.put(String.valueOf(kv[i]), kv[i + 1]);
        return f;
    }

    private static Map<String, Object> sector(String state, String... reasons) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("state", state);
        s.put("available", !"UNAVAILABLE".equals(state));
        if (reasons.length > 0) s.put("reasons", new ArrayList<>(List.of(reasons)));
        s.put("validationNote", "Rule-based indicator; not a calibrated probability.");
        return s;
    }

    private static Map<String, Object> ctx(Map<String, Object> forecast,
            Map<String, Object> crop, Map<String, Object> sectors) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("block", "Andana");
        loc.put("state", "Punjab");
        loc.put("district", "Sangrur");
        m.put("location", loc);
        m.put("crop", crop == null ? new LinkedHashMap<String, Object>() : crop);
        m.put("forecast", forecast == null ? new LinkedHashMap<String, Object>() : forecast);
        m.put("sectors", sectors == null ? new LinkedHashMap<String, Object>() : sectors);
        m.put("unavailable", List.of("elevation"));
        m.put("available", true);
        return m;
    }

    private static Map<String, Object> cropOf(String name) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("crop", name);
        c.putAll(new LocalAdvisoryCorpus().cropKnowledge(name));
        return c;
    }

    private static ChatbotService service(Map<String, Object> context) {
        return new ChatbotService(
                new SaarthiChatContext(null, null, null, null, null, null, null) {
                    @Override
                    public Map<String, Object> build(String s, String d, String b, String cr) {
                        return context;
                    }
                },
                new LocalAdvisoryCorpus(),
                new GeminiClient(new GeminiKeyResolver(Map.of(), null), 1) {
                    @Override public boolean isConfigured() { return false; }
                },
                new GeminiKeyResolver(Map.of(), null));
    }

    private static ChatRequest req(String message) {
        ChatRequest r = new ChatRequest();
        r.setMessage(message);
        return r;
    }

    // ==================================================================
    // 1. LOCAL FALLBACK USES ACTUAL CONTEXT
    // ==================================================================

    @Test
    void heavyRainQuotesTheRealRainfallValue() {
        Map<String, Object> c = ctx(
                forecast("rain3dMm", 6.4, "rain7dMm", 12.8, "horizonDays", 16), null, null);
        String reply = service(c).answer(req("What should I do if heavy rain is forecast?")).reply();
        assertTrue(reply.contains("6.4 mm"), "must quote the real 3-day value: " + reply);
        assertTrue(reply.contains("12.8 mm"), "must quote the real 7-day value: " + reply);
        assertTrue(reply.contains("Andana"), "must name the selected block: " + reply);
        assertTrue(reply.contains("Forecasts can change"),
                "must still warn that forecasts change: " + reply);
    }

    @Test
    void heavyRainSaysSoWhenRainfallIsUnavailable() {
        Map<String, Object> c = ctx(forecast("rain7dMm", 4.0), null, null); // no rain3dMm
        String reply = service(c).answer(req("What should I do if heavy rain is forecast?")).reply();
        assertTrue(reply.contains("can't access a reliable rainfall value"),
                "must admit the value is missing: " + reply);
        assertFalse(reply.contains(" mm of rainfall over the next 3 days"),
                "must not invent a 3-day figure");
    }

    @Test
    void fieldWorkReportsTheHighSectorStateAndReason() {
        Map<String, Object> sectors = new LinkedHashMap<>();
        sectors.put("agriculture", sector("HIGH", "Wet days expected in D+1..D+3."));
        String reply = service(ctx(forecast(), null, sectors))
                .answer(req("Is this a good time for field work?")).reply();
        assertTrue(reply.contains("HIGH"), "must quote the engine's own level: " + reply);
        assertTrue(reply.contains("Wet days expected"), "must preserve the reason: " + reply);
        assertTrue(reply.contains("rule-based indicator"), "must not imply certainty");
    }

    @Test
    void fieldWorkReportsModerateAndLowFaithfully() {
        Map<String, Object> mod = new LinkedHashMap<>();
        mod.put("agriculture", sector("MODERATE", "Some wet days in range."));
        assertTrue(service(ctx(forecast(), null, mod))
                .answer(req("Is this a good time for field work?")).reply().contains("MODERATE"));

        Map<String, Object> low = new LinkedHashMap<>();
        low.put("agriculture", sector("LOW"));
        String r = service(ctx(forecast(), null, low))
                .answer(req("Is this a good time for field work?")).reply();
        assertTrue(r.contains("LOW"), "must quote LOW: " + r);
        assertFalse(r.contains("HIGH"));
    }

    @Test
    void fieldWorkSaysUnavailableWhenTheEngineHasNoVerdict() {
        Map<String, Object> sectors = new LinkedHashMap<>();
        sectors.put("agriculture", sector("UNAVAILABLE"));
        String reply = service(ctx(forecast(), null, sectors))
                .answer(req("Is this a good time for field work?")).reply();
        assertTrue(reply.contains("doesn't currently have enough forecast information"),
                "must not invent a level: " + reply);
        assertFalse(reply.contains(" as HIGH"));
        assertFalse(reply.contains(" as LOW"));
    }

    @Test
    void forecastExplanationUsesTheActualValues() {
        Map<String, Object> c = ctx(forecast("rain3dMm", 6.4, "rain7dMm", 12.8,
                "maxTempC", 32.2, "minTempC", 22.3, "horizonDays", 16), null, null);
        String reply = service(c).answer(req("Explain my forecast simply")).reply();
        assertTrue(reply.contains("16-day forecast"), "must state the horizon: " + reply);
        assertTrue(reply.contains("6.4 mm"), "must state near-term rain: " + reply);
        assertTrue(reply.contains("32.2") && reply.contains("22.3"),
                "must state temperature: " + reply);
        assertTrue(reply.contains("not a measurement"),
                "must not present a forecast as an observation: " + reply);
    }

    @Test
    void aStaleForecastIsFlaggedInTheExplanation() {
        Map<String, Object> c = ctx(forecast("rain7dMm", 1.0, "stale", true), null, null);
        assertTrue(service(c).answer(req("Explain my forecast")).reply().contains("stale"));
    }

    @Test
    void sowingIsCropAwareAndDoesNotClaimSafety() {
        String reply = service(ctx(forecast("rain7dMm", 8.0), cropOf("Wheat (HD-2967)"), null))
                .answer(req("What should I consider before sowing?")).reply();
        assertTrue(reply.contains("Wheat (HD-2967)"), "must name the crop: " + reply);
        assertTrue(reply.contains("rabi"), "must use the reference season: " + reply);
        assertTrue(reply.contains("8.0 mm"), "must add the real rainfall: " + reply);
        assertTrue(reply.contains("not on its own a reason to sow"),
                "a calendar alone must not be presented as a go decision: " + reply);
    }

    @Test
    void sowingWithoutACropSaysSo() {
        String reply = service(ctx(forecast(), null, null))
                .answer(req("What should I consider before sowing?")).reply();
        assertTrue(reply.contains("No crop is selected"), "got: " + reply);
    }

    @Test
    void soilMoistureIsAlwaysCalledModelled() {
        String with = service(ctx(forecast("soilMoisture0to7cmForecast", 0.18), null, null))
                .answer(req("What about soil?")).reply();
        assertTrue(with.contains("modelled soil moisture"),
                "must call it modelled: " + with);
        assertTrue(with.contains("not a measurement from your field"), "got: " + with);
        assertFalse(with.toLowerCase().contains("measured in your field"));

        String without = service(ctx(forecast(), null, null))
                .answer(req("What about soil?")).reply();
        assertTrue(without.contains("not available"), "must admit unavailability: " + without);
    }

    @Test
    void missingValuesRemainMissingAndAreNeverZeroFilled() {
        // An empty forecast: no number may appear at all.
        String reply = service(ctx(forecast(), cropOf("Maize (PMH-1)"), null))
                .answer(req("What should I do if heavy rain is forecast?")).reply();
        assertFalse(reply.contains("0.0 mm"), "no fabricated 0.0 mm: " + reply);
        assertTrue(reply.contains("can't access a reliable rainfall value"));
    }

    @Test
    void irrigationNeverInventsAQuantity() {
        String reply = service(ctx(forecast("rain7dMm", 20.0), cropOf("Wheat (HD-2967)"), null))
                .answer(req("How do I decide when to irrigate?")).reply();
        assertTrue(reply.contains("don't calculate an irrigation quantity"),
                "must refuse to compute a quantity: " + reply);
        assertTrue(reply.contains("20.0 mm"), "must use the real 7-day rain: " + reply);
        assertTrue(reply.contains("high"), "must use the real water-need class: " + reply);
    }

    @Test
    void irrigationWithNoRainfallAndNoCropSaysItCannotAdvise() {
        String reply = service(ctx(forecast(), null, null))
                .answer(req("How do I decide when to irrigate?")).reply();
        assertTrue(reply.contains("don't have enough rainfall and crop information"),
                "got: " + reply);
    }

    @Test
    void growthStagesComeFromTheReference() {
        String reply = service(ctx(forecast(), cropOf("Paddy (PR-126)"), null))
                .answer(req("Tell me about crop growth stages")).reply();
        assertTrue(reply.contains("panicle"), "must use the reference stage list: " + reply);
        assertTrue(reply.contains("walking the field"),
                "must not claim the actual stage is known: " + reply);
    }

    // ==================================================================
    // 2. CLIMATE INTELLIGENCE CONTEXT
    // ==================================================================

    @Test
    void agricultureSectorQuestionIsAnsweredFromTheEngine() {
        Map<String, Object> sectors = new LinkedHashMap<>();
        sectors.put("agriculture", sector("HIGH", "Wet days expected in D+1..D+3."));
        String reply = service(ctx(forecast(), null, sectors))
                .answer(req("What is the agricultural risk?")).reply();
        assertTrue(reply.contains("Agriculture"), "must name the sector: " + reply);
        assertTrue(reply.contains("HIGH"), "must use the engine's level: " + reply);
        assertTrue(reply.contains("existing risk engine reports"),
                "must attribute the verdict: " + reply);
    }

    @Test
    void logisticsSectorQuestionIsAnswered() {
        Map<String, Object> sectors = new LinkedHashMap<>();
        sectors.put("logistics", sector("MODERATE", "Wet spell may affect roads."));
        String reply = service(ctx(forecast(), null, sectors))
                .answer(req("Could rain affect logistics?")).reply();
        assertTrue(reply.contains("Logistics") && reply.contains("MODERATE"), "got: " + reply);
        assertTrue(reply.contains("Wet spell may affect roads"), "got: " + reply);
    }

    @Test
    void warehouseSectorQuestionIsAnswered() {
        Map<String, Object> sectors = new LinkedHashMap<>();
        sectors.put("warehouse", sector("LOW", "No significant moisture exposure."));
        String reply = service(ctx(forecast(), null, sectors))
                .answer(req("What does the warehouse risk mean?")).reply();
        assertTrue(reply.contains("Warehouse") && reply.contains("LOW"), "got: " + reply);
    }

    @Test
    void energySectorQuestionIsAnswered() {
        Map<String, Object> sectors = new LinkedHashMap<>();
        sectors.put("energy_groundwater", sector("HIGH", "Irrigation pressure elevated."));
        String reply = service(ctx(forecast(), null, sectors))
                .answer(req("Why is energy risk elevated?")).reply();
        assertTrue(reply.contains("Energy / Groundwater"), "got: " + reply);
        assertTrue(reply.contains("HIGH"), "got: " + reply);
        assertTrue(reply.contains("Irrigation pressure elevated"), "got: " + reply);
    }

    @Test
    void theSectorsBlockCarriesStateReasonsAndValidationNote() {
        Map<String, Object> sectors = new LinkedHashMap<>();
        sectors.put("agriculture", sector("HIGH", "Because rain."));
        Map<String, Object> c = ctx(forecast(), null, sectors);
        ChatResponse r = service(c).answer(req("What is the agricultural risk?"));
        @SuppressWarnings("unchecked")
        Map<String, Object> used = (Map<String, Object>) r.contextUsed().get("sectors");
        assertNotNull(used, "the sectors block must be echoed to the client");
        @SuppressWarnings("unchecked")
        Map<String, Object> ag = (Map<String, Object>) used.get("agriculture");
        assertEquals("HIGH", ag.get("state"));
        assertNotNull(ag.get("reasons"));
        assertNotNull(ag.get("validationNote"),
                "the engine's own validation note must travel with the verdict");
    }

    @Test
    void anUnavailableSectorIsHandledSafelyAndNotQuotedAsALevel() {
        Map<String, Object> sectors = new LinkedHashMap<>();
        sectors.put("logistics", sector("UNAVAILABLE"));
        String reply = service(ctx(forecast(), null, sectors))
                .answer(req("What is the logistics risk?")).reply();
        assertTrue(reply.contains("does not currently have enough information"),
                "must not state a level it does not have: " + reply);
        assertFalse(reply.contains("marks Logistics as"), "got: " + reply);
    }

    @Test
    void noSectorContextMeansAnHonestRefusalNotAGuess() {
        String reply = service(ctx(forecast(), null, null))
                .answer(req("What is the agricultural risk?")).reply();
        assertTrue(reply.contains("does not currently have enough information"), "got: " + reply);
    }

    @Test
    void hindiSectorAnswerIsInHindiAndKeepsTheLevel() {
        Map<String, Object> sectors = new LinkedHashMap<>();
        sectors.put("agriculture", sector("HIGH", "Wet days expected."));
        ChatRequest r = req("कृषि जोखिम क्या है?");
        r.setContext(Map.of("language", "hi"));
        String reply = service(ctx(forecast(), null, sectors)).answer(r).reply();
        assertTrue(reply.contains("HIGH"), "the level must survive: " + reply);
        assertTrue(reply.matches("(?s).*\\p{IsDevanagari}+.*"),
                "must be answered in Hindi: " + reply);
    }

    @Test
    void theLocalFallbackStaysDeterministicWithContext() {
        ChatbotService s = service(ctx(forecast("rain3dMm", 6.4), null, null));
        String q = "What should I do if heavy rain is forecast?";
        assertEquals(s.answer(req(q)).reply(), s.answer(req(q)).reply());
        assertEquals(s.answer(req(q)).reply(), s.answer(req(q)).reply());
    }

    // ==================================================================
    // 3. MESSAGE VALIDATION
    // ==================================================================

    private static ChatRequest parse(String json) throws Exception {
        return JSON.readValue(json, ChatRequest.class);
    }

    private static void assertRejected(String json, String label) throws Exception {
        ChatRequest r = parse(json);
        ChatbotService s = service(ctx(forecast(), null, null));
        assertThrows(IllegalArgumentException.class, () -> s.answer(r), label);
    }

    @Test
    void numericMessageIsRejected() throws Exception {
        assertRejected("{\"message\":123}", "a number must not be coerced to text");
    }

    @Test
    void booleanMessageIsRejected() throws Exception {
        assertRejected("{\"message\":true}", "a boolean is not a question");
    }

    @Test
    void objectMessageIsRejected() throws Exception {
        assertRejected("{\"message\":{\"text\":\"hi\"}}", "an object is not a question");
    }

    @Test
    void arrayMessageIsRejected() throws Exception {
        assertRejected("{\"message\":[\"hi\"]}", "an array is not a question");
    }

    @Test
    void nullMessageIsRejected() throws Exception {
        assertRejected("{\"message\":null}", "JSON null is not a question");
    }

    @Test
    void missingMessageIsRejected() throws Exception {
        assertRejected("{}", "an absent field is not a question");
    }

    @Test
    void blankAndWhitespaceMessagesAreRejected() throws Exception {
        assertRejected("{\"message\":\"\"}", "empty string");
        assertRejected("{\"message\":\"   \"}", "whitespace only");
        assertRejected("{\"message\":\"\\n\\t  \\n\"}", "whitespace and newlines");
    }

    @Test
    void aRealStringMessageIsStillAccepted() throws Exception {
        ChatResponse r = service(ctx(forecast(), null, null))
                .answer(parse("{\"message\":\"What should I consider before sowing?\"}"));
        assertNotNull(r.reply());
        assertFalse(r.reply().isBlank());
    }

    @Test
    void theControllerReturns400ForEveryRejectedShape() throws Exception {
        ChatController c = new ChatController(service(ctx(forecast(), null, null)));
        for (String bad : List.of("{\"message\":123}", "{\"message\":true}",
                "{\"message\":{\"a\":1}}", "{\"message\":[\"x\"]}", "{\"message\":null}",
                "{}", "{\"message\":\"\"}")) {
            // Invoking the handler method directly bypasses Spring's dispatch, so
            // the rejection is raised and then mapped exactly as the MVC layer does.
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> c.chat(JSON.readValue(bad, ChatRequest.class)),
                    "should reject " + bad);
            ResponseEntity<Map<String, Object>> mapped = c.badRequest(thrown);
            assertEquals(400, mapped.getStatusCode().value(), "should be 400 for " + bad);
            assertEquals("bad_request", mapped.getBody().get("error"));
        }
    }

    @Test
    void theRejectionMessageDoesNotEchoTheOffendingPayload() throws Exception {
        ChatRequest r = parse("{\"message\":\"a-very-long-secret-string-value\"}");
        ChatbotService s = service(ctx(forecast(), null, null));
        // Oversized is reported by length only, never by echoing content.
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < ChatRequest.MAX_MESSAGE_CHARS + 50; i++) huge.append('z');
        ChatRequest big = parse("{\"message\":\"" + huge + "\"}");
        ChatbotService.ChatMessageTooLargeException ex = assertThrows(
                ChatbotService.ChatMessageTooLargeException.class, () -> s.answer(big));
        assertFalse(ex.getMessage().contains("z"), "the payload must not be echoed");
        assertNotNull(r);
    }
}
