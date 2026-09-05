package com.sbtools.drivers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sbtools.util.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class UpdateHistoryStore {

    private static final String DIR = ".winzenith";
    private static final String FILE = "update-history.json";
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdateEntry(
            String id,
            String deviceId,
            String deviceName,
            String oldVersion,
            String newVersion,
            String source,
            Instant timestamp,
            boolean success,
            String detail
    ) {
        /** Backwards-compatible accessor: old callers expect 8 fields. */
        public UpdateEntry(String id, String deviceId, String deviceName, String oldVersion,
                           String newVersion, String source, Instant timestamp, boolean success) {
            this(id, deviceId, deviceName, oldVersion, newVersion, source, timestamp, success, "");
        }
    }

    public List<UpdateEntry> listAll() throws IOException {
        return loadHistory().stream()
                .sorted(Comparator.comparing(UpdateEntry::timestamp).reversed())
                .toList();
    }

    public void recordUpdate(String deviceId, String deviceName, String oldVersion,
                             String newVersion, String source, boolean success) throws IOException {
        recordUpdate(deviceId, deviceName, oldVersion, newVersion, source, success, "");
    }

    public void recordUpdate(String deviceId, String deviceName, String oldVersion,
                             String newVersion, String source, boolean success, String detail) throws IOException {
        List<UpdateEntry> history = loadHistory();
        UpdateEntry entry = new UpdateEntry(
                java.util.UUID.randomUUID().toString(),
                deviceId,
                deviceName,
                oldVersion,
                newVersion,
                source,
                Instant.now(),
                success,
                detail == null ? "" : detail
        );
        history.add(entry);
        saveHistory(history);
        AppLogger.info("Update history recorded: " + deviceName + " " + oldVersion + " -> " + newVersion);
    }

    /**
     * Tolerant loader: parses each entry field-by-field so history files
     * written by older versions (8 fields, no {@code detail}) still load.
     * Corrupt entries are skipped, never aborting the whole history.
     */
    private static UpdateEntry nodeToEntry(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        try {
            String id = text(n, "id");
            if (id.isBlank()) id = java.util.UUID.randomUUID().toString();
            String deviceId = text(n, "deviceId");
            String deviceName = text(n, "deviceName");
            String oldVersion = text(n, "oldVersion");
            String newVersion = text(n, "newVersion");
            String source = text(n, "source");
            Instant ts;
            try {
                String raw = text(n, "timestamp");
                ts = raw.isBlank() ? Instant.now() : Instant.parse(raw);
            } catch (Exception ex) {
                ts = Instant.now();
            }
            boolean success = n.has("success") && !n.get("success").isNull()
                    ? n.get("success").asBoolean(false) : false;
            String detail = n.has("detail") && !n.get("detail").isNull()
                    ? n.get("detail").asText("") : "";
            return new UpdateEntry(id, deviceId, deviceName, oldVersion, newVersion, source, ts, success, detail);
        } catch (Exception ex) {
            AppLogger.warning("Skipping corrupt history entry: " + ex.getMessage());
            return null;
        }
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v != null && !v.isNull() ? v.asText("") : "";
    }

    private static List<UpdateEntry> parseArray(JsonNode root) {
        List<UpdateEntry> out = new ArrayList<>();
        if (root == null || !root.isArray()) return out;
        for (JsonNode n : root) {
            UpdateEntry e = nodeToEntry(n);
            if (e != null) out.add(e);
        }
        return out;
    }

    private List<UpdateEntry> loadHistory() {
        Path p = path();
        List<UpdateEntry> primary = new ArrayList<>();
        boolean loadedPrimary = false;
        if (Files.exists(p)) {
            try {
                JsonNode root = mapper.readTree(p.toFile());
                if (root.isArray()) {
                    primary = parseArray(root);
                    loadedPrimary = true;
                }
            } catch (IOException e) {
                AppLogger.warning("Failed to load update history: " + e.getMessage());
            }
        }
        // Merge fallback from legacy location if different from primary (ensures portable migration doesn't hide history)
        try {
            Path legacy = legacyPath();
            if (!legacy.equals(p) && Files.exists(legacy)) {
                try {
                    JsonNode root2 = mapper.readTree(legacy.toFile());
                    if (root2.isArray()) {
                        List<UpdateEntry> legacyList = parseArray(root2);
                        if (!legacyList.isEmpty()) {
                            if (!loadedPrimary) {
                                return new ArrayList<>(legacyList);
                            }
                            // Merge without duplicates (by id)
                            java.util.Set<String> seen = new java.util.HashSet<>();
                            for (UpdateEntry e : primary) if (e.id() != null) seen.add(e.id());
                            for (UpdateEntry e : legacyList) if (e.id() != null && seen.add(e.id())) primary.add(e);
                        }
                    }
                } catch (Exception ex) {
                    AppLogger.warning("Failed to load legacy history: " + ex.getMessage());
                }
            }
        } catch (Exception ignored) {}
        return primary;
    }

    private void saveHistory(List<UpdateEntry> history) throws IOException {
        Path p = path();
        Path dir = p.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        // Atomic write via tmp + move to avoid corruption on crash
        Path tmp = p.resolveSibling("." + p.getFileName().toString() + ".tmp");
        mapper.writeValue(tmp.toFile(), history);
        try {
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
        }
    }

    private Path path() {
        try {
            Path portable = com.sbtools.util.AppPaths.portableBaseDir();
            if (portable != null) {
                Path portablePath = portable.resolve(FILE);
                if (Files.exists(portablePath)) {
                    return portablePath;
                }
                try {
                    Files.createDirectories(portable);
                    if (Files.isWritable(portable)) {
                        Path legacy = Path.of(System.getProperty("user.home"), DIR, FILE);
                        if (Files.exists(legacy) && !Files.exists(portablePath)) {
                            try {
                                Files.copy(legacy, portablePath);
                                AppLogger.info("UpdateHistoryStore: Migrated history to portable location " + portablePath);
                            } catch (Exception ignored) {}
                        }
                        return portablePath;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return Path.of(System.getProperty("user.home"), DIR, FILE);
    }

    private Path legacyPath() {
        return Path.of(System.getProperty("user.home"), DIR, FILE);
    }
}
