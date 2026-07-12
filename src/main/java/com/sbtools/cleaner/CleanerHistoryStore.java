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
import java.util.Collections;
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
            Map<String, Long> perCategoryBytes
    ) {}

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
            return new ArrayList<>();
        }
    }

    public synchronized void append(CleanupService.CleanSummary summary) {
        List<HistoryEntry> history = load();
        Map<String, Long> perCategory = new java.util.HashMap<>();
        summary.getPerCategory().forEach((cat, bytes) ->
                perCategory.put(cat.getDisplayName(), bytes));

        HistoryEntry entry = new HistoryEntry(
                Instant.now().toString(),
                summary.getTotalBytes(),
                summary.getTotalItems(),
                perCategory
        );
        history.add(0, entry);

        while (history.size() > MAX_HISTORY_ENTRIES) {
            history.remove(history.size() - 1);
        }

        try {
            Path file = getHistoryFile();
            Files.createDirectories(file.getParent());
            MAPPER.writeValue(file.toFile(), history);
        } catch (IOException e) {
            AppLogger.warning("Failed to save cleanup history: " + e.getMessage());
        }
    }

    public synchronized long getTotalBytesFreedAllTime() {
        return load().stream().mapToLong(HistoryEntry::totalBytesFreed).sum();
    }
}
