package com.sbtools.cleaner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persists cleanup history (past clean sessions) to a JSON file.
 */
public class CleanerHistoryStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final int MAX_HISTORY_ENTRIES = 50;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HistoryEntry(
            String timestamp,
            long totalBytesFreed,
            int totalItems,
            Map<String, Long> perCategoryBytes,
            String appVersion,
            List<String> errors
    ) {
        public HistoryEntry {
            if (perCategoryBytes == null) perCategoryBytes = java.util.Collections.emptyMap();
            if (appVersion == null) appVersion = "";
            if (errors == null) errors = java.util.Collections.emptyList();
        }

        /** Backward-compatible constructor for pre-existing call sites and old JSON. */
        public HistoryEntry(String timestamp, long totalBytesFreed, int totalItems,
                            Map<String, Long> perCategoryBytes) {
            this(timestamp, totalBytesFreed, totalItems, perCategoryBytes, "", java.util.Collections.emptyList());
        }
    }

    private Path getHistoryFile() {
        return AppPaths.dataDir().resolve("cleanup-history.json");
    }

    public synchronized List<HistoryEntry> load() {
        Path file = getHistoryFile();
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            byte[] data = Files.readAllBytes(file);
            return new ArrayList<>(MAPPER.readValue(data, new TypeReference<List<HistoryEntry>>() {}));
        } catch (Exception e) {
            AppLogger.warning("Failed to load cleanup history: " + e.getMessage());
            // Preserve the corrupt file for diagnosis instead of letting the
            // next append silently overwrite up to 50 entries.
            try {
                Path backup = file.resolveSibling("cleanup-history.corrupt-" + System.currentTimeMillis() + ".json");
                Files.move(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {}
            return new ArrayList<>();
        }
    }

    public synchronized void append(CleanupService.CleanSummary summary) {
        List<HistoryEntry> history = load();
        Map<String, Long> perCategory = new java.util.HashMap<>();
        summary.getPerCategory().forEach((cat, bytes) ->
                perCategory.put(cat.getDisplayName(), bytes));

        String version = "";
        try {
            version = com.sbtools.util.AppInfo.VERSION != null ? com.sbtools.util.AppInfo.VERSION : "";
        } catch (Exception ignored) {}
        HistoryEntry entry = new HistoryEntry(
                Instant.now().toString(),
                summary.getTotalBytes(),
                summary.getTotalItems(),
                perCategory,
                version,
                new java.util.ArrayList<>(summary.getErrors())
        );
        history.add(0, entry);

        while (history.size() > MAX_HISTORY_ENTRIES) {
            history.remove(history.size() - 1);
        }

        try {
            Path file = getHistoryFile();
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), ".cleanup-history.", ".tmp");
            try {
                MAPPER.writeValue(tmp.toFile(), history);
                try {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            AppLogger.warning("Failed to save cleanup history: " + e.getMessage());
        }
    }

    public synchronized long getTotalBytesFreedAllTime() {
        return load().stream().mapToLong(HistoryEntry::totalBytesFreed).sum();
    }

    public synchronized void clear() {
        try {
            Path file = getHistoryFile();
            Files.createDirectories(file.getParent());
            Path tmp = Files.createTempFile(file.getParent(), ".cleanup-history.", ".tmp");
            try {
                MAPPER.writeValue(tmp.toFile(), new java.util.ArrayList<HistoryEntry>());
                try {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            AppLogger.warning("Failed to clear cleanup history: " + e.getMessage());
        }
    }
}
