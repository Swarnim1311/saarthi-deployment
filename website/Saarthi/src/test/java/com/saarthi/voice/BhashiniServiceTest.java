package com.saarthi.voice;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bhashini service fail-soft guarantees: no credentials or no reachable
 * provider means {@code null} (the controller's browser fallback), never an
 * exception and never a secret in the result.
 */
class BhashiniServiceTest {

    private static BhashiniService unconfigured() {
        return new BhashiniService(
                new BhashiniProperties("", "", "64392f96daac500b55c543cd",
                        "http://127.0.0.1:1/unreachable", 1, 60),
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .build());
    }

    @Test
    void unconfiguredServiceReportsItself() {
        assertFalse(unconfigured().isConfigured());
    }

    @Test
    void unconfiguredTtsReturnsNull() {
        assertNull(unconfigured().synthesize("Namaste", "hi-IN"));
    }

    @Test
    void unconfiguredAsrReturnsNull() {
        assertNull(unconfigured().transcribe("dGVzdA==", "hi-IN"));
    }

    @Test
    void unreachableProviderIsFailSoft() {
        BhashiniService service = new BhashiniService(
                new BhashiniProperties("user", "key", "64392f96daac500b55c543cd",
                        "http://127.0.0.1:1/unreachable", 1, 60),
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(1))
                        .build());
        assertTrue(service.isConfigured());
        assertNull(service.synthesize("Namaste", "hi"),
                "an unreachable provider must degrade to null, not throw");
        assertNull(service.transcribe("dGVzdA==", "hi"));
    }

    @Test
    void englishUsesBrowserFallback() {
        // English is outside the Bhashini voice layer by design.
        assertNull(unconfigured().synthesize("Hello", "en-IN"));
        assertNull(unconfigured().transcribe("dGVzdA==", "en-IN"));
    }

    @Test
    void oversizedInputsAreRejected() {
        assertNull(unconfigured().synthesize("a".repeat(2001), "hi"));
    }
}
