package com.sbtools.software;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sbtools.util.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SoftwareUpdateHistoryStore {

    private static final int MAX_ENTRIES = 500;
    private static final String DIR = ".winzenith";
    private static final String FILE = "software-update-history.json";
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Object lock = new Object();

    public void add(SoftwareUpdateHistoryEntry entry) {
        synchronized (lock) {
            try {
                List<SoftwareUpdateHistoryEntry> history = load();
                history.add(entry);
                if (history.size() > MAX_ENTRIES) {
                    history = history.subList(history.size() - MAX_ENTRIES, history.size());
                }
                save(history);
                AppLogger.info("Software update history recorded: " + entry.packageName()
                        + " " + entry.oldVersion() + " -> " + entry.newVersion());
            } catch (Exception ex) {
                AppLogger.warning("Failed to record software update history: " + ex.getMessage());
            }
        }
    }

    public List<SoftwareUpdateHistoryEntry> listAll() {
        synchronized (lock) {
            return load().stream()
                    .sorted(Comparator.comparing(SoftwareUpdateHistoryEntry::installedAt).reversed())
                    .toList();
        }
    }

    public void clear() {
        synchronized (lock) {
            try {
                save(new ArrayList<>());
            } catch (Exception ex) {
                AppLogger.warning("Failed to clear software update history: " + ex.getMessage());
            }
        }
    }

    private List<SoftwareUpdateHistoryEntry> load() {
        Path p = path();
        if (!Files.exists(p)) {
            return new ArrayList<>();
        }
        try {
            JsonNode root = mapper.readTree(p.toFile());
            if (root.isArray()) {
                return mapper.convertValue(root, new TypeReference<List<SoftwareUpdateHistoryEntry>>() {});
            }
        } catch (IOException e) {
            AppLogger.warning("Failed to load software update history: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    private void save(List<SoftwareUpdateHistoryEntry> history) throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), DIR);
        Files.createDirectories(dir);
        Path target = path();
        Path temp = dir.resolve(".software-update-history-" + ProcessHandle.current().pid() + ".tmp");
        try {
            mapper.writeValue(temp.toFile(), history);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private Path path() {
        return Path.of(System.getProperty("user.home"), DIR, FILE);
    }
}
