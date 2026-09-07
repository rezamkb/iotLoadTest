package org.example.cepbench.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only record of every resource a run has created or deleted, one JSON object per line.
 *
 * <p>This is the safety net for the whole benchmark. Provisioning creates hundreds of resources on a
 * shared sandbox over several minutes, and anything that can be interrupted will be. Each record is
 * written and fsynced the instant its API call returns, before the next call is made, so the file on
 * disk is never behind reality. Cleanup then deletes exactly what the journal names.
 *
 * <p>Two deliberate properties:
 *
 * <ul>
 *   <li><b>Append only.</b> A delete appends a tombstone rather than rewriting the file, so a crash
 *       mid-cleanup cannot lose the record of what still exists. State is the fold over all lines.</li>
 *   <li><b>Never adopt.</b> Nothing is looked up by name on the platform. A resource that happens to
 *       share this run's naming convention but is absent from the journal is somebody else's, and
 *       cleanup will not touch it.</li>
 * </ul>
 */
public final class ManifestJournal implements Closeable {

    private static final String OP_RUN = "RUN";
    private static final String OP_CREATED = "CREATED";
    private static final String OP_DELETED = "DELETED";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path file;
    private final FileOutputStream out;

    private ManifestJournal(Path file, FileOutputStream out) {
        this.file = file;
        this.out = out;
    }

    /**
     * Opens, and creates if absent, the journal for one run.
     *
     * @throws IllegalStateException if an existing journal was written against a different API host,
     *                               which would otherwise let cleanup issue deletes to the wrong
     *                               environment using ids that mean nothing there
     */
    public static ManifestJournal open(Path directory, String runId, String apiBaseUrl) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(runId + ".jsonl");
        boolean fresh = !Files.exists(file);

        if (!fresh) {
            String recorded = recordedApiBaseUrl(file);
            if (recorded != null && !recorded.equals(apiBaseUrl)) {
                throw new IllegalStateException(
                        "Manifest %s was created against %s but this config points at %s. Use a different runId."
                                .formatted(file, recorded, apiBaseUrl));
            }
        }

        ManifestJournal journal = new ManifestJournal(file, new FileOutputStream(file.toFile(), true));
        if (fresh) {
            ObjectNode header = journal.objectMapper.createObjectNode();
            header.put("op", OP_RUN);
            header.put("runId", runId);
            header.put("apiBaseUrl", apiBaseUrl);
            header.put("at", Instant.now().toString());
            journal.append(header);
        }
        return journal;
    }

    public Path file() {
        return file;
    }

    /** Records a successful create. Returns only after the line is on disk. */
    public synchronized void recordCreated(ResourceKind kind, String key, String id, String name) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("op", OP_CREATED);
        node.put("kind", kind.name());
        node.put("key", key);
        node.put("id", id);
        node.put("name", name);
        node.put("at", Instant.now().toString());
        append(node);
    }

    /** Records a successful delete. Only ever appended after the platform confirmed it. */
    public synchronized void recordDeleted(ResourceKind kind, String key, String id) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("op", OP_DELETED);
        node.put("kind", kind.name());
        node.put("key", key);
        node.put("id", id);
        node.put("at", Instant.now().toString());
        append(node);
    }

    /** Folds the journal into the set of resources that currently exist. */
    public ManifestState state() throws IOException {
        Map<ResourceKind, Map<String, ResourceRef>> live = new LinkedHashMap<>();
        for (ResourceKind kind : ResourceKind.values()) {
            live.put(kind, new LinkedHashMap<>());
        }

        List<String> lines = Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        int lineNumber = 0;
        List<String> unreadable = new ArrayList<>();

        for (String line : lines) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            JsonNode node;
            try {
                node = objectMapper.readTree(line);
            } catch (IOException e) {
                // A torn final line is expected after a hard kill; anything earlier is not, so both
                // are surfaced rather than silently skipped.
                unreadable.add("line " + lineNumber);
                continue;
            }
            String op = node.path("op").asText("");
            if (op.equals(OP_RUN)) {
                continue;
            }
            ResourceKind kind = parseKind(node.path("kind").asText(""));
            if (kind == null) {
                unreadable.add("line " + lineNumber + " (unknown kind)");
                continue;
            }
            String key = node.path("key").asText("");
            switch (op) {
                case OP_CREATED -> live.get(kind).put(key, new ResourceRef(
                        kind, key, node.path("id").asText(""), node.path("name").asText("")));
                case OP_DELETED -> live.get(kind).remove(key);
                default -> unreadable.add("line " + lineNumber + " (unknown op)");
            }
        }
        return new ManifestState(live, unreadable);
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    private void append(ObjectNode node) {
        try {
            byte[] bytes = (objectMapper.writeValueAsString(node) + System.lineSeparator())
                    .getBytes(StandardCharsets.UTF_8);
            out.write(bytes);
            out.flush();
            // The whole point of the journal is that it survives the process dying mid-run, which
            // means the bytes have to reach the disk, not just the page cache.
            out.getFD().sync();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write to manifest " + file, e);
        }
    }

    private static String recordedApiBaseUrl(Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = mapper.readTree(line);
                if (node.path("op").asText("").equals(OP_RUN)) {
                    return node.path("apiBaseUrl").asText(null);
                }
            } catch (IOException ignored) {
                // Fall through: a manifest without a readable header is treated as unknown rather
                // than as a mismatch, so a torn header does not block cleanup of real resources.
            }
        }
        return null;
    }

    private static ResourceKind parseKind(String raw) {
        for (ResourceKind kind : ResourceKind.values()) {
            if (kind.name().equals(raw)) {
                return kind;
            }
        }
        return null;
    }
}
