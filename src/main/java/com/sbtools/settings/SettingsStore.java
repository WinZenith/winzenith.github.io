package com.sbtools.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sbtools.util.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SettingsStore {

    private static final String DIR = ".winzenith";
    private static final String FILE = "settings.json";

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public AppSettings load() {
        Path p = path();
        if (!Files.exists(p)) {
            return AppSettings.defaults();
        }
        try {
            return mapper.readValue(p.toFile(), AppSettings.class);
        } catch (IOException e) {
            AppLogger.warning("Failed to load settings, using defaults: " + e.getMessage());
            return AppSettings.defaults();
        }
    }

    public void save(AppSettings settings) throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), DIR);
        Files.createDirectories(dir);
        mapper.writeValue(path().toFile(), settings);
    }

    private Path path() {
        return Path.of(System.getProperty("user.home"), DIR, FILE);
    }
}
