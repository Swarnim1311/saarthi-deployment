package com.saarthi.voice;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Voice endpoint contract: Bhashini provider problems are fail-soft
 * (HTTP 200 + {@code available:false} + browser fallback), never a 500.
 * Only malformed client input is a 4xx. No Spring context, no network: the
 * service runs unconfigured, so every provider call degrades locally.
 */
class VoiceControllerTest {

    private static MockMvc mvc() {
        BhashiniProperties props =
                new BhashiniProperties("", "", "64392f96daac500b55c543cd",
                        "http://127.0.0.1:1/unreachable", 1, 60);
        BhashiniService service = new BhashiniService(props,
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(java.time.Duration.ofSeconds(1))
                        .build());
        return MockMvcBuilders.standaloneSetup(new VoiceController(service)).build();
    }

    @Test
    void transcribeWithoutCredentialsIsFailSoft() throws Exception {
        mvc().perform(post("/api/voice/transcribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audio\":\"dGVzdA==\",\"language\":\"hi-IN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.fallback").value("browser"))
                .andExpect(jsonPath("$.text").value(""));
    }

    @Test
    void speakWithoutCredentialsIsFailSoft() throws Exception {
        mvc().perform(post("/api/voice/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Namaste\",\"language\":\"hi-IN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.fallback").value("browser"));
    }

    @Test
    void englishFallsBackToBrowserVoice() throws Exception {
        mvc().perform(post("/api/voice/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Hello\",\"language\":\"en-IN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.fallback").value("browser"));
    }

    @Test
    void missingAudioIsBadRequest() throws Exception {
        mvc().perform(post("/api/voice/transcribe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"hi-IN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("audio_required"));
    }

    @Test
    void missingTextIsBadRequest() throws Exception {
        mvc().perform(post("/api/voice/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"hi-IN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("text_required"));
    }

    @Test
    void credentialsNeverAppearInResponses() throws Exception {
        // Even the fail-soft bodies must carry no secret-shaped fields.
        mvc().perform(post("/api/voice/speak")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Namaste\",\"language\":\"hi-IN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.userId").doesNotExist());
    }
}
