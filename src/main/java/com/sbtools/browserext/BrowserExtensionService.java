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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public class BrowserExtensionService {

    /**
     * Browser names in scan order. Derived from the pluggable
     * {@link BrowserRegistry} (bundled {@code browser-catalog.json} + optional
     * portable override). Falls back to the hard-coded 11 when the catalog
     * cannot be loaded, so existing callers never break.
     */
    public static final List<String> ALL_BROWSERS = resolveAllBrowsers();

    private static List<String> resolveAllBrowsers() {
        try {
            List<String> names = BrowserRegistry.browserNames();
            if (names != null && !names.isEmpty()) return List.copyOf(names);
        } catch (Exception ignored) {
        }
        return List.of(
                "Chrome", "Chrome Canary",
                "Edge", "Edge Beta", "Edge Dev", "Edge Canary",
                "Firefox", "Brave", "Opera", "Opera GX", "Vivaldi"
        );
    }

    /** Browsers with hard-coded scan paths in the PS script (legacy path). Others use -UserDataPath. */
    private static final java.util.Set<String> LEGACY_PS_BROWSERS = java.util.Set.of(
            "Chrome", "Chrome Canary", "Edge", "Edge Beta", "Edge Dev", "Edge Canary",
            "Firefox", "Brave", "Opera", "Opera GX", "Vivaldi");

    private static final Map<String, String> BROWSER_PATHS = resolveBrowserPaths();

    private static Map<String, String> resolveBrowserPaths() {
        try {
            Map<String, String> m = new LinkedHashMap<>();
            for (BrowserDefinition d : BrowserRegistry.definitions()) {
                if (d.name() != null && d.userDataOrEmpty() != null) {
                    m.put(d.name(), d.userDataOrEmpty());
                }
            }
            if (!m.isEmpty()) return Map.copyOf(m);
        } catch (Exception ignored) {
        }
        return Map.ofEntries(
                Map.entry("Chrome", "%LOCALAPPDATA%\\Google\\Chrome\\User Data"),
                Map.entry("Chrome Canary", "%LOCALAPPDATA%\\Google\\Chrome SxS\\User Data"),
                Map.entry("Edge", "%LOCALAPPDATA%\\Microsoft\\Edge\\User Data"),
                Map.entry("Edge Beta", "%LOCALAPPDATA%\\Microsoft\\Edge Beta\\User Data"),
                Map.entry("Edge Dev", "%LOCALAPPDATA%\\Microsoft\\Edge Dev\\User Data"),
                Map.entry("Edge Canary", "%LOCALAPPDATA%\\Microsoft\\Edge SxS\\User Data"),
                Map.entry("Firefox", "%APPDATA%\\Mozilla\\Firefox\\Profiles"),
                Map.entry("Brave", "%LOCALAPPDATA%\\BraveSoftware\\Brave-Browser\\User Data"),
                Map.entry("Opera", "%APPDATA%\\Opera Software\\Opera Stable"),
                Map.entry("Opera GX", "%APPDATA%\\Opera Software\\Opera GX Stable"),
                Map.entry("Vivaldi", "%LOCALAPPDATA%\\Vivaldi\\User Data")
        );
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> lastScanErrors = new java.util.concurrent.ConcurrentHashMap<>();

    public Map<String, String> getLastScanErrors() {
        return Map.copyOf(lastScanErrors);
    }

    /** Expanded user-data / profiles root for a catalog browser label, or "". */
    public static String userDataDirFor(String browser) {
        String template = BROWSER_PATHS.get(browser);
        if (template == null) return "";
        return expandEnv(template);
    }

    private static String expandEnv(String template) {
        if (template == null) return "";
        String local = System.getenv("LOCALAPPDATA");
        String app = System.getenv("APPDATA");
        String pf = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        return template.replace("%LOCALAPPDATA%", local != null ? local : "")
                .replace("%APPDATA%", app != null ? app : "")
                .replace("%ProgramFiles%", pf != null ? pf : "")
                .replace("%ProgramFiles(x86)%", pf86 != null ? pf86 : "");
    }

    private static List<String> wellKnownExePaths(String browser) {
        try {
            BrowserDefinition def = BrowserRegistry.find(browser);
            if (def != null && !def.exesOrEmpty().isEmpty()) return def.exesOrEmpty();
        } catch (Exception ignored) {
        }
        return switch (browser) {
            case "Chrome" -> List.of(
                    "%LOCALAPPDATA%\\Google\\Chrome\\Application\\chrome.exe",
                    "%ProgramFiles%\\Google\\Chrome\\Application\\chrome.exe",
                    "%ProgramFiles(x86)%\\Google\\Chrome\\Application\\chrome.exe");
            case "Chrome Canary" -> List.of(
                    "%LOCALAPPDATA%\\Google\\Chrome SxS\\Application\\chrome.exe");
            case "Edge" -> List.of(
                    "%ProgramFiles%\\Microsoft\\Edge\\Application\\msedge.exe",
                    "%ProgramFiles(x86)%\\Microsoft\\Edge\\Application\\msedge.exe");
            case "Edge Beta" -> List.of(
                    "%ProgramFiles%\\Microsoft\\Edge Beta\\Application\\msedge.exe",
                    "%ProgramFiles(x86)%\\Microsoft\\Edge Beta\\Application\\msedge.exe");
            case "Edge Dev" -> List.of(
                    "%ProgramFiles%\\Microsoft\\Edge Dev\\Application\\msedge.exe",
                    "%ProgramFiles(x86)%\\Microsoft\\Edge Dev\\Application\\msedge.exe");
            case "Edge Canary" -> List.of(
                    "%LOCALAPPDATA%\\Microsoft\\Edge SxS\\Application\\msedge.exe");
            case "Firefox" -> List.of(
                    "%ProgramFiles%\\Mozilla Firefox\\firefox.exe",
                    "%ProgramFiles(x86)%\\Mozilla Firefox\\firefox.exe",
                    "%LOCALAPPDATA%\\Mozilla Firefox\\firefox.exe");
            case "Brave" -> List.of(
                    "%ProgramFiles%\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                    "%ProgramFiles(x86)%\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                    "%LOCALAPPDATA%\\BraveSoftware\\Brave-Browser\\Application\\brave.exe");
            case "Opera" -> List.of(
                    "%LOCALAPPDATA%\\Programs\\Opera\\opera.exe",
                    "%APPDATA%\\Opera Software\\Opera Stable\\opera.exe");
            case "Opera GX" -> List.of(
                    "%LOCALAPPDATA%\\Programs\\Opera GX\\opera.exe");
            case "Vivaldi" -> List.of(
                    "%LOCALAPPDATA%\\Vivaldi\\Application\\vivaldi.exe",
                    "%ProgramFiles%\\Vivaldi\\Application\\vivaldi.exe");
            default -> List.of();
        };
    }

    /**
     * Checks if a browser looks installed using BOTH profile-data and executable evidence.
     * Data dir alone is unreliable (leftover data after uninstall = false positive;
     * fresh install never launched = false negative), so well-known exe paths are
     * checked as well. Either signal counts as installed.
     * NOTE: intentionally file-existence checks only — no subprocess (e.g. where.exe),
     * because this runs on the FX thread via buildStatusText and must never block (B6).
     */
    public boolean checkBrowserInstalled(String browser) {
        String template = BROWSER_PATHS.get(browser);
        boolean dataFound = false;
        if (template != null) {
            String resolved = expandEnv(template);
            if (!resolved.isBlank()) {
                try {
                    if (Files.exists(Paths.get(resolved))) dataFound = true;
                } catch (Exception ignored) {
                }
            }
            if (!dataFound && "Firefox".equals(browser)) {
                String base = expandEnv("%APPDATA%\\Mozilla\\Firefox");
                try {
                    if (!base.isBlank() && Files.exists(Paths.get(base))) dataFound = true;
                    if (!dataFound) {
                        String ini = expandEnv("%APPDATA%\\Mozilla\\Firefox\\profiles.ini");
                        if (!ini.isBlank() && Files.exists(Paths.get(ini))) dataFound = true;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (dataFound) return true;
        // No profile data — fall back to executable evidence (fresh install case).
        try {
            for (String candidate : wellKnownExePaths(browser)) {
                String expanded = expandEnv(candidate);
                if (!expanded.isBlank() && Files.exists(Paths.get(expanded))) return true;
            }
        } catch (Exception e) {
            return dataFound;
        }
        return false;
    }

    /**
     * True when profile data exists for the browser (used to distinguish
     * "installed but no scannable profile data" from "not installed").
     */
    public boolean hasProfileData(String browser) {
        String template = BROWSER_PATHS.get(browser);
        if (template == null) return false;
        String resolved = expandEnv(template);
        if (!resolved.isBlank()) {
            try {
                if (Files.exists(Paths.get(resolved))) return true;
            } catch (Exception ignored) {
            }
        }
        if ("Firefox".equals(browser)) {
            try {
                String base = expandEnv("%APPDATA%\\Mozilla\\Firefox");
                if (!base.isBlank() && Files.exists(Paths.get(base))) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
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

    /**
     * Determinate-progress overload: reports (browser, done, total) after each
     * browser completes so the UI can show a real progress bar. Additive only —
     * the {@link Consumer} overload above delegates here.
     */
    public interface ScanProgress {
        void onBrowserDone(String browser, int done, int total);
    }

    public List<BrowserExtensionRow> scanAllBrowsersParallel(
            ExecutorService pool,
            ScanProgress progress,
            java.util.concurrent.atomic.AtomicBoolean cancelled) throws IOException {
        Consumer<String> legacy = null;
        if (progress != null) {
            java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
            int total = ALL_BROWSERS.size();
            legacy = browser -> progress.onBrowserDone(browser, done.incrementAndGet(), total);
        }
        return scanAllBrowsersParallel(pool, legacy, cancelled);
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
            // Pluggable browsers not hardcoded in the PS script are scanned via
            // explicit -UserDataPath/-Engine/-BrowserLabel (additive, legacy names unchanged).
            BrowserDefinition def = null;
            try {
                def = BrowserRegistry.find(browser);
            } catch (Exception ignored) {
            }
            boolean useCustomPath = def != null && !LEGACY_PS_BROWSERS.contains(browser)
                    && def.userDataOrEmpty() != null && !def.userDataOrEmpty().isBlank();
            try {
                if (useCustomPath) {
                    cmd = ProcessRunner.powershellScript(script.toString(),
                            "-Browser", browser,
                            "-UserDataPath", def.userDataOrEmpty(),
                            "-Engine", def.engineOrDefault(),
                            "-BrowserLabel", browser);
                } else {
                    cmd = ProcessRunner.powershellScript(script.toString(), "-Browser", browser);
                }
            } catch (Exception e) {
                if (useCustomPath) {
                    cmd = ProcessRunner.bestPowerShellScript(script.toString(),
                            "-Browser", browser,
                            "-UserDataPath", def.userDataOrEmpty(),
                            "-Engine", def.engineOrDefault(),
                            "-BrowserLabel", browser);
                } else {
                    cmd = ProcessRunner.bestPowerShellScript(script.toString(), "-Browser", browser);
                }
            }
            // Try powershell.exe first, fallback to pwsh.exe if not found
            ProcessResult pr;
            try {
                pr = new ProcessRunner(60).run(cmd, 60, cancelled);
            } catch (IOException ioe) {
                if (cancelled != null && cancelled.get()) throw new java.util.concurrent.CancellationException("Cancelled");
                if (cmd.get(0).equalsIgnoreCase("powershell.exe")) {
                    List<String> fallback;
                    if (useCustomPath) {
                        fallback = ProcessRunner.pwshScript(script.toString(),
                                "-Browser", browser,
                                "-UserDataPath", def.userDataOrEmpty(),
                                "-Engine", def.engineOrDefault(),
                                "-BrowserLabel", browser);
                    } else {
                        fallback = ProcessRunner.pwshScript(script.toString(), "-Browser", browser);
                    }
                    cmd = fallback;
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
                    if (extBrowser == null || extBrowser.isBlank()) extBrowser = browser;
                    String extPath = str(entry, "path");
                    String profilePath = str(entry, "profilePath");
                    String profileName = str(entry, "profileName");
                    String installTime = str(entry, "installTime");
                    String permissions = str(entry, "permissions");
                    boolean enabled = true;
                    Object en = entry.get("enabled");
                    if (en instanceof Boolean) enabled = (Boolean) en;
                    boolean managed = false;
                    Object mg = entry.get("managed");
                    if (mg instanceof Boolean) managed = (Boolean) mg;
                    String installSource = str(entry, "installSource");
                    results.add(new BrowserExtensionRow(extBrowser, id, name, version,
                            description, enabled, extPath, profilePath, profileName, installTime, permissions,
                            managed, installSource));
                } catch (Exception e) {
                    AppLogger.warning("Failed to parse extension entry: " + e.getMessage());
                }
            }
        } catch (java.util.concurrent.CancellationException ce) {
            throw ce;
        } catch (IOException ioe) {
            String msg = ioe.getMessage() != null ? ioe.getMessage() : "scan failed";
            lastScanErrors.put(browser, msg);
            AppLogger.warning("Failed to scan browser " + browser + ": " + msg);
            throw ioe;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Scan interrupted", ie);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "scan failed";
            AppLogger.warning("Failed to scan browser " + browser + ": " + msg);
            lastScanErrors.put(browser, msg);
            throw new IOException("Scan failed for " + browser + ": " + msg, e);
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
            // Policy/default/system extensions are re-enforced by the browser:
            // refuse instead of reporting false success.
            if (ext.isManaged() || ext.isOrphaned()) {
                AppLogger.warning("Refusing toggle of " + (ext.isOrphaned() ? "orphaned" : "managed")
                        + " extension " + ext.getBrowser() + ":"
                        + extId + " (source=" + ext.getInstallSource() + ")");
                return false;
            }

            // Prefer profilePath (added in fix) for accurate profile targeting
            Path profileDir;
            String pp = ext.getProfilePath();
            if (pp != null && !pp.isBlank()) {
                profileDir = Paths.get(pp);
                if (!Files.isDirectory(profileDir)) return false;
            } else {
                // Legacy fallback: walk up from path until a profile marker is
                // found (Preferences / Secure Preferences / extensions.json).
                // The old single-parent assumption broke when path pointed at
                // the per-extension dir instead of the Extensions dir (B1).
                String pathStr = ext.getPath();
                if (pathStr == null || pathStr.isBlank()) return false;
                Path cursor;
                try {
                    cursor = Paths.get(pathStr);
                } catch (Exception e) {
                    return false;
                }
                profileDir = null;
                for (int depth = 0; depth < 5 && cursor != null; depth++) {
                    try {
                        if (Files.exists(cursor.resolve("Preferences"))
                                || Files.exists(cursor.resolve("Secure Preferences"))
                                || Files.exists(cursor.resolve("extensions.json"))) {
                            profileDir = cursor;
                            break;
                        }
                        if ("Extensions".equalsIgnoreCase(cursor.getFileName() != null
                                ? cursor.getFileName().toString() : "")) {
                            Path parent = cursor.getParent();
                            if (parent != null && Files.exists(parent)) {
                                profileDir = parent;
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    cursor = cursor.getParent();
                }
                if (profileDir == null || !Files.isDirectory(profileDir)) return false;
            }

            return BrowserProfileToggle.toggle(profileDir, extId, enable, cancelled);
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

    /**
     * Expected process image for a browser (e.g. {@code chrome.exe}).
     * Registry-driven; falls back to the legacy switch for built-ins and to
     * the file name of the first known exe path for pluggable custom browsers.
     * Returns "" only when nothing is known — callers must treat "" as
     * "unknown" (warn), never as "not running".
     */
    public static String expectedExeFor(String browser) {
        try {
            BrowserDefinition def = BrowserRegistry.find(browser);
            if (def != null) {
                if (def.processNameOrEmpty() != null && !def.processNameOrEmpty().isBlank()) {
                    return def.processNameOrEmpty().toLowerCase();
                }
                // Pluggable browser without processName: derive from exe paths.
                for (String exe : def.exesOrEmpty()) {
                    String fileName = fileNameOf(exe);
                    if (fileName != null && fileName.toLowerCase().endsWith(".exe")) {
                        return fileName.toLowerCase();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return switch (browser == null ? "" : browser) {
            case "Chrome", "Chrome Canary" -> "chrome.exe";
            case "Edge", "Edge Beta", "Edge Dev", "Edge Canary" -> "msedge.exe";
            case "Firefox" -> "firefox.exe";
            case "Brave" -> "brave.exe";
            case "Opera", "Opera GX" -> "opera.exe";
            case "Vivaldi" -> "vivaldi.exe";
            default -> "";
        };
    }

    private static String fileNameOf(String path) {
        if (path == null || path.isBlank()) return null;
        try {
            String expanded = expandEnv(path);
            String candidate = (expanded != null && !expanded.isBlank()) ? expanded : path;
            int slash = Math.max(candidate.lastIndexOf('\\'), candidate.lastIndexOf('/'));
            String leaf = slash >= 0 ? candidate.substring(slash + 1) : candidate;
            leaf = leaf.trim();
            // Strip any trailing args/quotes from catalog typos.
            int space = leaf.indexOf(' ');
            if (space > 0) leaf = leaf.substring(0, space);
            leaf = leaf.replace("\"", "");
            return leaf.isBlank() ? null : leaf;
        } catch (Exception e) {
            return null;
        }
    }

    /** Store URL for an extension id, or "" when the browser has no known store. */
    public static String storeUrlFor(String browser, String extensionId) {
        try {
            BrowserDefinition def = BrowserRegistry.find(browser);
            if (def != null) return def.storeUrlFor(extensionId);
        } catch (Exception ignored) {
        }
        return "";
    }

    /** {@code browser|profilePath|extensionId} — unique across profiles. */
    public static String ignoredKey(BrowserExtensionRow row) {
        if (row == null) return "";
        String profile = row.getProfilePath() != null ? row.getProfilePath() : "";
        return nvl(row.getBrowser()) + "|" + profile + "|" + nvl(row.getExtensionId());
    }

    /** Legacy {@code browser:extensionId} still accepted when reading settings. */
    public static String legacyIgnoredKey(BrowserExtensionRow row) {
        if (row == null) return "";
        return nvl(row.getBrowser()) + ":" + nvl(row.getExtensionId());
    }

    public static boolean matchesIgnoredId(BrowserExtensionRow row, String id) {
        return row != null && id != null && !id.isBlank()
                && (id.equals(ignoredKey(row)) || id.equals(legacyIgnoredKey(row)));
    }

    /**
     * Persist ignored IDs without dropping entries whose rows are not in the
     * current scan. Loaded rows win: ignored → qualified key, un-ignored → drop.
     */
    public static List<String> mergeIgnoredIds(List<String> persisted, List<BrowserExtensionRow> rows) {
        List<BrowserExtensionRow> safeRows = rows == null ? List.of() : rows;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (persisted != null) {
            for (String id : persisted) {
                if (id == null || id.isBlank()) continue;
                BrowserExtensionRow match = null;
                for (BrowserExtensionRow row : safeRows) {
                    if (matchesIgnoredId(row, id)) {
                        match = row;
                        break;
                    }
                }
                if (match == null) {
                    out.add(id);
                } else if (match.isIgnored()) {
                    out.add(ignoredKey(match));
                }
            }
        }
        for (BrowserExtensionRow row : safeRows) {
            if (row != null && row.isIgnored()) out.add(ignoredKey(row));
        }
        return List.copyOf(out);
    }

    /** Persisted ignore IDs that do not match any loaded row (still shown in Manage Ignored). */
    public static List<String> unmatchedIgnoredIds(List<String> persisted, List<BrowserExtensionRow> rows) {
        if (persisted == null || persisted.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String id : persisted) {
            if (id == null || id.isBlank()) continue;
            boolean matched = false;
            if (rows != null) {
                for (BrowserExtensionRow row : rows) {
                    if (matchesIgnoredId(row, id)) {
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) out.add(id);
        }
        return List.copyOf(out);
    }

    /** One-line label for a persisted ignore key when the row is not loaded. */
    public static String describeIgnoredId(String id) {
        if (id == null || id.isBlank()) return "";
        String[] pipe = id.split("\\|", 3);
        if (pipe.length == 3) {
            String browser = pipe[0];
            String profile = pipe[1];
            String extId = pipe[2];
            String leaf = profile;
            try {
                if (profile != null && !profile.isBlank()) {
                    java.nio.file.Path p = Paths.get(profile);
                    if (p.getFileName() != null) leaf = p.getFileName().toString();
                }
            } catch (Exception ignored) {
            }
            if (leaf != null && !leaf.isBlank()) {
                return "• " + extId + " (" + browser + " — " + leaf + ")";
            }
            return "• " + extId + " (" + browser + ")";
        }
        int colon = id.indexOf(':');
        if (colon > 0 && colon < id.length() - 1) {
            return "• " + id.substring(colon + 1) + " (" + id.substring(0, colon) + ")";
        }
        return "• " + id;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /**
     * Lists Preferences/extensions.json backups created during enable/disable
     * ({@code Preferences.bak.*}, {@code Secure Preferences.bak.*},
     * {@code extensions.json.bak.*}) newest-first. Returns empty list when the
     * profile dir is missing — never throws.
     */
    public static List<Path> listProfileBackups(String profilePath) {
        List<Path> out = new ArrayList<>();
        if (profilePath == null || profilePath.isBlank()) return out;
        try {
            Path dir = Paths.get(profilePath);
            if (!Files.isDirectory(dir)) return out;
            try (var stream = Files.list(dir)) {
                stream.filter(p -> {
                    String n = p.getFileName().toString();
                    return n.startsWith("Preferences.bak.") || n.startsWith("Secure Preferences.bak.")
                            || n.startsWith("extensions.json.bak.");
                }).forEach(out::add);
            }
            out.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (Exception e) {
                    return 0;
                }
            });
        } catch (Exception e) {
            AppLogger.warning("Failed to list profile backups: " + e.getMessage());
        }
        return out;
    }

    /**
     * Backups written in one toggle share the {@code .bak.} suffix. Restoring
     * one file restores that whole snapshot (Preferences and Secure Preferences
     * together) so a single-file restore cannot leave {@code super_mac} stripped.
     */
    static List<Path> pairedBackups(Path backupFile) {
        List<Path> out = new ArrayList<>();
        if (backupFile == null || backupFile.getFileName() == null) return out;
        String name = backupFile.getFileName().toString();
        int bakIdx = name.indexOf(".bak.");
        Path parent = backupFile.getParent();
        if (bakIdx <= 0 || parent == null) {
            if (Files.isRegularFile(backupFile)) out.add(backupFile);
            return out;
        }
        String suffix = name.substring(bakIdx);
        for (String liveName : List.of("Secure Preferences", "Preferences", "extensions.json")) {
            Path sibling = parent.resolve(liveName + suffix);
            if (Files.isRegularFile(sibling)) out.add(sibling);
        }
        if (out.isEmpty() && Files.isRegularFile(backupFile)) out.add(backupFile);
        return out;
    }

    static String liveNameFromBackup(Path backupFile) {
        if (backupFile == null || backupFile.getFileName() == null) return "";
        String name = backupFile.getFileName().toString();
        int bakIdx = name.indexOf(".bak.");
        if (bakIdx <= 0) return name;
        return name.substring(0, bakIdx);
    }

    /**
     * Restores a profile backup snapshot over the live files (atomic move).
     * Same-suffix Preferences / Secure Preferences / extensions.json backups
     * are restored together. Returns true on success. Caller must ensure the
     * browser is closed first.
     */
    public static boolean restoreProfileBackup(Path backupFile) {
        if (backupFile == null || !Files.isRegularFile(backupFile)) return false;
        if (!BrowserProfileToggle.beginMutation()) {
            AppLogger.warning("Refusing restore: another browser-profile write is in progress");
            return false;
        }
        List<Path> tmps = new ArrayList<>();
        List<Path> undos = new ArrayList<>();
        List<Path> lives = new ArrayList<>();
        boolean success = false;
        try {
            List<Path> set = pairedBackups(backupFile);
            if (set.isEmpty()) return false;
            Path parent = backupFile.getParent();
            if (parent == null || !Files.isDirectory(parent)) return false;
            for (Path bak : set) {
                String liveName = liveNameFromBackup(bak);
                if (!BrowserProfileToggle.isAllowedLiveName(liveName)
                        || !BrowserProfileToggle.backupLooksValid(bak, liveName)) {
                    AppLogger.warning("Refusing restore of invalid backup: " + bak);
                    return false;
                }
                Path live = parent.resolve(liveName);
                if (Files.isRegularFile(live) && BrowserProfileToggle.isLocked(live)) {
                    AppLogger.warning("Refusing restore: live file is locked: " + live);
                    return false;
                }
            }
            for (Path bak : set) {
                String liveName = liveNameFromBackup(bak);
                Path live = parent.resolve(liveName);
                Path undo = null;
                if (Files.isRegularFile(live)) {
                    undo = live.resolveSibling("." + liveName + ".undo.tmp");
                    Files.copy(live, undo, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                undos.add(undo);
                lives.add(live);
                Path tmp = live.resolveSibling("." + liveName + ".restore.tmp");
                tmps.add(tmp);
                Files.copy(bak, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, live, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, live, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                if (!BrowserProfileToggle.backupLooksValid(live, liveName)) {
                    AppLogger.warning("Restore verification failed for " + live);
                    undoRestores(undos, lives);
                    return false;
                }
            }
            AppLogger.info("Restored browser profile backup set (" + set.size() + ") from " + backupFile);
            success = true;
            return true;
        } catch (Exception e) {
            AppLogger.warning("Failed to restore profile backup: " + e.getMessage());
            undoRestores(undos, lives);
            return false;
        } finally {
            for (Path tmp : tmps) {
                try {
                    if (tmp != null) Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
            }
            if (success) {
                for (Path undo : undos) {
                    try {
                        if (undo != null) Files.deleteIfExists(undo);
                    } catch (Exception ignored) {
                    }
                }
            }
            BrowserProfileToggle.endMutation();
        }
    }

    private static void undoRestores(List<Path> undos, List<Path> lives) {
        if (undos == null) return;
        for (int i = undos.size() - 1; i >= 0; i--) {
            Path live = lives != null && i < lives.size() ? lives.get(i) : null;
            restoreUndo(undos.get(i), live);
        }
    }

    private static void restoreUndo(Path undo, Path live) {
        if (undo == null || live == null || !Files.isRegularFile(undo)) return;
        try {
            try {
                Files.move(undo, live, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(undo, live, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to put original live file back after restore failure: " + e.getMessage());
        }
    }
}
