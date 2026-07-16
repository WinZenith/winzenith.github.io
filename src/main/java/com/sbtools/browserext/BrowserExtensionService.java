package com.sbtools.browserext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbtools.util.AppLogger;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.ProcessResult;

import javafx.application.Platform;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class BrowserExtensionService {

    public static final List<String> ALL_BROWSERS = List.of(
            "Chrome", "Chrome Canary",
            "Edge", "Edge Beta", "Edge Dev", "Edge Canary",
            "Firefox", "Brave", "Opera", "Opera GX", "Vivaldi"
    );

    private static final List<String> CHROMIUM_BROWSERS = List.of(
            "Chrome", "Chrome Canary", "Edge", "Edge Beta", "Edge Dev", "Edge Canary",
            "Brave", "Opera", "Opera GX", "Vivaldi"
    );

    public record ToggleResult(int success, int failed) {}

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Scan all browsers individually, calling onProgress(browserName, completedCount, totalCount)
     * after each browser completes. This enables real-time progress UI updates.
     */
    public List<BrowserExtensionRow> scanAllBrowsers(java.util.function.Consumer<String> onProgress) {
        List<BrowserExtensionRow> all = new ArrayList<>();
        int total = ALL_BROWSERS.size();
        for (int i = 0; i < total; i++) {
            String browser = ALL_BROWSERS.get(i);
            all.addAll(scanBrowser(browser));
            if (onProgress != null) {
                onProgress.accept(browser + " (" + (i + 1) + "/" + total + ")");
            }
        }
        return all;
    }

    public List<BrowserExtensionRow> scanBrowser(String browser) {
        List<BrowserExtensionRow> results = new ArrayList<>();
        try {
            Path script = PowerShellScripts.resolve("browser-extensions.ps1");
            ProcessResult pr = new ProcessRunner(120).run(
                    ProcessRunner.powershellScript(script.toString(), "-Browser", browser));
            int exitCode = pr.exitCode();
            String stdout = pr.stdout();
            String stderr = pr.stderr().trim();
            if (!stderr.isEmpty()) {
                AppLogger.warning("Script stderr for " + browser + ": " + stderr);
            }
            if (exitCode != 0 || stderr.toLowerCase().contains("error")) {
                AppLogger.warning("[BrowserExtensionService] Exit=" + exitCode + " stderr=" + stderr);
            }
            String trimmed = stdout.trim();
            if (trimmed.isEmpty() || "[]".equals(trimmed)) {
                AppLogger.warning("[BrowserExtensionService] EMPTY stdout for " + browser + " (exit=" + exitCode + ") raw_hex=" + bytesToHex(stdout.getBytes(StandardCharsets.UTF_8)));
                return results;
            }
            stdout = trimmed;
            List<Map<String, Object>> raw = mapper.readValue(stdout,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> entry : raw) {
                try {
                    String id = str(entry, "id");
                    String name = str(entry, "name");
                    String version = str(entry, "version");
                    String description = str(entry, "description");
                    String extBrowser = str(entry, "browser");
                    String extPath = str(entry, "path");
                    String installTime = str(entry, "installTime");
                    String permissions = str(entry, "permissions");
                    boolean enabled = true;
                    Object en = entry.get("enabled");
                    if (en instanceof Boolean) enabled = (Boolean) en;
                    results.add(new BrowserExtensionRow(extBrowser, id, name, version,
                            description, enabled, extPath, installTime, permissions));
                } catch (Exception e) {
                    AppLogger.warning("Failed to parse extension entry: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to scan browser " + browser + ": " + e.getMessage());
        }
        return results;
    }

    public boolean toggleExtension(BrowserExtensionRow ext, boolean enable) {
        try {
            Path extPath = Paths.get(ext.getPath());
            if (!Files.exists(extPath)) return false;
            String browser = ext.getBrowser();
            if (CHROMIUM_BROWSERS.contains(browser)) {
                Path extFolder = extPath.resolve(ext.getExtensionId());
                Path versionDir;
                try (Stream<Path> stream = Files.list(extFolder)) {
                    versionDir = stream
                            .filter(Files::isDirectory)
                            .findFirst().orElse(null);
                }
                if (versionDir == null) return false;
                Path disabledMarker = versionDir.resolve("Disabled");
                if (enable) {
                    Files.deleteIfExists(disabledMarker);
                } else {
                    if (!Files.exists(disabledMarker)) {
                        Files.createFile(disabledMarker);
                    }
                }
                Platform.runLater(() -> ext.setEnabled(enable));
                return true;
            } else if ("Firefox".equals(browser)) {
                if (enable) {
                    Path disabledXpi = extPath.resolve(ext.getExtensionId() + ".xpi.disabled");
                    if (Files.exists(disabledXpi)) {
                        Files.move(disabledXpi, disabledXpi.resolveSibling(ext.getExtensionId() + ".xpi"));
                        Platform.runLater(() -> ext.setEnabled(true));
                        return true;
                    }
                    Path disabledJson = extPath.resolve(ext.getExtensionId() + ".json.disabled");
                    if (Files.exists(disabledJson)) {
                        Files.move(disabledJson, disabledJson.resolveSibling(ext.getExtensionId() + ".json"));
                        Platform.runLater(() -> ext.setEnabled(true));
                        return true;
                    }
                    return false;
                } else {
                    Path xpiPath = extPath.resolve(ext.getExtensionId() + ".xpi");
                    if (Files.exists(xpiPath)) {
                        Path disabledPath = extPath.resolve(ext.getExtensionId() + ".xpi.disabled");
                        if (!Files.exists(disabledPath)) {
                            Files.move(xpiPath, disabledPath);
                        }
                        Platform.runLater(() -> ext.setEnabled(false));
                        return true;
                    }
                    Path jsonPath = extPath.resolve(ext.getExtensionId() + ".json");
                    if (Files.exists(jsonPath)) {
                        Path disabledPath = extPath.resolve(ext.getExtensionId() + ".json.disabled");
                        if (!Files.exists(disabledPath)) {
                            Files.move(jsonPath, disabledPath);
                        }
                        Platform.runLater(() -> ext.setEnabled(false));
                        return true;
                    }
                    return false;
                }
            }
            return false;
        } catch (Exception e) {
            AppLogger.warning("Failed to toggle extension: " + e.getMessage());
            return false;
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
