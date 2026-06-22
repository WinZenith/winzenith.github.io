package com.sbtools.shredder;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.ProcessManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class ShredderService {

    private static final long TIMEOUT_SECONDS = 3600;
    private final ProcessRunner processRunner = new ProcessRunner(TIMEOUT_SECONDS);
    private final List<String> knownTempFiles = new CopyOnWriteArrayList<>();

    public ShredderResult secureDelete(String filePath) throws IOException, InterruptedException {
        return secureDelete(filePath, 3);
    }

    public ShredderResult secureDelete(String filePath, int passCount) throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("Secure erase is only available on Windows.");
        }
        Path script = PowerShellScripts.resolve("secure-delete.ps1");
        ProcessResult result = processRunner.run(
                ProcessRunner.powershellScript(script.toString(), filePath, String.valueOf(passCount)));
        return parseResult(result, filePath);
    }

    public ShredderResult scheduleForReboot(String filePath) throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("Reboot scheduling is only available on Windows.");
        }
        Path script = PowerShellScripts.resolve("schedule-reboot-delete.ps1");
        ProcessResult result = processRunner.run(
                ProcessRunner.powershellScript(script.toString(), filePath));
        return parseResult(result, filePath);
    }

    public void wipeFreeSpace(List<String> driveLetters, Consumer<WipeProgress> progressCallback,
                              AtomicBoolean cancelled) throws IOException {
        wipeFreeSpace(driveLetters, progressCallback, cancelled, 3);
    }

    public void wipeFreeSpace(List<String> driveLetters, Consumer<WipeProgress> progressCallback,
                              AtomicBoolean cancelled, int passCount) throws IOException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("Free space wiping is only available on Windows.");
        }
        knownTempFiles.clear();
        Path script = PowerShellScripts.resolve("wipe-free-space.ps1");

        File stopFlag = File.createTempFile("winzenith-wipe-stop-", ".flag");
        stopFlag.deleteOnExit();

        List<String> cmd = new ArrayList<>(ProcessRunner.powershellScript(script.toString()));
        cmd.addAll(driveLetters);
        cmd.add("-StopFlagPath");
        cmd.add(stopFlag.getAbsolutePath());
        cmd.add("-PassCount");
        cmd.add(String.valueOf(passCount));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = ProcessManager.start(pb);

        ProcessWatcher watcher = new ProcessWatcher(process, progressCallback, cancelled, stopFlag);
        watcher.watch();

        try {
            boolean finished = process.waitFor(3600, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Free space wipe timed out.");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Free space wipe interrupted.", e);
        } finally {
            cleanupTempFiles();
            stopFlag.delete();
        }

        if (process.exitValue() != 0 && !cancelled.get()) {
            throw new IOException("Free space wipe failed with exit code " + process.exitValue());
        }
    }

    private void cleanupTempFiles() {
        for (String path : knownTempFiles) {
            try {
                File f = new File(path);
                if (f.exists()) {
                    f.delete();
                    AppLogger.info("Cleaned up temp file: " + path);
                }
            } catch (Exception e) {
                AppLogger.error("Failed to clean up temp file: " + path, e);
            }
        }
        knownTempFiles.clear();
        sweepOrphanedTempFiles();
    }

    public static void sweepOrphanedTempFiles() {
        try {
            for (File root : File.listRoots()) {
                File[] orphans = root.listFiles((dir, name) ->
                        name.startsWith("~winzenith-wipe-") && name.endsWith(".tmp"));
                if (orphans != null) {
                    for (File f : orphans) {
                        if (f.delete()) {
                            AppLogger.info("Cleaned up orphaned temp file: " + f.getAbsolutePath());
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.error("Error during orphan cleanup", e);
        }
    }

    private class ProcessWatcher {
        private final Process process;
        private final Consumer<WipeProgress> callback;
        private final AtomicBoolean cancelled;
        private final File stopFlag;

        ProcessWatcher(Process process, Consumer<WipeProgress> callback,
                       AtomicBoolean cancelled, File stopFlag) {
            this.process = process;
            this.callback = callback;
            this.cancelled = cancelled;
            this.stopFlag = stopFlag;
        }

        void watch() {
            Thread t = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (cancelled.get()) {
                            try { stopFlag.createNewFile(); } catch (Exception ignored) {}
                            process.destroy();
                            break;
                        }
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        try {
                            WipeProgress prog = JsonMapper.mapper().readValue(line, WipeProgress.class);
                            String tf = prog.getTempFile();
                            if (tf != null && !tf.isEmpty() && !knownTempFiles.contains(tf)) {
                                knownTempFiles.add(tf);
                            }
                            if (callback != null) {
                                callback.accept(prog);
                            }
                        } catch (Exception e) {
                            AppLogger.error("Failed to parse wipe progress JSON: " + line, e);
                        }
                    }
                } catch (IOException e) {
                    AppLogger.error("Error reading wipe process output stream", e);
                }
            }, "wipe-stream-reader");
            t.setDaemon(true);
            t.start();
        }
    }

    private ShredderResult parseResult(ProcessResult result, String filePath) {
        if (!result.success()) {
            return new ShredderResult(filePath, false, false, false, "Process failed: " + result.combinedOutput());
        }
        String json = result.stdout().trim();
        try {
            return JsonMapper.mapper().readValue(json, ShredderResult.class);
        } catch (Exception e) {
            AppLogger.error("Failed to parse shredder result", e);
            return new ShredderResult(filePath, false, false, false, "Parse error: " + e.getMessage());
        }
    }
}
