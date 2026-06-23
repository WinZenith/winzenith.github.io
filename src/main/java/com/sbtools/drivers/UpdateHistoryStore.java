package com.sbtools.drivers;

import com.fasterxml.jackson.core.type.TypeReference;
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

    public record UpdateEntry(
            String id,
            String deviceId,
            String deviceName,
            String oldVersion,
            String newVersion,
            String source,
            Instant timestamp,
            boolean success
    ) {
    }

    public List<UpdateEntry> listAll() throws IOException {
        return loadHistory().stream()
                .sorted(Comparator.comparing(UpdateEntry::timestamp).reversed())
                .toList();
    }

    public void recordUpdate(String deviceId, String deviceName, String oldVersion,
                             String newVersion, String source, boolean success) throws IOException {
        List<UpdateEntry> history = loadHistory();
        UpdateEntry entry = new UpdateEntry(
                java.util.UUID.randomUUID().toString(),
                deviceId,
                deviceName,
                oldVersion,
                newVersion,
                source,
                Instant.now(),
                success
        );
        history.add(entry);
        saveHistory(history);
        AppLogger.info("Update history recorded: " + deviceName + " " + oldVersion + " -> " + newVersion);
    }

    private List<UpdateEntry> loadHistory() {
        Path p = path();
        if (!Files.exists(p)) {
            return new ArrayList<>();
        }
        try {
            JsonNode root = mapper.readTree(p.toFile());
            if (root.isArray()) {
                return mapper.convertValue(root, new TypeReference<List<UpdateEntry>>() {});
            }
        } catch (IOException e) {
            AppLogger.warning("Failed to load update history: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    private void saveHistory(List<UpdateEntry> history) throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), DIR);
        Files.createDirectories(dir);
        mapper.writeValue(path().toFile(), history);
    }

    private Path path() {
        return Path.of(System.getProperty("user.home"), DIR, FILE);
    }
}
