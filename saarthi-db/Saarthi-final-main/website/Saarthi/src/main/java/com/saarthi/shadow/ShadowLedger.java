package com.saarthi.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Append-only JSONL ledger for live IFS shadow records.
 *
 * <p>Immutability contract: the first record written for an
 * {@code (issue_id, block)} key wins. Repeat retrievals of the same issue
 * return {@code "duplicate_skipped"} and never overwrite — two different
 * forecast retrievals are never silently treated as identical.
 *
 * <p>Schema compatibility: lines whose {@code schema_version} is not
 * {@code ifs-shadow/v1} are ignored by key scans (the Python truth/metrics
 * tools count them as foreign lines rather than parsing them as shadow
 * records, keeping historical GEFS metrics strictly separate).
 */
public class ShadowLedger {

    public static final String SCHEMA_VERSION = "ifs-shadow/v1";

    private final Path path;
    private final String schemaVersion;
    private final ObjectMapper mapper = new ObjectMapper();

    public ShadowLedger(Path path) {
        this(path, SCHEMA_VERSION);
    }

    /**
     * Ledger for a specific schema version (e.g. the field-shadow ledger).
     * The dry-spell ledger always uses {@link #SCHEMA_VERSION}; its schema
     * and single-arg behaviour are unchanged by this overload.
     */
    public ShadowLedger(Path path, String schemaVersion) {
        this.path = path;
        this.schemaVersion = schemaVersion;
    }

    public Path path() {
        return path;
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    /**
     * Repository-root-anchored default ledger path for {@code fileName} under
     * {@code data/processed/shadow/}.
     *
     * <p>The Spring module normally runs with cwd = {@code website/Saarthi},
     * but IDEs, packaged jars, or scripts may start elsewhere — and the old
     * {@code user.dir + "/../../..."} formula then silently pointed at a
     * different directory (capture is fail-soft, so nothing complained).
     * To keep the repo-canonical location deterministic, walk up from
     * {@code user.dir} to the nearest directory containing a
     * {@code data/processed} subtree; fall back to the legacy formula only
     * when no such marker is found. Explicit {@code saarthi.*.path}
     * overrides are unaffected.
     */
    public static Path defaultPath(String fileName) {
        Path root = repoRoot();
        if (root != null) {
            return root.resolve("data").resolve("processed")
                    .resolve("shadow").resolve(fileName);
        }
        return Paths.get(System.getProperty("user.dir"),
                "..", "..", "data", "processed", "shadow", fileName).normalize();
    }

    /** Nearest ancestor of {@code user.dir} holding {@code data/processed}, else {@code null}. */
    static Path repoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (Files.isDirectory(dir.resolve("data").resolve("processed"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * Append {@code record} unless {@code (issueId, block)} already exists.
     *
     * @return {@code "written"} or {@code "duplicate_skipped"}
     * @throws IOException on storage failure (callers must treat the ledger as
     *         fail-soft: log and keep serving weather)
     */
    public synchronized String appendIfAbsent(String issueId, String block,
            Map<String, Object> record) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        if (keyExists(issueId, block)) return "duplicate_skipped";
        String line = mapper.writeValueAsString(record) + "\n";
        Files.writeString(path, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return "written";
    }

    private boolean keyExists(String issueId, String block) throws IOException {
        if (!Files.isRegularFile(path)) return false;
        try (BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (Exception e) {
                    throw new IOException("Corrupt shadow ledger line: " + e.getMessage());
                }
                if (!schemaVersion.equals(node.path("schema_version").asText(null))) {
                    continue; // foreign line: never parsed as a shadow record
                }
                if (issueId.equals(node.path("issue_id").asText(null))
                        && block.equals(node.path("block").asText(null))) {
                    return true;
                }
            }
        }
        return false;
    }
}
