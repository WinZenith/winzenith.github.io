package com.sbtools.defrag;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class DefragService {

    public enum DefragOption {
        FAST,
        FULL,
        FREE_SPACE
    }

    private static final long TIMEOUT_SECONDS = 3600;
    private static final long GET_DRIVES_TIMEOUT_SECONDS = 30;
    private static final long METADATA_CACHE_TTL_MS = 5 * 60 * 1000;
    private final ProcessRunner processRunner = new ProcessRunner(TIMEOUT_SECONDS);
    private final Map<String, MetadataCacheEntry> metadataCache = new ConcurrentHashMap<>();

    private record MetadataCacheEntry(long mftSizeBytes, long pageFileSizeBytes,
                                       long hiberFileSizeBytes, long swapFileSizeBytes,
                                       long timestamp) {
        boolean isFresh() {
            return System.currentTimeMillis() - timestamp < METADATA_CACHE_TTL_MS;
        }
    }

    @SuppressWarnings("unchecked")
    public List<DriveInfo> getDrives() throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("Drive operations are only available on Windows.");
        }
        Path script = PowerShellScripts.resolve("get-drives.ps1");
        ProcessResult result = null;
        IOException lastIoException = null;
        // Try powershell.exe first, fallback to pwsh.exe if not available
        List<List<String>> candidates = List.of(
                ProcessRunner.powershellScript(script.toString()),
                ProcessRunner.pwshScript(script.toString())
        );
        for (List<String> cmd : candidates) {
            try {
                result = processRunner.run(cmd, GET_DRIVES_TIMEOUT_SECONDS);
                break;
            } catch (IOException e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean isMissingExe = msg.contains("cannot run program") || msg.contains("no such file") || msg.contains("error=2");
                if (isMissingExe && cmd != candidates.get(candidates.size() - 1)) {
                    AppLogger.warning("PowerShell executable not found, trying fallback: " + cmd.get(0) + " -> " + e.getMessage());
                    lastIoException = e;
                    continue;
                }
                throw e;
            }
        }
        if (result == null) {
            if (lastIoException != null) throw lastIoException;
            throw new IOException("Failed to enumerate drives: no PowerShell executable available");
        }
        if (!result.success()) {
            AppLogger.error("get-drives.ps1 failed exit=" + result.exitCode() + " stdout=" + result.stdout() + " stderr=" + result.stderr());
            throw new IOException("Failed to enumerate drives: " + result.combinedOutput());
        }
        String json = result.stdout().trim();
        String stderr = result.stderr() != null ? result.stderr().trim() : "";
        if (json.isBlank()) {
            if (!stderr.isBlank()) {
                AppLogger.error("get-drives.ps1 returned blank stdout but stderr present: " + stderr);
                throw new IOException("Drive enumeration failed: " + stderr);
            }
            // Retry once on blank (transient WMI stall)
            AppLogger.warning("get-drives.ps1 returned blank JSON, retrying once");
            try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
            // retry with short timeout using same fallback logic
            for (List<String> cmd : candidates) {
                try {
                    result = processRunner.run(cmd, GET_DRIVES_TIMEOUT_SECONDS);
                    break;
                } catch (IOException e) {
                    String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                    boolean isMissingExe = msg.contains("cannot run program") || msg.contains("no such file");
                    if (isMissingExe && cmd != candidates.get(candidates.size() - 1)) continue;
                    throw e;
                }
            }
            if (result != null) json = result.stdout().trim();
            if (json.isBlank()) {
                AppLogger.warning("get-drives.ps1 still blank after retry; treating as no drives but logging for diagnostics");
                dumpDriveEnumOutput(json, stderr, result != null ? result.combinedOutput() : "");
                return List.of();
            }
        }
        // Dump for diagnostics (best-effort)
        dumpDriveEnumOutput(json, stderr, null);
        try {
            com.fasterxml.jackson.databind.JsonNode root = JsonMapper.mapper().readTree(json);
            // Handle wrapper { drives: [...], error: ... }
            if (root.isObject() && root.has("drives")) {
                com.fasterxml.jackson.databind.JsonNode drivesNode = root.get("drives");
                if (root.has("error") && !root.get("error").asText("").isBlank()) {
                    String err = root.get("error").asText();
                    // If drives empty and error present -> treat as failure so UI shows error, not silent 0
                    if (drivesNode == null || !drivesNode.isArray() || drivesNode.size() == 0) {
                        throw new IOException("Drive enumeration reported error: " + err);
                    } else {
                        AppLogger.warning("get-drives.ps1 reported partial error: " + err);
                    }
                }
                if (drivesNode == null || drivesNode.isNull()) return List.of();
                if (drivesNode.isArray()) {
                    return JsonMapper.mapper().readValue(drivesNode.toString(),
                            JsonMapper.mapper().getTypeFactory().constructCollectionType(List.class, DriveInfo.class));
                } else if (drivesNode.isObject()) {
                    DriveInfo single = JsonMapper.mapper().treeToValue(drivesNode, DriveInfo.class);
                    return List.of(single);
                }
                return List.of();
            }
            // Plain array
            if (root.isArray()) {
                return JsonMapper.mapper().readValue(json,
                        JsonMapper.mapper().getTypeFactory().constructCollectionType(List.class, DriveInfo.class));
            }
            // Single object case (PS single-item collapse) -> wrap
            if (root.isObject() && root.has("driveLetter")) {
                DriveInfo single = JsonMapper.mapper().treeToValue(root, DriveInfo.class);
                return List.of(single);
            }
            // Unknown object — log and try as list fallback
            AppLogger.warning("get-drives.ps1 returned unexpected JSON shape: " + json.substring(0, Math.min(500, json.length())));
            return JsonMapper.mapper().readValue(json,
                    JsonMapper.mapper().getTypeFactory().constructCollectionType(List.class, DriveInfo.class));
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Drive enumeration reported error")) throw e;
            AppLogger.error("Failed to parse drive info JSON: " + json.substring(0, Math.min(800, json.length())), e);
            dumpDriveEnumOutput(json, stderr, e.getMessage());
            throw new IOException("Failed to parse drive info: " + e.getMessage(), e);
        } catch (Exception e) {
            AppLogger.error("Failed to parse drive info JSON", e);
            throw new IOException("Failed to parse drive info: " + e.getMessage(), e);
        }
    }

    private void dumpDriveEnumOutput(String json, String stderr, String extra) {
        try {
            java.nio.file.Path dumpDir = AppPaths.portableLogsDir();
            // Ensure fallback to localAppData if portable not writable
            try {
                java.nio.file.Files.createDirectories(dumpDir);
                if (!java.nio.file.Files.isWritable(dumpDir)) throw new java.io.IOException("not writable");
            } catch (Exception ex) {
                dumpDir = AppPaths.logsDir();
                java.nio.file.Files.createDirectories(dumpDir);
            }
            java.nio.file.Path dumpFile = dumpDir.resolve("drive-enum-last.json");
            String content = "timestamp: " + java.time.Instant.now() + System.lineSeparator()
                    + "stdout: " + (json == null ? "" : json) + System.lineSeparator()
                    + "stderr: " + (stderr == null ? "" : stderr) + System.lineSeparator()
                    + (extra != null ? "extra: " + extra + System.lineSeparator() : "");
            java.nio.file.Files.writeString(dumpFile, content, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception ignored) {
        }
    }

    /**
     * Returns true if the drive is an SSD and should skip fragmentation analysis.
     */
    public static boolean isSsd(DriveInfo drive) {
        return "SSD".equalsIgnoreCase(drive.getMediaType());
    }

    /**
     * Analyzes fragmentation for a single drive.
     * If the drive is an SSD, populates zero values and returns without running analysis.
     * Metadata (MFT, system files) is cached for 5 minutes to avoid redundant queries.
     */
    public void analyze(DriveInfo drive, Consumer<String> progressCallback, AtomicBoolean cancelled)
            throws IOException, InterruptedException, CancellationException {
        if (!AppPaths.isWindows()) return;

        String letter = drive.getDriveLetter().replace(":", "");

        if (isSsd(drive)) {
            drive.setFragmentedSpaceBytes(0);
            drive.setFragmentationPercent(0);
            drive.setFragmentedFileCount(0);
            drive.setTotalFileCount(0);
            drive.setAverageFragmentsPerFile(0);

            MetadataCacheEntry cached = metadataCache.get(letter);
            if (cached != null && cached.isFresh()) {
                drive.setMftSizeBytes(cached.mftSizeBytes());
                drive.setPageFileSizeBytes(cached.pageFileSizeBytes());
                drive.setHiberFileSizeBytes(cached.hiberFileSizeBytes());
                drive.setSwapFileSizeBytes(cached.swapFileSizeBytes());
            }
            if (progressCallback != null) {
                progressCallback.accept("SSD detected — fragmentation analysis skipped. Use Trim instead.");
            }
            return;
        }

        MetadataCacheEntry cached = metadataCache.get(letter);
        boolean skipMetadata = cached != null && cached.isFresh();

        if (skipMetadata) {
            drive.setMftSizeBytes(cached.mftSizeBytes());
            drive.setPageFileSizeBytes(cached.pageFileSizeBytes());
            drive.setHiberFileSizeBytes(cached.hiberFileSizeBytes());
            drive.setSwapFileSizeBytes(cached.swapFileSizeBytes());
        }

        List<String> args = new ArrayList<>();
        args.add(letter);
        if (skipMetadata) {
            args.add("-SkipMetadata");
        }

        Path script = PowerShellScripts.resolve("analyze-fragmentation.ps1");
        ProcessResult result = processRunner.runStreaming(
                ProcessRunner.powershellScript(script.toString(), args.toArray(new String[0])),
                line -> {
                    if (line.startsWith("stage:") && progressCallback != null) {
                        progressCallback.accept(line.substring(6));
                    }
                }, null, cancelled);

        if (!result.success()) {
            throw new IOException("Analysis failed: " + result.combinedOutput());
        }

        String json = extractJson(result.stdout());
        if (json.isBlank()) return;
        try {
            var parsed = JsonMapper.mapper().readTree(json);
            long fragments = parsed.get("fragmentsFound").asLong(0);
            long percent = parsed.get("fragmentationPercent").asLong(0);
            drive.setFragmentedSpaceBytes(fragments);
            drive.setFragmentationPercent(percent);

            long fragFiles = parsed.has("fragmentedFileCount") ? parsed.get("fragmentedFileCount").asLong(0) : 0;
            long totalFiles = parsed.has("totalFileCount") ? parsed.get("totalFileCount").asLong(0) : 0;
            double avgFrag = parsed.has("averageFragmentsPerFile") ? parsed.get("averageFragmentsPerFile").asDouble(0) : 0;
            long mftSize = parsed.has("mftSizeBytes") ? parsed.get("mftSizeBytes").asLong(0) : 0;
            long pageSize = parsed.has("pageFileSizeBytes") ? parsed.get("pageFileSizeBytes").asLong(0) : 0;
            long hiberSize = parsed.has("hiberFileSizeBytes") ? parsed.get("hiberFileSizeBytes").asLong(0) : 0;
            long swapSize = parsed.has("swapFileSizeBytes") ? parsed.get("swapFileSizeBytes").asLong(0) : 0;
            long totalDirs = parsed.has("totalDirectories") ? parsed.get("totalDirectories").asLong(0) : 0;

            drive.setFragmentedFileCount(fragFiles);
            drive.setTotalFileCount(totalFiles);
            drive.setAverageFragmentsPerFile(avgFrag);
            drive.setMftSizeBytes(mftSize);
            drive.setPageFileSizeBytes(pageSize);
            drive.setHiberFileSizeBytes(hiberSize);
            drive.setSwapFileSizeBytes(swapSize);
            drive.setTotalDirectories(totalDirs);

            if (!skipMetadata) {
                metadataCache.put(letter, new MetadataCacheEntry(mftSize, pageSize, hiberSize, swapSize, System.currentTimeMillis()));
            }

            if (progressCallback != null) {
                progressCallback.accept("Analysis complete - " + fragments + " bytes fragmented space, " + percent + "% fragmented");
            }
        } catch (Exception e) {
            AppLogger.error("Failed to parse analysis result", e);
            throw new IOException("Failed to parse analysis: " + e.getMessage(), e);
        }
    }

    private static String extractJson(String output) {
        String[] lines = output.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{")) {
                return line;
            }
        }
        return output.trim();
    }

    public void defrag(DriveInfo drive, DefragOption option, Consumer<String> statusCallback,
                       Consumer<Double> progressCallback, AtomicBoolean cancelled)
            throws IOException, CancellationException {
        if (!AppPaths.isWindows()) return;
        String letter = drive.getDriveLetter().replace(":", "");
        String mode = switch (option) {
            case FAST -> "FAST";
            case FULL -> "FULL";
            case FREE_SPACE -> "FREE_SPACE";
        };
        Path script = PowerShellScripts.resolve("optimize-volume.ps1");
        ProcessResult result = processRunner.runStreaming(
                ProcessRunner.powershellScript(script.toString(), letter, mode),
                line -> {
                    // Filter out raw JSON progress/result lines from status display
                    if (line != null && line.trim().startsWith("{") && line.contains("\"success\"")) {
                        // Don't show raw JSON as status; let caller handle
                        return;
                    }
                    if (statusCallback != null) statusCallback.accept(line);
                }, progressCallback, cancelled);
        checkOptimizeResult(result);
    }

    public void trim(DriveInfo drive, Consumer<String> statusCallback,
                     Consumer<Double> progressCallback, AtomicBoolean cancelled)
            throws IOException, CancellationException {
        if (!AppPaths.isWindows()) return;
        String letter = drive.getDriveLetter().replace(":", "");
        Path script = PowerShellScripts.resolve("optimize-volume.ps1");
        ProcessResult result = processRunner.runStreaming(
                ProcessRunner.powershellScript(script.toString(), letter, "TRIM"),
                line -> {
                    if (line != null && line.trim().startsWith("{") && line.contains("\"success\"")) return;
                    if (statusCallback != null) statusCallback.accept(line);
                }, progressCallback, cancelled);
        checkOptimizeResult(result);
    }

    private void checkOptimizeResult(ProcessResult result) throws IOException {
        if (result == null) return;
        String output = result.stdout() != null ? result.stdout().trim() : "";
        if (output.isEmpty()) {
            if (!result.success()) throw new IOException("Defrag operation failed: " + result.combinedOutput());
            return;
        }
        // Find last JSON line with success field
        String lastJson = null;
        for (String line : output.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("{") && t.contains("\"success\"")) lastJson = t;
        }
        if (lastJson != null) {
            try {
                var node = JsonMapper.mapper().readTree(lastJson);
                if (node.has("success") && !node.get("success").asBoolean(true)) {
                    String msg = node.has("message") ? node.get("message").asText("Operation failed") : "Operation failed";
                    throw new IOException(msg);
                }
            } catch (IOException e) {
                if (e.getMessage() != null && (e.getMessage().contains("Operation failed") || e.getMessage().contains("Access denied"))) throw e;
                // JSON parse error - fall back to exit code check
                if (!result.success()) throw new IOException("Defrag operation failed: " + result.combinedOutput());
            }
        } else if (!result.success()) {
            throw new IOException("Defrag operation failed: " + result.combinedOutput());
        }
    }
}
