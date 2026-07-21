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
import java.util.Comparator;
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
            if (exitCode != 0) {
                AppLogger.warning("[BrowserExtensionService] Exit=" + exitCode + " stderr=" + stderr);
            }
            String trimmed = stdout.trim();
            if (trimmed.startsWith("\uFEFF")) {
                trimmed = trimmed.substring(1);
            }
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
            String pathStr = ext.getPath();
            if (pathStr == null || pathStr.isBlank()) return false;
            Path extPath = Paths.get(pathStr);
            if (!Files.exists(extPath)) return false;
            String browser = ext.getBrowser();
            if (CHROMIUM_BROWSERS.contains(browser)) {
                Path extFolder = extPath.resolve(ext.getExtensionId());
                if (!Files.exists(extFolder)) return false;
                Path versionDir;
                try (Stream<Path> stream = Files.list(extFolder)) {
                    versionDir = stream
                            .filter(Files::isDirectory)
                            .filter(p -> {
                                String name = p.getFileName().toString();
                                return !name.equals("metadata") && !name.startsWith(".");
                            })
                            .sorted((a, b) -> compareVersions(
                                    b.getFileName().toString(),
                                    a.getFileName().toString()))
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
                String extId = ext.getExtensionId();
                return toggleFirefoxExtension(extPath, extId, enable, ext);
            }
            return false;
        } catch (Exception e) {
            AppLogger.warning("Failed to toggle extension: " + e.getMessage());
            return false;
        }
    }

    private boolean toggleFirefoxExtension(Path extPath, String extId, boolean enable,
                                           BrowserExtensionRow ext) throws Exception {
        if (enable) {
            Path disabledXpi = extPath.resolve(extId + ".xpi.disabled");
            if (Files.exists(disabledXpi)) {
                Files.move(disabledXpi, disabledXpi.resolveSibling(extId + ".xpi"));
                Platform.runLater(() -> ext.setEnabled(true));
                return true;
            }
            Path disabledJson = extPath.resolve(extId + ".json.disabled");
            if (Files.exists(disabledJson)) {
                Files.move(disabledJson, disabledJson.resolveSibling(extId + ".json"));
                Platform.runLater(() -> ext.setEnabled(true));
                return true;
            }
            Path disabledDir = extPath.resolve(extId + ".dir.disabled");
            if (Files.exists(disabledDir)) {
                Files.move(disabledDir, disabledDir.resolveSibling(extId));
                Platform.runLater(() -> ext.setEnabled(true));
                return true;
            }
            return false;
        } else {
            Path xpiPath = extPath.resolve(extId + ".xpi");
            if (Files.exists(xpiPath)) {
                Path disabledPath = extPath.resolve(extId + ".xpi.disabled");
                if (!Files.exists(disabledPath)) {
                    Files.move(xpiPath, disabledPath);
                }
                Platform.runLater(() -> ext.setEnabled(false));
                return true;
            }
            Path jsonPath = extPath.resolve(extId + ".json");
            if (Files.exists(jsonPath)) {
                Path disabledPath = extPath.resolve(extId + ".json.disabled");
                if (!Files.exists(disabledPath)) {
                    Files.move(jsonPath, disabledPath);
                }
                Platform.runLater(() -> ext.setEnabled(false));
                return true;
            }
            Path dirPath = extPath.resolve(extId);
            if (Files.isDirectory(dirPath)) {
                Path disabledPath = extPath.resolve(extId + ".dir.disabled");
                if (!Files.exists(disabledPath)) {
                    Files.move(dirPath, disabledPath);
                }
                Platform.runLater(() -> ext.setEnabled(false));
                return true;
            }
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

    private static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int p1 = i < parts1.length ? parseIntOrDefault(parts1[i], 0) : 0;
            int p2 = i < parts2.length ? parseIntOrDefault(parts2[i], 0) : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
        }
        return 0;
    }

    private static int parseIntOrDefault(String s, int defaultValue) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
