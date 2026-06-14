package com.sbtools.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sbtools.util.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LicenseStore {

    private static final String DIR = ".winzenith";
    private static final String FILE = "license.json";
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public LicenseData load() {
        Path p = path();
        AppLogger.info("Loading license from: " + p);
        if (!Files.exists(p)) {
            AppLogger.info("License file does not exist");
            return LicenseData.empty();
        }
        try {
            JsonNode root = mapper.readTree(p.toFile());
            String key = str(root, "licenseKey");
            long savedAt = longVal(root, "savedAt");
            AppLogger.info("License loaded, key present: " + (key != null && !key.isBlank()));
            return new LicenseData(key != null ? key : "", savedAt);
        } catch (IOException e) {
            AppLogger.error("Failed to load license", e);
            return LicenseData.empty();
        }
    }

    public void save(String licenseKey) throws IOException {
        Path dir = Path.of(System.getProperty("user.home"), DIR);
        Files.createDirectories(dir);
        LicenseData data = new LicenseData(licenseKey, System.currentTimeMillis());
        Path target = path();
        AppLogger.info("Saving license to: " + target);
        mapper.writeValue(target.toFile(), data);
        AppLogger.info("License saved successfully");
    }

    public void clear() throws IOException {
        Path p = path();
        if (Files.exists(p)) {
            Files.delete(p);
        }
    }

    private Path path() {
        return Path.of(System.getProperty("user.home"), DIR, FILE);
    }

    private static String str(JsonNode root, String key) {
        JsonNode n = root.get(key);
        return n != null && n.isTextual() ? n.asText() : null;
    }

    private static long longVal(JsonNode root, String key) {
        JsonNode n = root.get(key);
        return n != null && n.isNumber() ? n.asLong() : 0;
    }

    public record LicenseData(String licenseKey, long savedAt) {
        static LicenseData empty() {
            return new LicenseData("", 0);
        }

        boolean isEmpty() {
            return licenseKey == null || licenseKey.isBlank();
        }
    }
}
