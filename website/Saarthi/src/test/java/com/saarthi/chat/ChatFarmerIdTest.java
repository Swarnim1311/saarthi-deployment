package com.saarthi.chat;

import com.saarthi.farmer.FarmerService;
import com.saarthi.farmer.FarmerRepository;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code POST /api/chat} farmer support: an optional {@code farmerId} fills
 * blank context fields from the stored profile; without it (or with an
 * unknown id) the request is answered exactly as before. Gemini stays
 * unconfigured here so the deterministic local fallback answers.
 */
class ChatFarmerIdTest {

    /** Captures the effective context inputs the service resolved. */
    private static final class CapturingContext extends SaarthiChatContext {
        String state;
        String district;
        String block;
        String crop;

        CapturingContext() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public Map<String, Object> build(String state, String district, String block,
                String crop) {
            this.state = state;
            this.district = district;
            this.block = block;
            this.crop = crop;
            Map<String, Object> m = new LinkedHashMap<>();
            Map<String, Object> loc = new LinkedHashMap<>();
            if (block != null) loc.put("block", block);
            m.put("location", loc);
            Map<String, Object> cropMap = new LinkedHashMap<>();
            if (crop != null) {
                cropMap.put("crop", crop);
                cropMap.putAll(new LocalAdvisoryCorpus().cropKnowledge(crop));
            }
            m.put("crop", cropMap);
            m.put("forecast", new LinkedHashMap<String, Object>());
            m.put("unavailable", List.of());
            m.put("available", true);
            return m;
        }
    }

    private static final class StubGemini extends GeminiClient {
        StubGemini() {
            super(new GeminiKeyResolver(Map.of(), null), 1);
        }

        @Override public boolean isConfigured() { return false; }

        @Override public String ask(String system, String user) { return null; }
    }

    private static ChatbotService service(CapturingContext ctx) {
        ChatbotService s = new ChatbotService(ctx, new LocalAdvisoryCorpus(),
                new StubGemini(), new GeminiKeyResolver(Map.of(), null));
        s.setFarmerService(new FarmerService(new FarmerRepository()));
        return s;
    }

    private static ChatRequest request(String message) {
        ChatRequest r = new ChatRequest();
        r.setMessage(message);
        r.setContext(new LinkedHashMap<>());
        return r;
    }

    @Test
    void farmerCropFillsBlankContext() {
        CapturingContext ctx = new CapturingContext();
        ChatRequest r = request("What should I consider before sowing?");
        r.setFarmerId("F001");
        ChatResponse resp = service(ctx).answer(r);
        assertEquals("Rice", ctx.crop, "stored crop fills the blank context field");
        assertEquals("Amritsar", ctx.block, "stored location fills the blank block");
        assertNotNull(resp.reply());
        assertTrue(resp.reply().contains("Rice"),
                "sowing advice should use the farmer's crop, got: " + resp.reply());
        assertEquals(ChatResponse.MODE_LOCAL, resp.mode());
    }

    @Test
    void explicitContextWinsOverFarmer() {
        CapturingContext ctx = new CapturingContext();
        ChatRequest r = request("What should I consider before sowing?");
        r.setFarmerId("F001");
        r.getContext().put("crop", "Wheat");
        service(ctx).answer(r);
        assertEquals("Wheat", ctx.crop, "explicit context must win over the profile");
    }

    @Test
    void noFarmerIdIsUnchanged() {
        CapturingContext ctx = new CapturingContext();
        ChatResponse resp = service(ctx)
                .answer(request("What should I consider before sowing?"));
        assertNull(ctx.crop);
        assertNotNull(resp.reply());
        assertTrue(resp.reply().contains("No crop is selected"),
                "anonymous sowing advice is unchanged, got: " + resp.reply());
    }

    @Test
    void unknownFarmerIdDegradesToAnonymous() {
        CapturingContext ctx = new CapturingContext();
        ChatRequest r = request("What should I consider before sowing?");
        r.setFarmerId("GHOST");
        ChatResponse resp = service(ctx).answer(r);
        assertNull(ctx.crop, "unknown farmer must not invent context");
        assertNotNull(resp.reply());
        assertEquals(ChatResponse.MODE_LOCAL, resp.mode());
    }

    @Test
    void farmerBlockAppearsInContextUsed() {
        CapturingContext ctx = new CapturingContext();
        ChatRequest r = request("Explain my forecast simply");
        r.setFarmerId("f002");
        ChatResponse resp = service(ctx).answer(r);
        Object farmer = resp.contextUsed().get("farmer");
        assertTrue(farmer instanceof Map<?, ?>, "contextUsed should echo the farmer block");
        assertEquals("F002", ((Map<?, ?>) farmer).get("farmerId"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) farmer).get("contextUsed"));
    }

    @Test
    void anonymousResponseCarriesNoFarmerBlock() {
        CapturingContext ctx = new CapturingContext();
        ChatResponse resp = service(ctx).answer(request("Explain my forecast simply"));
        assertTrue(!resp.contextUsed().containsKey("farmer"),
                "anonymous answers must not gain a farmer block");
    }
}
