package com.sbtools.browserext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbtools.util.AppLogger;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.ProcessResult;

import java.io.IOException;
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
            Map.entry("Opera",        "%APPDATA%\\Opera Software\\Opera Stable"),
            Map.entry("Opera GX",     "%APPDATA%\\Opera Software\\Opera GX Stable"),
            Map.entry("Vivaldi",      "%LOCALAPPDATA%\\Vivaldi\\User Data")
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> lastScanErrors = new java.util.concurrent.ConcurrentHashMap<>();

    public Map<String, String> getLastScanErrors() {
        return Map.copyOf(lastScanErrors);
    }

    /**
     * Checks if a browser is installed by testing if its profile directory exists.
     * Falls back to base dir for Firefox (Profiles subdir may not exist on fresh install).
     */
    public boolean checkBrowserInstalled(String browser) {
        String template = BROWSER_PATHS.get(browser);
        if (template == null) return false;
        String resolved = template.replace("%LOCALAPPDATA%", System.getenv("LOCALAPPDATA") != null ? System.getenv("LOCALAPPDATA") : "")
                                   .replace("%APPDATA%", System.getenv("APPDATA") != null ? System.getenv("APPDATA") : "");
        if (resolved.isBlank()) return false;
        try {
            Path p = Paths.get(resolved);
            if (Files.exists(p)) return true;
            // Fallback for Firefox: check base Firefox dir if Profiles missing
            if ("Firefox".equals(browser)) {
                String base = "%APPDATA%\\Mozilla\\Firefox".replace("%APPDATA%", System.getenv("APPDATA") != null ? System.getenv("APPDATA") : "");
                if (!base.isBlank() && Files.exists(Paths.get(base))) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Scan all browsers in parallel using the provided thread pool.
     * Returns extension rows. Throws if all browsers failed due to infrastructure error (e.g., PowerShell missing).
     */
    public List<BrowserExtensionRow> scanAllBrowsersParallel(
            ExecutorService pool,
            Consumer<String> onProgress) throws IOException {
        return scanAllBrowsersParallel(pool, onProgress, null);
    }

    public List<BrowserExtensionRow> scanAllBrowsersParallel(
            ExecutorService pool,
            Consumer<String> onProgress,
            java.util.concurrent.atomic.AtomicBoolean cancelled) throws IOException {
        int total = ALL_BROWSERS.size();
        lastScanErrors.clear();
        Map<String, CompletableFuture<List<BrowserExtensionRow>>> futures = new LinkedHashMap<>();
        Map<String, Throwable> failures = new java.util.concurrent.ConcurrentHashMap<>();

        for (int i = 0; i < total; i++) {
            String browser = ALL_BROWSERS.get(i);
            futures.put(browser, CompletableFuture.supplyAsync(() -> {
                if (cancelled != null && cancelled.get()) {
                    throw new java.util.concurrent.CancellationException("Cancelled");
                }
                try {
                    List<BrowserExtensionRow> rows = scanBrowser(browser, cancelled);
                    if (onProgress != null) {
                        onProgress.accept(browser);
                    }
                    return rows;
                } catch (java.util.concurrent.CancellationException ce) {
                    throw ce;
                } catch (Exception e) {
                    failures.put(browser, e);
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, pool));
        }

        List<BrowserExtensionRow> all = new ArrayList<>();
        int successCount = 0;
        for (Map.Entry<String, CompletableFuture<List<BrowserExtensionRow>>> entry : futures.entrySet()) {
            if (cancelled != null && cancelled.get()) {
                futures.values().forEach(f -> f.cancel(true));
                throw new java.util.concurrent.CancellationException("Scan cancelled");
            }
            try {
                List<BrowserExtensionRow> part = entry.getValue().join();
                all.addAll(part);
                successCount++;
            } catch (java.util.concurrent.CancellationException ce) {
                throw ce;
            } catch (java.util.concurrent.CompletionException ce) {
                Throwable cause = ce.getCause();
                // Unwrap CancellationException
                if (cause instanceof java.util.concurrent.CancellationException) {
                    throw (java.util.concurrent.CancellationException) cause;
                }
                String msg = cause != null ? cause.getMessage() : ce.getMessage();
                AppLogger.warning("Failed to scan " + entry.getKey() + ": " + msg);
                lastScanErrors.put(entry.getKey(), msg != null ? msg : "unknown");
                // continue to collect other browsers
            } catch (Exception e) {
                AppLogger.warning("Failed to scan " + entry.getKey() + ": " + e.getMessage());
                lastScanErrors.put(entry.getKey(), e.getMessage());
            }
        }
        // Populate lastScanErrors from failures map as well (for infra failures)
        for (Map.Entry<String, Throwable> fe : failures.entrySet()) {
            if (!lastScanErrors.containsKey(fe.getKey())) {
                String m = fe.getValue() != null ? fe.getValue().getMessage() : "unknown";
                lastScanErrors.put(fe.getKey(), m != null ? m : "unknown");
            }
        }
        // If all browsers failed due to infrastructure, propagate
        if (all.isEmpty() && !failures.isEmpty() && successCount == 0) {
            // Check if failures are infrastructure (IOException) not just "not installed"
            long infraFailures = failures.values().stream().filter(t -> t instanceof IOException).count();
            if (infraFailures == failures.size() && infraFailures > 0) {
                Throwable first = failures.values().iterator().next();
                if (first instanceof IOException) throw (IOException) first;
                throw new IOException("All browser scans failed: " + first.getMessage(), first);
            }
        }
        if (cancelled != null && cancelled.get()) {
            throw new java.util.concurrent.CancellationException("Scan cancelled");
        }
        return all;
    }

    /**
     * Scan all browsers individually, calling onProgress(browserName, completedCount, totalCount)
     * after each browser completes. This enables real-time progress UI updates.
     */
    public List<BrowserExtensionRow> scanAllBrowsers(java.util.function.Consumer<String> onProgress) throws IOException {
        return scanAllBrowsers(onProgress, null);
    }

    public List<BrowserExtensionRow> scanAllBrowsers(java.util.function.Consumer<String> onProgress, java.util.concurrent.atomic.AtomicBoolean cancelled) throws IOException {
        List<BrowserExtensionRow> all = new ArrayList<>();
        int total = ALL_BROWSERS.size();
        for (int i = 0; i < total; i++) {
            if (cancelled != null && cancelled.get()) throw new java.util.concurrent.CancellationException("Cancelled");
            String browser = ALL_BROWSERS.get(i);
            all.addAll(scanBrowser(browser, cancelled));
            if (onProgress != null) {
                onProgress.accept(browser + " (" + (i + 1) + "/" + total + ")");
            }
        }
        return all;
    }

    public List<BrowserExtensionRow> scanBrowser(String browser) throws IOException {
        return scanBrowser(browser, null);
    }

    public List<BrowserExtensionRow> scanBrowser(String browser, java.util.concurrent.atomic.AtomicBoolean cancelled) throws IOException {
        List<BrowserExtensionRow> results = new ArrayList<>();
        try {
            Path script = PowerShellScripts.resolve("browser-extensions.ps1");
            List<String> cmd;
            try {
                cmd = ProcessRunner.powershellScript(script.toString(), "-Browser", browser);
            } catch (Exception e) {
                cmd = ProcessRunner.bestPowerShellScript(script.toString(), "-Browser", browser);
            }
            // Try powershell.exe first, fallback to pwsh.exe if not found
            ProcessResult pr;
            try {
                pr = new ProcessRunner(60).run(cmd, 60, cancelled);
            } catch (IOException ioe) {
                if (cancelled != null && cancelled.get()) throw new java.util.concurrent.CancellationException("Cancelled");
                if (cmd.get(0).equalsIgnoreCase("powershell.exe")) {
                    cmd = ProcessRunner.pwshScript(script.toString(), "-Browser", browser);
                    pr = new ProcessRunner(60).run(cmd, 60, cancelled);
                } else {
                    throw ioe;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Scan interrupted", ie);
            }
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
            if (trimmed.isEmpty()) {
                String hex = bytesToHex(stdout.getBytes(StandardCharsets.UTF_8));
                AppLogger.warning("[BrowserExtensionService] EMPTY stdout for " + browser + " (exit=" + exitCode + ") raw_hex=" + hex + " stderr=" + stderr);
                if (exitCode != 0) {
                    throw new IOException("PowerShell failed for " + browser + " (exit=" + exitCode + "): " + stderr);
                }
                return results;
            }
            if ("[]".equals(trimmed)) {
                return results;
            }
            stdout = trimmed;
            // Robust parsing: handle both array and single-object (legacy) outputs
            List<Map<String, Object>> raw;
            try {
                raw = mapper.readValue(stdout, new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception ex) {
                // Fallback: single object instead of array
                if (stdout.trim().startsWith("{")) {
                    Map<String, Object> single = mapper.readValue(stdout, new TypeReference<Map<String, Object>>() {});
                    raw = List.of(single);
                } else {
                    throw ex;
                }
            }
            for (Map<String, Object> entry : raw) {
                try {
                    String id = str(entry, "id");
                    String name = str(entry, "name");
                    String version = str(entry, "version");
                    String description = str(entry, "description");
                    String extBrowser = str(entry, "browser");
                    String extPath = str(entry, "path");
                    String profilePath = str(entry, "profilePath");
                    String installTime = str(entry, "installTime");
                    String permissions = str(entry, "permissions");
                    boolean enabled = true;
                    Object en = entry.get("enabled");
                    if (en instanceof Boolean) enabled = (Boolean) en;
                    results.add(new BrowserExtensionRow(extBrowser, id, name, version,
                            description, enabled, extPath, profilePath, installTime, permissions));
                } catch (Exception e) {
                    AppLogger.warning("Failed to parse extension entry: " + e.getMessage());
                }
            }
        } catch (java.util.concurrent.CancellationException ce) {
            throw ce;
        } catch (IOException ioe) {
            // Infrastructure failure - propagate to caller for proper UX
            AppLogger.warning("Failed to scan browser " + browser + ": " + ioe.getMessage());
            throw ioe;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Scan interrupted", ie);
        } catch (Exception e) {
            AppLogger.warning("Failed to scan browser " + browser + ": " + e.getMessage());
            // Non-infra parse errors return partial results, not throw
        }
        return results;
    }

    public boolean toggleExtension(BrowserExtensionRow ext, boolean enable) {
        return toggleExtension(ext, enable, null);
    }

    public boolean toggleExtension(BrowserExtensionRow ext, boolean enable, java.util.concurrent.atomic.AtomicBoolean cancelled) {
        try {
            if (cancelled != null && cancelled.get()) return false;
            String extId = ext.getExtensionId();
            if (extId == null || extId.isBlank()) return false;

            // Prefer profilePath (added in fix) for accurate profile targeting
            Path profileDir;
            String pp = ext.getProfilePath();
            if (pp != null && !pp.isBlank()) {
                profileDir = Paths.get(pp);
                if (!Files.exists(profileDir)) return false;
            } else {
                // Legacy fallback: derive from path (Extensions dir -> profile)
                String pathStr = ext.getPath();
                if (pathStr == null || pathStr.isBlank()) return false;
                Path extPath = Paths.get(pathStr);
                if (!Files.exists(extPath)) return false;
                profileDir = extPath.getParent();
                if (profileDir == null || !Files.exists(profileDir)) return false;
            }

            Path script = PowerShellScripts.resolve("browser-extensions.ps1");
            List<String> cmd;
            try {
                cmd = ProcessRunner.powershellScript(script.toString(),
                        "-Action", "Toggle",
                        "-ProfilePath", profileDir.toString(),
                        "-ExtId", extId,
                        "-Enable", String.valueOf(enable));
            } catch (Exception e) {
                cmd = ProcessRunner.bestPowerShellScript(script.toString(),
                        "-Action", "Toggle",
                        "-ProfilePath", profileDir.toString(),
                        "-ExtId", extId,
                        "-Enable", String.valueOf(enable));
            }
            ProcessResult pr;
            try {
                pr = new ProcessRunner(30).run(cmd, 30, cancelled);
            } catch (java.util.concurrent.CancellationException ce) {
                throw ce;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException ioe) {
                if (cancelled != null && cancelled.get()) return false;
                if (cmd.get(0).equalsIgnoreCase("powershell.exe")) {
                    cmd = ProcessRunner.pwshScript(script.toString(),
                            "-Action", "Toggle",
                            "-ProfilePath", profileDir.toString(),
                            "-ExtId", extId,
                            "-Enable", String.valueOf(enable));
                    try {
                        pr = new ProcessRunner(30).run(cmd, 30, cancelled);
                    } catch (InterruptedException ie2) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    throw ioe;
                }
            }
            if (cancelled != null && cancelled.get()) return false;
            String stdout = pr.stdout().trim();
            // Handle BOM
            if (stdout.startsWith("\uFEFF")) stdout = stdout.substring(1);
            boolean success = "true".equalsIgnoreCase(stdout);
            if (!success) {
                String err = pr.stderr().trim();
                AppLogger.warning("Toggle PowerShell returned: '" + stdout + "' stderr=" + err);
                // Surface lock error specifically
                if (err.toLowerCase().contains("locked") || err.toLowerCase().contains("browser may be running")) {
                    AppLogger.warning("Toggle blocked by file lock (browser running) for " + ext.getBrowser() + ":" + extId);
                }
            }
            return success;
        } catch (java.util.concurrent.CancellationException ce) {
            AppLogger.info("Toggle cancelled for " + ext.getExtensionId());
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
