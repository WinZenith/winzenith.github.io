package com.sbtools.systeminfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public class SystemInfoService {

    private static final long TIMEOUT_SECONDS = 240;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    private final ProcessRunner processRunner = new ProcessRunner(TIMEOUT_SECONDS);
    private final Object cacheLock = new Object();

    private volatile SystemInfoData cachedData;
    private volatile long cacheTimestamp;

    public SystemInfoData gatherSystemInfo() throws IOException, InterruptedException {
        return gatherSystemInfo(null);
    }

    public SystemInfoData gatherSystemInfo(BiConsumer<String, Double> progressCallback) throws IOException, InterruptedException {
        return gatherSystemInfo(progressCallback, false);
    }

    public SystemInfoData gatherSystemInfo(BiConsumer<String, Double> progressCallback, boolean forceRefresh) throws IOException, InterruptedException {
        return gatherSystemInfo(progressCallback, forceRefresh, null);
    }

    /**
     * Cancellation-aware overload. When cancelledToken.get() returns true the underlying
     * PowerShell process is killed and an InterruptedException is thrown.
     */
    public SystemInfoData gatherSystemInfo(BiConsumer<String, Double> progressCallback, boolean forceRefresh,
                                            java.util.concurrent.atomic.AtomicBoolean cancelledToken)
            throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("System information is only available on Windows.");
        }

        if (!forceRefresh) {
            synchronized (cacheLock) {
                if (cachedData != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS) {
                    if (progressCallback != null) {
                        progressCallback.accept("cached", 1.0);
                    }
                    return cachedData;
                }
            }
        }

        if (progressCallback != null) {
            progressCallback.accept("CPU", 0.1);
        }
        Path script = PowerShellScripts.resolve("system-info.ps1");
        if (progressCallback != null) {
            progressCallback.accept("hardware", 0.2);
        }
        ProcessResult result;
        // Use separate stdout/stderr for system-info so PowerShell warnings/errors
        // never contaminate the JSON stream (B1 fix). run() already supports
        // cancellation polling and timeout, so use it for both branches.
        // Non-interactive flags prevent hangs on prompts; pwsh.exe fallback covers
        // hosts where Windows PowerShell 5.1 was removed (same pattern as
        // DiskHealthService / DefragService / ShredderService).
        try {
            result = runSystemInfoScript(script.toString(), cancelledToken);
        } catch (java.util.concurrent.CancellationException ce) {
            throw new InterruptedException("System info cancelled by user");
        }
        if (progressCallback != null) {
            progressCallback.accept("parsing", 0.9);
        }
        String rawOut = result.stdout();
        String json = (rawOut != null && !rawOut.isBlank()) ? extractJson(rawOut, result.stderr()) : null;
        boolean hasUsableJson = json != null && !json.isBlank() && isValidSystemInfoJson(json);
        if (!result.success() && !hasUsableJson) {
            String combined = result.combinedOutput();
            persistDiagnosticDump(result.stdout(), result.stderr());
            // Include stderr snippet in exception when combined is terse
            String errSnippet = result.stderr() != null ? result.stderr().trim() : "";
            if (!errSnippet.isBlank() && !combined.contains(errSnippet)) {
                combined = combined + "\n" + errSnippet;
            }
            throw new IOException("System info query failed: " + combined);
        }
        if (!hasUsableJson) {
            if (rawOut == null || rawOut.isBlank()) {
                String errOut = result.stderr();
                if (errOut != null && !errOut.isBlank()) {
                    throw new IOException("System info query returned empty stdout. Stderr: " + errOut.trim());
                }
                throw new IOException("System info query returned empty output.");
            }
            // Persist raw for diagnostics
            persistDiagnosticDump(rawOut, result.stderr());
            throw new IOException("System info query returned empty output after stripping. Raw stdout length=" + rawOut.length());
        }
        try {
            // Per-section fault isolation: one malformed section must not discard
            // all other good sections (previously a single readValue failure for
            // the whole SystemInfoData threw everything away).
            SystemInfoData data = parseTolerantly(json);
            synchronized (cacheLock) {
                cachedData = data;
                cacheTimestamp = System.currentTimeMillis();
            }
            if (progressCallback != null) {
                progressCallback.accept("done", 1.0);
            }
            return data;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            AppLogger.error("Failed to parse system info JSON", e);
            persistDiagnosticDump(json, null);
            throw new IOException("Failed to parse system info: " + e.getMessage(), e);
        }
    }

    /**
     * Runs system-info.ps1 with powershell.exe first, falling back to pwsh.exe when
     * the Windows PowerShell host is missing. Only executable-missing errors fall
     * back; real script failures are returned to the caller for parsing/diagnostics.
     */
    private ProcessResult runSystemInfoScript(String scriptPath,
                                              java.util.concurrent.atomic.AtomicBoolean cancelledToken)
            throws IOException, InterruptedException {
        List<List<String>> candidates = List.of(
                ProcessRunner.powershellScriptNonInteractive(scriptPath),
                ProcessRunner.pwshScriptNonInteractive(scriptPath));
        IOException lastMissing = null;
        for (List<String> cmd : candidates) {
            try {
                return processRunner.run(cmd, TIMEOUT_SECONDS, cancelledToken);
            } catch (IOException e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean missing = msg.contains("cannot run program") || msg.contains("no such file") || msg.contains("error=2");
                if (missing && cmd.get(0).equals("powershell.exe")) {
                    AppLogger.warning("SystemInfo: powershell.exe not found, trying pwsh.exe");
                    lastMissing = e;
                    continue;
                }
                throw e;
            }
        }
        if (lastMissing != null) throw lastMissing;
        throw new IOException("Failed to run system info: no PowerShell executable");
    }

    /**
     * Tolerant root parser. Each top-level section is converted independently;
     * a failure yields {@code null} for that section plus a warning entry instead
     * of failing the entire payload. Missing/null sections stay {@code null}
     * (legal — e.g. battery on desktops); only genuine conversion errors warn.
     */
    static SystemInfoData parseTolerantly(String json) throws IOException {
        JsonNode root;
        try {
            root = JsonMapper.mapper().readTree(json);
        } catch (Exception e) {
            throw new IOException("Failed to parse system info: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IOException("Failed to parse system info: root is not a JSON object");
        }
        List<String> warnings = new ArrayList<>();
        JsonNode psWarnings = root.get("warnings");
        if (psWarnings != null && psWarnings.isArray()) {
            for (JsonNode w : psWarnings) {
                if (w.isTextual() && !w.asText().isBlank()) warnings.add(w.asText());
                else if (!w.isNull()) warnings.add(w.toString());
            }
        }

        CpuInfo cpu = readSection(root, "cpu", CpuInfo.class, warnings);
        List<GpuInfo> gpu = readListSection(root, "gpu", GpuInfo.class, warnings);
        RamInfo ram = readSection(root, "ram", RamInfo.class, warnings);
        OsInfo os = readSection(root, "os", OsInfo.class, warnings);
        StorageInfo storage = readSection(root, "storage", StorageInfo.class, warnings);
        MotherboardInfo motherboard = readSection(root, "motherboard", MotherboardInfo.class, warnings);
        BiosInfo bios = readSection(root, "bios", BiosInfo.class, warnings);
        List<OtherDevice> others = readListSection(root, "others", OtherDevice.class, warnings);
        List<NetworkAdapterInfo> networkAdapters = readListSection(root, "networkAdapters", NetworkAdapterInfo.class, warnings);
        List<AudioDeviceInfo> audioDevices = readListSection(root, "audioDevices", AudioDeviceInfo.class, warnings);
        BatteryInfo battery = readSectionLenient(root, "battery", BatteryInfo.class, warnings, true);
        List<TemperatureInfo> temperatures = readListSection(root, "temperatures", TemperatureInfo.class, warnings);
        List<UsbDeviceInfo> usbDevices = readListSection(root, "usbDevices", UsbDeviceInfo.class, warnings);
        List<MonitorInfo> monitors = readListSection(root, "monitors", MonitorInfo.class, warnings);
        List<PrinterInfo> printers = readListSection(root, "printers", PrinterInfo.class, warnings);
        String version = null;
        try {
            JsonNode v = root.get("version");
            if (v != null && !v.isNull()) version = v.isTextual() ? v.asText() : v.toString();
        } catch (Exception e) {
            AppLogger.warning("SystemInfo: unreadable 'version' section: " + e.getMessage());
        }

        return new SystemInfoData(cpu, gpu, ram, os, storage, motherboard, bios, others,
                networkAdapters, audioDevices, battery, temperatures, usbDevices, monitors,
                printers, version, warnings);
    }

    private static <T> T readSection(JsonNode root, String key, Class<T> type, List<String> warnings) {
        return readSectionLenient(root, key, type, warnings, false);
    }

    private static <T> T readSectionLenient(JsonNode root, String key, Class<T> type,
                                            List<String> warnings, boolean nullIsOk) {
        JsonNode node = root.get(key);
        if (node == null || node.isNull()) return null;
        try {
            return JsonMapper.mapper().treeToValue(node, type);
        } catch (Exception e) {
            AppLogger.warning("SystemInfo: unreadable '" + key + "' section, skipped: " + e.getMessage());
            if (!nullIsOk || !"battery".equals(key)) {
                warnings.add("Section '" + key + "' was unreadable and was skipped.");
            }
            return null;
        }
    }

    private static <T> List<T> readListSection(JsonNode root, String key, Class<T> elementType,
                                               List<String> warnings) {
        JsonNode node = root.get(key);
        if (node == null || node.isNull()) return null;
        if (!node.isArray()) {
            AppLogger.warning("SystemInfo: '" + key + "' is not an array, skipped.");
            warnings.add("Section '" + key + "' was unreadable and was skipped.");
            return null;
        }
        try {
            return JsonMapper.mapper().convertValue(node,
                    JsonMapper.mapper().getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (Exception e) {
            // Last resort: salvage individually readable elements.
            List<T> salvaged = new ArrayList<>();
            int failed = 0;
            for (JsonNode el : node) {
                try {
                    salvaged.add(JsonMapper.mapper().treeToValue(el, elementType));
                } catch (Exception ignored) {
                    failed++;
                }
            }
            if (!salvaged.isEmpty() || failed == 0) {
                if (failed > 0) {
                    warnings.add("Section '" + key + "': " + failed + " unreadable entr"
                            + (failed == 1 ? "y" : "ies") + " skipped.");
                }
                return salvaged;
            }
            AppLogger.warning("SystemInfo: unreadable '" + key + "' section, skipped: " + e.getMessage());
            warnings.add("Section '" + key + "' was unreadable and was skipped.");
            return null;
        }
    }

    /** Portable-aware diagnostic dump (portable/logs when available, else LOCALAPPDATA). */
    private static void persistDiagnosticDump(String stdout, String stderr) {
        try {
            Path diag = diagnosticPath();
            Files.createDirectories(diag.getParent());
            String dump = stdout != null ? stdout : "";
            if (stderr != null && !stderr.isBlank()) {
                dump += "\n---STDERR---\n" + stderr;
            }
            Files.writeString(diag, dump, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private static Path diagnosticPath() {
        try {
            Path portable = AppPaths.portableLogsDir();
            if (portable != null) return portable.resolve("system-info-last.json");
        } catch (Exception ignored) {}
        return AppPaths.logsDir().resolve("system-info-last.json");
    }

    public void invalidateCache() {
        synchronized (cacheLock) {
            cachedData = null;
            cacheTimestamp = 0;
        }
    }

    /**
     * Robust JSON extraction: PowerShell may emit non-JSON preamble before the
     * single Compressed JSON object (despite -NoProfile, enterprise transcription
     * or warnings can still appear on stdout if streams were merged in older builds).
     * We now keep streams separate (see gatherSystemInfo), but still defend against
     * any leading/trailing noise by locating the outermost JSON object that parses.
     */
    private static String extractJson(String rawOut, String stderr) {
        if (rawOut == null) return null;
        String s = rawOut.replace("﻿", "").trim();
        if (s.isEmpty()) return s;
        // Fast path: whole output is already valid JSON containing expected keys
        if (isValidSystemInfoJson(s)) {
            return s;
        }
        // Line-based reverse scan: script emits one Compressed JSON line; any preamble is line-oriented.
        String[] lines = s.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            if (line.charAt(0) != '{') continue;
            // Quick filter: must contain at least one top-level key
            if (!line.contains("\"version\"") && !line.contains("\"cpu\"") && !line.contains("\"os\"")) continue;
            if (isValidSystemInfoJson(line)) {
                return line;
            }
        }
        // Fallback: find first '{' to last '}' substring and validate
        int first = s.indexOf('{');
        int last = s.lastIndexOf('}');
        if (first >= 0 && last > first) {
            String candidate = s.substring(first, last + 1).trim();
            if (isValidSystemInfoJson(candidate)) {
                return candidate;
            }
            // Try balanced-brace scan from each '{' position (handles preamble containing braces)
            for (int start = first; start >= 0; start = s.indexOf('{', start + 1)) {
                if (start < 0) break;
                String bal = extractBalancedJson(s, start);
                if (bal != null && isValidSystemInfoJson(bal)) {
                    return bal;
                }
            }
        }
        // Last resort: return trimmed raw (will fail parse and be logged)
        return s;
    }

    private static boolean isValidSystemInfoJson(String json) {
        if (json == null || json.isBlank()) return false;
        String t = json.trim();
        if (!t.startsWith("{") || !t.endsWith("}")) return false;
        try {
            var node = JsonMapper.mapper().readTree(t);
            if (!node.isObject()) return false;
            // Accept if it has any of the top-level expected keys; version is most reliable
            return node.has("version") || node.has("cpu") || node.has("os") || node.has("ram") || node.has("storage");
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String extractBalancedJson(String s, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else {
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return s.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }
}
