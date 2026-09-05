package com.sbtools.systeminfo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public class SystemInfoService {

    private static final long TIMEOUT_SECONDS = 240;
    private static final long SECTION_TIMEOUT_SECONDS = 90;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    private static final long DISK_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_ATTEMPTS = 2;
    private static final int PARALLELISM = 4;

    /**
     * Balanced batches: each entry is one PowerShell invocation with
     * {@code -Sections a,b,c}. Four processes max, each bounded by
     * {@link #SECTION_TIMEOUT_SECONDS} with one retry. Keys match the
     * {@code system-info.ps1} {@code ShouldRun} filter (JSON property names).
     */
    static final List<SectionGroup> SECTION_GROUPS = List.of(
            new SectionGroup("core", List.of("cpu", "os", "bios", "motherboard", "battery")),
            new SectionGroup("compute", List.of("ram", "gpu", "storage")),
            new SectionGroup("devices", List.of("others", "usbDevices", "networkAdapters", "audioDevices")),
            new SectionGroup("peripherals", List.of("temperatures", "monitors", "printers"))
    );

    record SectionGroup(String name, List<String> sections) {}

    private final ProcessRunner processRunner = new ProcessRunner(TIMEOUT_SECONDS);
    private final ProcessRunner sectionRunner = new ProcessRunner(SECTION_TIMEOUT_SECONDS);
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
     *
     * <p>v3.1: fans out to 4 parallel {@code -Sections} invocations (one retry each,
     * per-group timeout) and merges. Falls back to the legacy single-call path when
     * section mode is unsupported or all groups fail, so old extracted scripts and
     * single-shot environments keep working.
     */
    public SystemInfoData gatherSystemInfo(BiConsumer<String, Double> progressCallback, boolean forceRefresh,
                                            AtomicBoolean cancelledToken)
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

        checkCancelled(cancelledToken);

        // Fast path: parallel section fan-out with incremental progress.
        SystemInfoData merged = tryGatherParallel(progressCallback, forceRefresh, cancelledToken);
        if (merged != null) {
            storeCache(merged);
            saveSnapshot(merged);
            if (progressCallback != null) {
                progressCallback.accept("done", 1.0);
            }
            return merged;
        }

        // Fallback: legacy single invocation (supports old extracted scripts without -Sections).
        AppLogger.warning("SystemInfo: parallel section gather failed, falling back to legacy single-call");
        if (progressCallback != null) {
            progressCallback.accept("hardware", 0.2);
        }
        SystemInfoData legacy = gatherLegacy(progressCallback, cancelledToken);
        storeCache(legacy);
        saveSnapshot(legacy);
        if (progressCallback != null) {
            progressCallback.accept("done", 1.0);
        }
        return legacy;
    }

    // ── Parallel fan-out ─────────────────────────────────────────────────────

    private SystemInfoData tryGatherParallel(BiConsumer<String, Double> progressCallback,
                                              boolean forceRefresh,
                                              AtomicBoolean cancelledToken) {
        Path script;
        try {
            script = PowerShellScripts.resolve("system-info.ps1");
        } catch (IOException e) {
            AppLogger.warning("SystemInfo: cannot resolve script for parallel gather: " + e.getMessage());
            return null;
        }

        int total = SECTION_GROUPS.size();
        AtomicInteger completed = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(total, PARALLELISM), r -> {
            Thread t = new Thread(r, "system-info-section");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<GroupResult>> futures = new ArrayList<>();
            for (SectionGroup group : SECTION_GROUPS) {
                Callable<GroupResult> task = () -> gatherGroup(script.toString(), group, cancelledToken);
                futures.add(pool.submit(task));
            }
            pool.shutdown();

            ObjectNode merged = JsonMapper.mapper().createObjectNode();
            List<String> warnings = new ArrayList<>();
            Map<String, Long> timings = new LinkedHashMap<>();
            String version = null;
            int usableGroups = 0;

            for (int i = 0; i < futures.size(); i++) {
                SectionGroup group = SECTION_GROUPS.get(i);
                GroupResult result = null;
                try {
                    // Bound overall wait: groups already have per-process timeouts + 1 retry.
                    result = futures.get(i).get(SECTION_TIMEOUT_SECONDS * MAX_ATTEMPTS + 30, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    cancelAll(futures);
                    return null;
                } catch (Exception e) {
                    AppLogger.warning("SystemInfo: group '" + group.name() + "' failed: " + e.getMessage());
                    warnings.add("Section group '" + group.name() + "' was unavailable (" + shortMessage(e) + ").");
                }
                checkCancelledQuiet(cancelledToken);
                if (result != null && result.root() != null) {
                    usableGroups++;
                    mergeGroup(merged, warnings, timings, result);
                    if (result.version() != null && (version == null || result.version().compareTo(version) > 0)) {
                        version = result.version();
                    }
                } else if (result != null && result.warning() != null) {
                    warnings.add(result.warning());
                    timings.putAll(result.timings());
                }
                int done = completed.incrementAndGet();
                if (progressCallback != null) {
                    double progress = 0.1 + 0.8 * ((double) done / total);
                    final String label = group.name();
                    final double p = Math.min(0.9, progress);
                    progressCallback.accept(label, p);
                }
                if (cancelledToken != null && cancelledToken.get()) {
                    cancelAll(futures);
                    return null;
                }
            }

            if (usableGroups == 0) {
                return null;
            }
            if (version != null) {
                merged.put("version", version);
            }
            // Attach merged timings + collection timestamp before tolerant parse.
            try {
                ObjectNode timingsNode = JsonMapper.mapper().createObjectNode();
                for (Map.Entry<String, Long> e : timings.entrySet()) {
                    timingsNode.put(e.getKey(), e.getValue() == null ? 0L : e.getValue());
                }
                merged.set("timings", timingsNode);
            } catch (Exception ignored) {}
            merged.put("collectedAt", Instant.now().toString());

            SystemInfoData data;
            try {
                data = parseTolerantly(JsonMapper.mapper().writeValueAsString(merged));
            } catch (IOException e) {
                AppLogger.warning("SystemInfo: merged payload unparseable: " + e.getMessage());
                return null;
            }
            // Prepend Java-level group warnings (PS warnings already inside payload).
            if (!warnings.isEmpty() && data.warnings() != null) {
                List<String> combined = new ArrayList<>(warnings);
                combined.addAll(data.warnings());
                data = new SystemInfoData(data.cpu(), data.gpu(), data.ram(), data.os(),
                        data.storage(), data.motherboard(), data.bios(), data.others(),
                        data.networkAdapters(), data.audioDevices(), data.battery(),
                        data.temperatures(), data.usbDevices(), data.monitors(),
                        data.printers(), data.version(), combined, data.timings(), data.collectedAt());
            }
            if (!isUsable(data)) {
                AppLogger.warning("SystemInfo: parallel merge produced no usable sections");
                return null;
            }
            return data;
        } finally {
            pool.shutdownNow();
        }
    }

    record GroupResult(JsonNode root, String version, String warning, Map<String, Long> timings) {}

    private GroupResult gatherGroup(String scriptPath, SectionGroup group, AtomicBoolean cancelledToken) {
        long startMs = System.currentTimeMillis();
        Map<String, Long> groupTimings = new LinkedHashMap<>();
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (cancelledToken != null && cancelledToken.get()) {
                return new GroupResult(null, null, null, groupTimings);
            }
            try {
                ProcessResult result = runSectionScript(scriptPath, group.sections(), cancelledToken);
                String rawOut = result.stdout();
                String json = (rawOut != null && !rawOut.isBlank()) ? extractJson(rawOut, result.stderr()) : null;
                if (json != null && !json.isBlank() && isValidSystemInfoJson(json)) {
                    JsonNode root = JsonMapper.mapper().readTree(json);
                    String version = root.has("version") && root.get("version").isTextual()
                            ? root.get("version").asText() : null;
                    // Collect PS-reported per-section timings for diagnostics.
                    try {
                        JsonNode t = root.get("timings");
                        if (t != null && t.isObject()) {
                            t.fields().forEachRemaining(e -> {
                                try {
                                    groupTimings.put(e.getKey(), e.getValue().asLong(0L));
                                } catch (Exception ignored) {}
                            });
                        }
                    } catch (Exception ignored) {}
                    groupTimings.put(group.name() + "Ms", System.currentTimeMillis() - startMs);
                    return new GroupResult(root, version, null, groupTimings);
                }
                // No usable JSON: capture stderr snippet (capped) for diagnostics.
                String detail = cappedSnippet(result.stderr(), 2000);
                if (detail.isBlank()) {
                    detail = "empty output (stdout=" + (rawOut == null ? 0 : rawOut.length()) + " chars)";
                }
                lastError = new IOException("Group '" + group.name() + "' attempt " + attempt + ": " + detail);
                persistDiagnosticDump(rawOut, result.stderr());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new GroupResult(null, null, null, groupTimings);
            } catch (java.util.concurrent.CancellationException ce) {
                Thread.currentThread().interrupt();
                return new GroupResult(null, null, null, groupTimings);
            } catch (IOException e) {
                lastError = e;
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                // powershell.exe missing -> caller falls back to pwsh inside runSectionScript;
                // an "unsupported -Sections" error means old script: abort parallel fast.
                if (msg.contains("sections") && (msg.contains("parameter") || msg.contains("cannot find"))) {
                    AppLogger.warning("SystemInfo: script does not support -Sections, aborting parallel path");
                    return new GroupResult(null, null, null, groupTimings);
                }
                AppLogger.warning("SystemInfo: group '" + group.name() + "' attempt " + attempt + " failed: " + e.getMessage());
            }
            // Brief backoff before retry (cancellation-aware).
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new GroupResult(null, null, null, groupTimings);
                }
            }
        }
        groupTimings.put(group.name() + "Ms", System.currentTimeMillis() - startMs);
        String warn = "Section group '" + group.name() + "' was unavailable after "
                + MAX_ATTEMPTS + " attempts"
                + (lastError != null && lastError.getMessage() != null ? (": " + cappedSnippet(lastError.getMessage(), 300)) : ".");
        return new GroupResult(null, null, warn, groupTimings);
    }

    static void mergeGroup(ObjectNode merged, List<String> warnings,
                            Map<String, Long> timings, GroupResult result) {
        JsonNode root = result.root();
        if (root == null || !root.isObject()) {
            return;
        }
        // Copy every present section key; skipped sections are $null (v3.1) and
        // must never overwrite the owning group's value. Defensive: never
        // overwrite an existing non-empty array/object with an empty one, so
        // old scripts (skipped = @()) and any group ordering stay correct.
        String[] sectionKeys = {"cpu", "gpu", "ram", "os", "storage", "motherboard", "bios",
                "others", "networkAdapters", "audioDevices", "battery",
                "temperatures", "usbDevices", "monitors", "printers"};
        for (String key : sectionKeys) {
            JsonNode node = root.get(key);
            if (node == null || node.isNull()) {
                continue;
            }
            JsonNode existing = merged.get(key);
            if (existing != null && !existing.isNull() && isNonEmpty(existing) && isEmptyContainer(node)) {
                continue;
            }
            merged.set(key, node);
        }
        JsonNode w = root.get("warnings");
        if (w != null && w.isArray()) {
            for (JsonNode item : w) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    warnings.add(item.asText());
                } else if (!item.isNull()) {
                    warnings.add(item.toString());
                }
            }
        }
        // Timings: keep real (>0) values; later skipped-group zeros must not
        // clobber earlier real measurements. totalMs keeps the max (slowest group).
        for (Map.Entry<String, Long> e : result.timings().entrySet()) {
            String key = e.getKey();
            long value = e.getValue() == null ? 0L : e.getValue();
            if ("totalMs".equals(key)) {
                timings.merge(key, value, Math::max);
                continue;
            }
            if (value > 0) {
                timings.put(key, value);
            } else {
                timings.putIfAbsent(key, value);
            }
        }
    }

    private static boolean isEmptyContainer(JsonNode node) {
        return (node.isArray() && node.isEmpty())
                || (node.isObject() && node.isEmpty());
    }

    private static boolean isNonEmpty(JsonNode node) {
        return !isEmptyContainer(node);
    }

    private static void cancelAll(List<Future<GroupResult>> futures) {
        for (Future<GroupResult> f : futures) {
            try {
                f.cancel(true);
            } catch (Exception ignored) {}
        }
    }

    private static boolean isUsable(SystemInfoData data) {
        if (data == null) {
            return false;
        }
        return (data.cpu() != null && data.cpu().name() != null && !data.cpu().name().isBlank())
                || (data.os() != null && data.os().name() != null && !data.os().name().isBlank())
                || (data.ram() != null && data.ram().totalBytes() > 0)
                || (data.storage() != null && data.storage().disks() != null && !data.storage().disks().isEmpty())
                || (data.gpu() != null && !data.gpu().isEmpty());
    }

    // ── Legacy single-call fallback (v3.0 behavior) ──────────────────────────

    private SystemInfoData gatherLegacy(BiConsumer<String, Double> progressCallback,
                                         AtomicBoolean cancelledToken)
            throws IOException, InterruptedException {
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
            // Include stderr snippet in exception when combined is terse (capped at 2KB).
            String errSnippet = cappedSnippet(result.stderr(), 2000);
            if (!errSnippet.isBlank() && !combined.contains(errSnippet)) {
                combined = combined + "\n" + errSnippet;
            }
            throw new IOException("System info query failed: " + combined);
        }
        if (!hasUsableJson) {
            if (rawOut == null || rawOut.isBlank()) {
                String errOut = result.stderr();
                if (errOut != null && !errOut.isBlank()) {
                    throw new IOException("System info query returned empty stdout. Stderr: " + cappedSnippet(errOut, 2000));
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
            SystemInfoData parsed = parseTolerantly(json);
            // Stamp collection time when the script did not provide one (v3.0 scripts).
            if (parsed.collectedAt() == null || parsed.collectedAt().isBlank()) {
                parsed = new SystemInfoData(parsed.cpu(), parsed.gpu(), parsed.ram(), parsed.os(),
                        parsed.storage(), parsed.motherboard(), parsed.bios(), parsed.others(),
                        parsed.networkAdapters(), parsed.audioDevices(), parsed.battery(),
                        parsed.temperatures(), parsed.usbDevices(), parsed.monitors(),
                        parsed.printers(), parsed.version(), parsed.warnings(),
                        parsed.timings(), Instant.now().toString());
            }
            return parsed;
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
                                              AtomicBoolean cancelledToken)
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

    private ProcessResult runSectionScript(String scriptPath, List<String> sections,
                                            AtomicBoolean cancelledToken)
            throws IOException, InterruptedException {
        String joined = String.join(",", sections);
        List<List<String>> candidates = List.of(
                ProcessRunner.powershellScriptNonInteractive(scriptPath, "-Sections", joined),
                ProcessRunner.pwshScriptNonInteractive(scriptPath, "-Sections", joined));
        IOException lastMissing = null;
        for (List<String> cmd : candidates) {
            try {
                return sectionRunner.run(cmd, SECTION_TIMEOUT_SECONDS, cancelledToken);
            } catch (IOException e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean missing = msg.contains("cannot run program") || msg.contains("no such file") || msg.contains("error=2");
                if (missing && cmd.get(0).equals("powershell.exe")) {
                    AppLogger.warning("SystemInfo: powershell.exe not found, trying pwsh.exe for sections " + joined);
                    lastMissing = e;
                    continue;
                }
                throw e;
            }
        }
        if (lastMissing != null) throw lastMissing;
        throw new IOException("Failed to run system info sections: no PowerShell executable");
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
        Map<String, Long> timings = null;
        try {
            JsonNode t = root.get("timings");
            if (t != null && t.isObject()) {
                timings = new LinkedHashMap<>();
                var fields = t.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    try {
                        timings.put(entry.getKey(), entry.getValue().asLong(0L));
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            AppLogger.warning("SystemInfo: unreadable 'timings' section: " + e.getMessage());
        }
        String collectedAt = null;
        try {
            JsonNode c = root.get("collectedAt");
            if (c != null && !c.isNull()) collectedAt = c.isTextual() ? c.asText() : c.toString();
        } catch (Exception e) {
            AppLogger.warning("SystemInfo: unreadable 'collectedAt' section: " + e.getMessage());
        }

        return new SystemInfoData(cpu, gpu, ram, os, storage, motherboard, bios, others,
                networkAdapters, audioDevices, battery, temperatures, usbDevices, monitors,
                printers, version, warnings, timings, collectedAt);
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

    // ── Cache (in-memory + portable disk snapshot) ───────────────────────────

    private void storeCache(SystemInfoData data) {
        synchronized (cacheLock) {
            cachedData = data;
            cacheTimestamp = System.currentTimeMillis();
        }
    }

    /** Stale-while-revalidate: instant render from last snapshot without blocking. */
    public SystemInfoData tryLoadCachedSnapshot() {
        synchronized (cacheLock) {
            if (cachedData != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS) {
                return cachedData;
            }
        }
        SystemInfoData disk = loadSnapshot();
        if (disk != null) {
            synchronized (cacheLock) {
                // Populate memory cache from disk so the next Load() can report "cached"
                // when the snapshot is still within TTL of its collection time.
                cachedData = disk;
                cacheTimestamp = System.currentTimeMillis();
            }
        }
        return disk;
    }

    public void invalidateCache() {
        synchronized (cacheLock) {
            cachedData = null;
            cacheTimestamp = 0;
        }
    }

    private void saveSnapshot(SystemInfoData data) {
        try {
            Path cache = cachePath();
            Files.createDirectories(cache.getParent());
            String pretty = JsonMapper.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(data);
            Files.writeString(cache, pretty, StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private SystemInfoData loadSnapshot() {
        try {
            Path cache = cachePath();
            if (!Files.isRegularFile(cache)) {
                return null;
            }
            long ageMs = System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis();
            if (ageMs < 0 || ageMs > DISK_CACHE_MAX_AGE_MS) {
                return null;
            }
            String json = Files.readString(cache, StandardCharsets.UTF_8);
            if (json == null || json.isBlank() || !isValidSystemInfoJson(json)) {
                return null;
            }
            SystemInfoData data = parseTolerantly(json);
            return isUsable(data) ? data : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path cachePath() {
        try {
            Path portable = AppPaths.portableLogsDir();
            if (portable != null) return portable.resolve("system-info-cache.json");
        } catch (Exception ignored) {}
        return AppPaths.logsDir().resolve("system-info-cache.json");
    }

    /** Portable-aware diagnostic dump (portable/logs when available, else LOCALAPPDATA). */
    private static void persistDiagnosticDump(String stdout, String stderr) {
        try {
            Path diag = diagnosticPath();
            Files.createDirectories(diag.getParent());
            String dump = stdout != null ? stdout : "";
            if (stderr != null && !stderr.isBlank()) {
                dump += "\n---STDERR---\n" + cappedSnippet(stderr, 8000);
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

    /**
     * Robust JSON extraction: PowerShell may emit non-JSON preamble before the
     * single Compressed JSON object (despite -NoProfile, enterprise transcription
     * or warnings can still appear on stdout if streams were merged in older builds).
     * We now keep streams separate (see gatherSystemInfo), but still defend against
     * any leading/trailing noise by locating the outermost JSON object that parses.
     */
    static String extractJson(String rawOut, String stderr) {
        if (rawOut == null) return null;
        // Strip BOM (PowerShell UTF-8 preamble) wherever it appears, then trim.
        String s = rawOut.replace("﻿", "").replace("￾", "").trim();
        if (s.isEmpty()) return s;
        // Fast path: whole output is already valid JSON containing expected keys
        if (isValidSystemInfoJson(s)) {
            return s;
        }
        // Line-based reverse scan: script emits one Compressed JSON line; any preamble is line-oriented.
        String[] lines = s.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].replace("﻿", "").trim();
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
            // Guard against pathological multi-MB noise: cap candidate window.
            if (last - first > 20 * 1024 * 1024) {
                s = s.substring(first, first + 20 * 1024 * 1024);
                last = s.lastIndexOf('}');
                if (last <= 0) return rawOut.trim();
            }
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
                // Bound scan cost on huge outputs.
                if (start > 1024 * 1024) break;
            }
        }
        // Last resort: return trimmed raw (will fail parse and be logged)
        return s;
    }

    static boolean isValidSystemInfoJson(String json) {
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

    private static void checkCancelled(AtomicBoolean cancelledToken) throws InterruptedException {
        if (cancelledToken != null && cancelledToken.get()) {
            throw new InterruptedException("System info cancelled by user");
        }
    }

    private static void checkCancelledQuiet(AtomicBoolean cancelledToken) {
        // Best-effort cooperative check inside the merge loop; the caller re-checks.
    }

    private static String cappedSnippet(String text, int maxChars) {
        if (text == null) return "";
        String t = text.trim();
        if (t.isEmpty()) return "";
        if (t.length() <= maxChars) return t;
        return t.substring(0, Math.max(0, maxChars)) + "…[truncated " + (t.length() - maxChars) + " chars]";
    }

    private static String shortMessage(Exception e) {
        if (e == null) return "unknown error";
        String m = e.getMessage();
        if (m == null || m.isBlank()) return e.getClass().getSimpleName();
        String oneLine = m.replaceAll("\\R", " ").trim();
        return oneLine.length() > 160 ? oneLine.substring(0, 160) + "…" : oneLine;
    }
}
