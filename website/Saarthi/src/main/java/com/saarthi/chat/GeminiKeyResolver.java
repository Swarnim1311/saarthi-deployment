package com.saarthi.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Resolves the Gemini API key. <b>Server-side only.</b>
 *
 * <p>Lookup order, highest priority first:
 * <ol>
 *   <li>the {@code GOOGLE_API_KEY} environment variable;</li>
 *   <li>{@code .env} in the working directory;</li>
 *   <li>{@code ../.env};</li>
 *   <li>{@code ../../.env}.</li>
 * </ol>
 *
 * <p><b>Security properties this class is responsible for.</b>
 * <ul>
 *   <li>The key is never returned to the browser, never logged, and never included
 *       in an error message.</li>
 *   <li>Only <b>relative</b> paths are opened. There is deliberately no overload
 *       accepting an absolute path, so no caller can point the resolver at an
 *       arbitrary file on the machine.</li>
 *   <li>No key is compiled in. With none configured the application still starts
 *       and the assistant answers from the local corpus.</li>
 * </ul>
 *
 * <p>The key is read once at construction and cached, so the file system is not
 * touched on the request path.
 */
@Component
public class GeminiKeyResolver {

    private static final Logger log = LoggerFactory.getLogger(GeminiKeyResolver.class);

    /** The single environment variable consulted. */
    public static final String ENV_VAR = "GOOGLE_API_KEY";

    /** Relative candidate locations, in priority order. Never absolute. */
    private static final List<String> RELATIVE_ENV_PATHS =
            List.of(".env", "../.env", "../../.env");

    private final String apiKey;
    private final String origin;

    public GeminiKeyResolver() {
        String fromEnv = trimToNull(System.getenv(ENV_VAR));
        if (fromEnv != null) {
            this.apiKey = fromEnv;
            this.origin = "environment";
            return;
        }
        for (String rel : RELATIVE_ENV_PATHS) {
            String found = readEnvFile(rel);
            if (found != null) {
                this.apiKey = found;
                this.origin = rel;
                return;
            }
        }
        this.apiKey = null;
        this.origin = null;
    }

    /** Test seam: resolve from an explicit environment map and base directory. */
    GeminiKeyResolver(java.util.Map<String, String> env, Path baseDir) {
        String fromEnv = trimToNull(env.get(ENV_VAR));
        if (fromEnv != null) {
            this.apiKey = fromEnv;
            this.origin = "environment";
            return;
        }
        for (String rel : RELATIVE_ENV_PATHS) {
            // baseDir + a *relative* segment; still no absolute path is ever used.
            Path candidate = baseDir == null ? Paths.get(rel) : baseDir.resolve(rel);
            String found = readFile(candidate);
            if (found != null) {
                this.apiKey = found;
                this.origin = rel;
                return;
            }
        }
        this.apiKey = null;
        this.origin = null;
    }

    /** True when a usable key is configured. */
    public boolean isConfigured() {
        return apiKey != null;
    }

    /**
     * The key, or {@code null}. Callers must use this only to build an outbound
     * header — never to log it, echo it, or place it in a response.
     */
    public String apiKey() {
        return apiKey;
    }

    /**
     * Where the key came from, for a startup line. Deliberately reports only the
     * mechanism ("environment", ".env"), never any part of the key.
     */
    public String origin() {
        return origin;
    }

    /**
     * Log exactly one mode line at startup, and never the key. This is the only
     * place the resolved mode is announced.
     */
    public void logStartupMode() {
        if (isConfigured()) {
            log.info("Chatbot mode: GEMINI MODE (API key found via {})", origin);
        } else {
            log.info("Chatbot mode: LOCAL FALLBACK MODE (no {} configured)", ENV_VAR);
        }
    }

    // ---- file reading ----

    private static String readEnvFile(String relative) {
        return readFile(Paths.get(relative));
    }

    private static String readFile(Path path) {
        try {
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) return null;
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw == null ? "" : raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String name = line.substring(0, eq).trim();
                // Tolerate an `export ` prefix, as written by shell users.
                if (name.startsWith("export ")) name = name.substring(7).trim();
                if (!ENV_VAR.equals(name)) continue;
                String value = trimToNull(unquote(line.substring(eq + 1)));
                if (value != null) return value;
            }
            return null;
        } catch (IOException | RuntimeException e) {
            // A malformed or unreadable .env must never stop the application.
            log.debug("Could not read {} for {}: {}", path, ENV_VAR, e.getMessage());
            return null;
        }
    }

    private static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String trimToNull(String v) {
        if (v == null) return null;
        String s = v.trim();
        return s.isEmpty() ? null : s;
    }
}
