package com.sbtools.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SettingsStore {

    private static final String DIR = ".basic-s-driver-update";
    private static final String FILE = "settings.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public AppSettings load() {
        Path p = path();
        if (!Files.exists(p)) {
            return AppSettings.defaults();
        }
        try {
            JsonNode root = mapper.readTree(p.toFile());
            AppSettings d = AppSettings.defaults();
            return new AppSettings(
                    bool(root, "autoBackupDrivers", d.autoBackupDrivers()),
                    bool(root, "createSystemRestorePoint", d.createSystemRestorePoint()),
                    bool(root, "eulaAccepted", d.eulaAccepted())
            );
        } catch (IOException e) {
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

    private static boolean bool(JsonNode root, String key, boolean fallback) {
        JsonNode n = root.get(key);
        return n != null && n.isBoolean() ? n.asBoolean() : fallback;
    }
}
