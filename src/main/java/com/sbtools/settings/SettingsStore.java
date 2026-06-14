package com.sbtools.settings;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
            JsonNode root = mapper.readTree(p.toFile());
            AppSettings d = AppSettings.defaults();
            List<String> excludedDrivers = list(root, "excludedDriverIds");
            List<String> skippedSoftware = list(root, "skippedSoftwareIds");
            String netPreset = str(root, "networkOptimizationPreset");
            String downloadDir = str(root, "downloadDirectory");
            String licenseKey = str(root, "licenseKey");
            String backupDir = str(root, "backupDirectory");
            String psPath = str(root, "powerShellPath");
            return new AppSettings(
                    bool(root, "autoBackupDrivers", d.autoBackupDrivers()),
                    bool(root, "createSystemRestorePoint", d.createSystemRestorePoint()),
                    bool(root, "eulaAccepted", d.eulaAccepted()),
                    excludedDrivers != null ? excludedDrivers : d.excludedDriverIds(),
                    skippedSoftware != null ? skippedSoftware : d.skippedSoftwareIds(),
                    netPreset != null ? netPreset : d.networkOptimizationPreset(),
                    downloadDir != null ? downloadDir : d.downloadDirectory(),
                    licenseKey != null ? licenseKey : d.licenseKey(),
                    bool(root, "minimizeToTray", d.minimizeToTray()),
                    bool(root, "startMinimized", d.startMinimized()),
                    bool(root, "scanOnStartup", d.scanOnStartup()),
                    bool(root, "notifyOnDriverUpdate", d.notifyOnDriverUpdate()),
                    backupDir != null ? backupDir : d.backupDirectory(),
                    psPath != null ? psPath : d.powerShellPath(),
                    intVal(root, "windowWidth", d.windowWidth()),
                    intVal(root, "windowHeight", d.windowHeight()),
                    bool(root, "windowMaximized", d.windowMaximized()),
                    bool(root, "autoCheckForUpdates", d.autoCheckForUpdates())
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

    private static String str(JsonNode root, String key) {
        JsonNode n = root.get(key);
        return n != null && n.isTextual() ? n.asText() : null;
    }

    private static int intVal(JsonNode root, String key, int fallback) {
        JsonNode n = root.get(key);
        return n != null && n.isNumber() ? n.asInt() : fallback;
    }

    private static List<String> list(JsonNode root, String key) {
        JsonNode n = root.get(key);
        if (n != null && n.isArray()) {
            try {
                return mapper.convertValue(n, new TypeReference<List<String>>() {});
            } catch (Exception ignored) {}
        }
        return null;
    }
}
