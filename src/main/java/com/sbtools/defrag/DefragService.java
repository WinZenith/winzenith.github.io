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

    private static final long TIMEOUT_SECONDS = 600;
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
        ProcessResult result = processRunner.run(ProcessRunner.powershellScript(script.toString()));
        if (!result.success()) {
            throw new IOException("Failed to enumerate drives: " + result.combinedOutput());
        }
        String json = result.stdout().trim();
        if (json.isBlank()) return List.of();
        try {
            return JsonMapper.mapper().readValue(json,
                    JsonMapper.mapper().getTypeFactory().constructCollectionType(List.class, DriveInfo.class));
        } catch (Exception e) {
            AppLogger.error("Failed to parse drive info JSON", e);
            throw new IOException("Failed to parse drive info: " + e.getMessage(), e);
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
        processRunner.runStreaming(
                ProcessRunner.powershellScript(script.toString(), letter, mode),
                statusCallback, progressCallback, cancelled);
    }

    public void trim(DriveInfo drive, Consumer<String> statusCallback,
                     Consumer<Double> progressCallback, AtomicBoolean cancelled)
            throws IOException, CancellationException {
        if (!AppPaths.isWindows()) return;
        String letter = drive.getDriveLetter().replace(":", "");
        Path script = PowerShellScripts.resolve("optimize-volume.ps1");
        processRunner.runStreaming(
                ProcessRunner.powershellScript(script.toString(), letter, "TRIM"),
                statusCallback, progressCallback, cancelled);
    }
}
