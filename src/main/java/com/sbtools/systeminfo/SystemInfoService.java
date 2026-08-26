package com.sbtools.systeminfo;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Path;
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
        try {
            if (cancelledToken != null) {
                result = processRunner.run(
                        ProcessRunner.powershellScript(script.toString()), TIMEOUT_SECONDS, cancelledToken);
            } else {
                result = processRunner.run(ProcessRunner.powershellScript(script.toString()), TIMEOUT_SECONDS, null);
            }
        } catch (java.util.concurrent.CancellationException ce) {
            throw new InterruptedException("System info cancelled by user");
        }
        if (progressCallback != null) {
            progressCallback.accept("parsing", 0.9);
        }
        if (!result.success()) {
            String combined = result.combinedOutput();
            // Persist raw output for diagnostics when available
            try {
                Path diag = AppPaths.logsDir().resolve("system-info-last.json");
                java.nio.file.Files.createDirectories(diag.getParent());
                String dump = result.stdout() != null ? result.stdout() : "";
                if (result.stderr() != null && !result.stderr().isBlank()) {
                    dump += "\n---STDERR---\n" + result.stderr();
                }
                java.nio.file.Files.writeString(diag, dump, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
            // Include stderr snippet in exception when combined is terse
            String errSnippet = result.stderr() != null ? result.stderr().trim() : "";
            if (!errSnippet.isBlank() && !combined.contains(errSnippet)) {
                combined = combined + "\n" + errSnippet;
            }
            throw new IOException("System info query failed: " + combined);
        }
        String rawOut = result.stdout();
        if (rawOut == null || rawOut.isBlank()) {
            String errOut = result.stderr();
            if (errOut != null && !errOut.isBlank()) {
                throw new IOException("System info query returned empty stdout. Stderr: " + errOut.trim());
            }
            throw new IOException("System info query returned empty output.");
        }
        String json = extractJson(rawOut, result.stderr());
        if (json == null || json.isBlank()) {
            // Persist raw for diagnostics
            try {
                Path diag = AppPaths.logsDir().resolve("system-info-last.json");
                java.nio.file.Files.createDirectories(diag.getParent());
                String dump = rawOut;
                if (result.stderr() != null && !result.stderr().isBlank()) dump += "\n---STDERR---\n" + result.stderr();
                java.nio.file.Files.writeString(diag, dump, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
            throw new IOException("System info query returned empty output after stripping. Raw stdout length=" + rawOut.length());
        }
        try {
            SystemInfoData data = JsonMapper.mapper().readValue(json, SystemInfoData.class);
            synchronized (cacheLock) {
                cachedData = data;
                cacheTimestamp = System.currentTimeMillis();
            }
            if (progressCallback != null) {
                progressCallback.accept("done", 1.0);
            }
            return data;
        } catch (Exception e) {
            AppLogger.error("Failed to parse system info JSON", e);
            try {
                Path diag = AppPaths.logsDir().resolve("system-info-last.json");
                java.nio.file.Files.createDirectories(diag.getParent());
                java.nio.file.Files.writeString(diag, json, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
            throw new IOException("Failed to parse system info: " + e.getMessage(), e);
        }
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
        String s = rawOut.replace("\uFEFF", "").trim();
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
