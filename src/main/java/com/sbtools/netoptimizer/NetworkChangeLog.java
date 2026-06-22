package com.sbtools.netoptimizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sbtools.util.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class NetworkChangeLog {

    private static final int MAX_ENTRIES = 3;
    private static final String DIR = ".winzenith";
    private static final String FILE = "network-changelog.json";

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public synchronized void append(NetworkChangeEntry entry) {
        List<NetworkChangeEntry> entries = load();
        entries.add(0, entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        save(entries);
    }

    public synchronized List<NetworkChangeEntry> load() {
        Path p = path();
        if (!Files.exists(p)) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(p.toFile(), new TypeReference<List<NetworkChangeEntry>>() {});
        } catch (IOException e) {
            AppLogger.warning("Failed to load network change log: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public synchronized void clear() {
        save(new ArrayList<>());
    }

    private void save(List<NetworkChangeEntry> entries) {
        try {
            Path dir = Path.of(System.getProperty("user.home"), DIR);
            Files.createDirectories(dir);
            mapper.writeValue(path().toFile(), entries);
        } catch (IOException e) {
            AppLogger.warning("Failed to save network change log: " + e.getMessage());
        }
    }

    private Path path() {
        return Path.of(System.getProperty("user.home"), DIR, FILE);
    }
}
