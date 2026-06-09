package com.sbtools.defrag;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class DefragService {

    public enum DefragOption {
        FAST,
        FULL,
        FREE_SPACE
    }

    private static final long TIMEOUT_SECONDS = 600;
    private final ProcessRunner processRunner = new ProcessRunner(TIMEOUT_SECONDS);

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

    public void analyze(DriveInfo drive, Consumer<String> progressCallback, AtomicBoolean cancelled)
            throws IOException, InterruptedException, CancellationException {
        if (!AppPaths.isWindows()) return;
        String letter = drive.getDriveLetter().replace(":", "");
        Path script = PowerShellScripts.resolve("analyze-fragmentation.ps1");
        ProcessResult result = processRunner.run(
                ProcessRunner.powershellScript(script.toString(), letter));

        if (cancelled.get()) throw new CancellationException("Analysis cancelled");

        if (!result.success()) {
            throw new IOException("Analysis failed: " + result.combinedOutput());
        }
        String json = result.stdout().trim();
        if (json.isBlank()) return;
        try {
            var parsed = JsonMapper.mapper().readTree(json);
            long fragments = parsed.get("fragmentsFound").asLong(0);
            long percent = parsed.get("fragmentationPercent").asLong(0);
            drive.setFragmentsFound(fragments);
            drive.setFragmentationPercent(percent);
            if (progressCallback != null) {
                progressCallback.accept("Analysis complete - " + fragments + " fragments, " + percent + "% fragmented");
            }
        } catch (Exception e) {
            AppLogger.error("Failed to parse analysis result", e);
            throw new IOException("Failed to parse analysis: " + e.getMessage(), e);
        }
    }

    public void defrag(DriveInfo drive, DefragOption option, Consumer<String> progressCallback,
                       AtomicBoolean cancelled) throws IOException, InterruptedException, CancellationException {
        if (!AppPaths.isWindows()) return;
        String letter = drive.getDriveLetter().replace(":", "");
        String mode = switch (option) {
            case FAST -> "FAST";
            case FULL -> "FULL";
            case FREE_SPACE -> "FREE_SPACE";
        };
        Path script = PowerShellScripts.resolve("optimize-volume.ps1");
        ProcessResult result = processRunner.run(
                ProcessRunner.powershellScript(script.toString(), letter, mode));

        if (cancelled != null && cancelled.get()) throw new CancellationException("Defrag cancelled");

        if (progressCallback != null) {
            String msg = result.success() ? result.stdout() : result.combinedOutput();
            progressCallback.accept(msg);
        }
    }

    public void trim(DriveInfo drive, Consumer<String> progressCallback, AtomicBoolean cancelled)
            throws IOException, InterruptedException, CancellationException {
        if (!AppPaths.isWindows()) return;
        String letter = drive.getDriveLetter().replace(":", "");
        Path script = PowerShellScripts.resolve("optimize-volume.ps1");
        ProcessResult result = processRunner.run(
                ProcessRunner.powershellScript(script.toString(), letter, "TRIM"));

        if (cancelled != null && cancelled.get()) throw new CancellationException("Trim cancelled");

        if (progressCallback != null) {
            String msg = result.success() ? result.stdout() : result.combinedOutput();
            progressCallback.accept(msg);
        }
    }
}
