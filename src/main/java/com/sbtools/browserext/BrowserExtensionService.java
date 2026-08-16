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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public class BrowserExtensionService {

    public static final List<String> ALL_BROWSERS = List.of(
            "Chrome", "Chrome Canary",
            "Edge", "Edge Beta", "Edge Dev", "Edge Canary",
            "Firefox", "Brave", "Opera", "Opera GX", "Vivaldi"
    );

    private static final Map<String, String> BROWSER_PATHS = Map.ofEntries(
            Map.entry("Chrome",       "%LOCALAPPDATA%\\Google\\Chrome\\User Data"),
            Map.entry("Chrome Canary", "%LOCALAPPDATA%\\Google\\Chrome SxS\\User Data"),
            Map.entry("Edge",         "%LOCALAPPDATA%\\Microsoft\\Edge\\User Data"),
            Map.entry("Edge Beta",    "%LOCALAPPDATA%\\Microsoft\\Edge Beta\\User Data"),
            Map.entry("Edge Dev",     "%LOCALAPPDATA%\\Microsoft\\Edge Dev\\User Data"),
            Map.entry("Edge Canary",  "%LOCALAPPDATA%\\Microsoft\\Edge SxS\\User Data"),
            Map.entry("Firefox",      "%APPDATA%\\Mozilla\\Firefox\\Profiles"),
            Map.entry("Brave",        "%LOCALAPPDATA%\\BraveSoftware\\Brave-Browser\\User Data"),
            Map.entry("Opera",        "%APPDATA%\\Opera Software\\Opera Stable\\Extensions"),
            Map.entry("Opera GX",     "%APPDATA%\\Opera Software\\Opera GX Stable\\Extensions"),
            Map.entry("Vivaldi",      "%LOCALAPPDATA%\\Vivaldi\\User Data")
    );

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Checks if a browser is installed by testing if its profile directory exists.
     */
    public boolean checkBrowserInstalled(String browser) {
        String template = BROWSER_PATHS.get(browser);
        if (template == null) return false;
        String resolved = template.replace("%LOCALAPPDATA%", System.getenv("LOCALAPPDATA") != null ? System.getenv("LOCALAPPDATA") : "")
                                  .replace("%APPDATA%", System.getenv("APPDATA") != null ? System.getenv("APPDATA") : "");
        return Files.exists(Paths.get(resolved));
    }

    /**
     * Scan all browsers in parallel using the provided thread pool.
     * Returns a map of browser name -> installed status, plus extension rows.
     */
    public List<BrowserExtensionRow> scanAllBrowsersParallel(
            ExecutorService pool,
            Consumer<String> onProgress) {
        int total = ALL_BROWSERS.size();
        Map<String, CompletableFuture<List<BrowserExtensionRow>>> futures = new LinkedHashMap<>();

        for (int i = 0; i < total; i++) {
            String browser = ALL_BROWSERS.get(i);
            futures.put(browser, CompletableFuture.supplyAsync(() -> {
                List<BrowserExtensionRow> rows = scanBrowser(browser);
                if (onProgress != null) {
                    onProgress.accept(browser);
                }
                return rows;
            }, pool));
        }

        List<BrowserExtensionRow> all = new ArrayList<>();
        for (Map.Entry<String, CompletableFuture<List<BrowserExtensionRow>>> entry : futures.entrySet()) {
            try {
                all.addAll(entry.getValue().join());
            } catch (Exception e) {
                AppLogger.warning("Failed to scan " + entry.getKey() + ": " + e.getMessage());
            }
        }
        return all;
    }

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
            Path profileDir = extPath.getParent();
            if (profileDir == null || !Files.exists(profileDir)) return false;
            String extId = ext.getExtensionId();
            Path script = PowerShellScripts.resolve("browser-extensions.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString(),
                            "-Action", "Toggle",
                            "-ProfilePath", profileDir.toString(),
                            "-ExtId", extId,
                            "-Enable", String.valueOf(enable)));
            String stdout = pr.stdout().trim();
            boolean success = "true".equals(stdout);
            if (success) {
                Platform.runLater(() -> ext.setEnabled(enable));
            } else {
                AppLogger.warning("Toggle PowerShell returned: " + stdout
                        + " stderr=" + pr.stderr().trim());
            }
            return success;
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
