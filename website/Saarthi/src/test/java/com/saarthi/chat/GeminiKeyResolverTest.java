package com.saarthi.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Key resolution and startup mode. The point of these tests is that a secret is
 * found when it should be, is not found when it should not be, and is never
 * disclosed by the class that holds it.
 */
class GeminiKeyResolverTest {

    private static final String FAKE = "AIzaSyTESTKEY_not_a_real_key_000";

    private static void writeEnv(Path dir, String name, String body) {
        try {
            Files.writeString(dir.resolve(name), body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("test setup failed", e);
        }
    }

    @Test
    void theEnvironmentVariableWins(@TempDir Path dir) {
        writeEnv(dir, ".env", GeminiKeyResolver.ENV_VAR + "=" + FAKE + "\n");
        GeminiKeyResolver r = new GeminiKeyResolver(
                Map.of(GeminiKeyResolver.ENV_VAR, "AIzaSyFromEnv_000"), dir);
        assertTrue(r.isConfigured());
        assertEquals("AIzaSyFromEnv_000", r.apiKey(),
                "the environment must take precedence over a .env file");
        assertEquals("environment", r.origin());
    }

    @Test
    void aKeyIsReadFromTheLocalDotEnv(@TempDir Path dir) {
        writeEnv(dir, ".env", "# a comment\n\n" + GeminiKeyResolver.ENV_VAR + "=" + FAKE + "\n");
        GeminiKeyResolver r = new GeminiKeyResolver(Map.of(), dir);
        assertTrue(r.isConfigured());
        assertEquals(FAKE, r.apiKey());
    }

    @Test
    void aQuotedValueAndAnExportPrefixAreBothUnderstood(@TempDir Path dir) {
        writeEnv(dir, ".env", "export " + GeminiKeyResolver.ENV_VAR + "=\"" + FAKE + "\"\n");
        GeminiKeyResolver r = new GeminiKeyResolver(Map.of(), dir);
        assertTrue(r.isConfigured());
        assertEquals(FAKE, r.apiKey(), "quotes and export must be stripped");
    }

    @Test
    void aParentDotEnvIsFoundWhenTheLocalOneHasNoKey(@TempDir Path base) throws Exception {
        Path parent = Files.createDirectories(base.resolve("child"));
        writeEnv(base, ".env", GeminiKeyResolver.ENV_VAR + "=" + FAKE + "\n");
        GeminiKeyResolver r = new GeminiKeyResolver(Map.of(), parent);
        assertTrue(r.isConfigured(), "../.env must be searched");
        assertEquals(FAKE, r.apiKey());
    }

    @Test
    void aGrandparentDotEnvIsFound(@TempDir Path root) throws Exception {
        Path grandchild = Files.createDirectories(root.resolve("a").resolve("b"));
        writeEnv(root, ".env", GeminiKeyResolver.ENV_VAR + "=" + FAKE + "\n");
        GeminiKeyResolver r = new GeminiKeyResolver(Map.of(), grandchild);
        assertTrue(r.isConfigured(), "../../.env must be searched");
    }

    @Test
    void noKeyAnywhereMeansUnconfiguredAndStillUsable(@TempDir Path dir) {
        GeminiKeyResolver r = new GeminiKeyResolver(Map.of(), dir);
        assertFalse(r.isConfigured());
        assertNull(r.apiKey());
        assertNull(r.origin());
    }

    @Test
    void aBlankOrCommentOnlyValueIsNotAKey(@TempDir Path dir) {
        writeEnv(dir, ".env", GeminiKeyResolver.ENV_VAR + "=\n# only a comment\n");
        assertFalse(new GeminiKeyResolver(Map.of(), dir).isConfigured(),
                "an empty value must not count as a configured key");
    }

    @Test
    void anEmptyEnvironmentVariableIsIgnored(@TempDir Path dir) {
        assertFalse(new GeminiKeyResolver(
                Map.of(GeminiKeyResolver.ENV_VAR, "   "), dir).isConfigured());
    }

    @Test
    void anUnrelatedVariableIsNotMistakenForTheKey(@TempDir Path dir) throws Exception {
        writeEnv(dir, ".env", "SOMETHING_ELSE=abc\nPATH=/usr/bin\n");
        assertFalse(new GeminiKeyResolver(Map.of(), dir).isConfigured());
    }

    @Test
    void originNeverDisclosesTheKey(@TempDir Path dir) throws Exception {
        writeEnv(dir, ".env", GeminiKeyResolver.ENV_VAR + "=" + FAKE + "\n");
        GeminiKeyResolver r = new GeminiKeyResolver(Map.of(), dir);
        assertNotNull(r.origin());
        assertFalse(r.origin().contains(FAKE), "origin must not contain any part of the key");
        assertFalse(r.origin().contains("AIza"));
    }

    @Test
    void theResolverHasNoApiForAnAbsoluteDotEnvPath() {
        // Guards the "never use an absolute path" rule structurally: the only
        // candidate paths are the three relative ones, and the resolver exposes
        // no setter or override for them.
        boolean hasAbsoluteApi = false;
        for (java.lang.reflect.Method m : GeminiKeyResolver.class.getDeclaredMethods()) {
            String n = m.getName().toLowerCase();
            if ((n.startsWith("set") || n.startsWith("with")) && n.contains("path")) {
                hasAbsoluteApi = true;
            }
        }
        assertFalse(hasAbsoluteApi, "there must be no way to inject an absolute .env path");
    }

    @Test
    void theRealEnvironmentResolverConstructsWithoutThrowing() {
        // Exercises the no-arg constructor used by Spring, including a real
        // filesystem walk. It must never throw, key or no key.
        GeminiKeyResolver r = new GeminiKeyResolver();
        assertNotNull(r);
        // A key may or may not be present depending on the machine; both are valid.
        assertEquals(r.isConfigured(), r.apiKey() != null);
    }
}
