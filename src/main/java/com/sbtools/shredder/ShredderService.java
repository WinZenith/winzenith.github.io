package com.sbtools.shredder;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.ProcessManager;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

    public FolderDeleteResult secureDeleteFolder(String folderPath, int passCount) throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("Secure erase is only available on Windows.");
        }
        Path script = PowerShellScripts.resolve("secure-delete-folder.ps1");
        ProcessResult result = processRunner.run(
                ProcessRunner.powershellScript(script.toString(), folderPath, String.valueOf(passCount)));
        if (!result.success()) {
            return new FolderDeleteResult(false, "Process failed: " + result.combinedOutput(), 0, 0, List.of());
        }
        String json = result.stdout().trim();
        try {
            return JsonMapper.mapper().readValue(json, FolderDeleteResult.class);
        } catch (Exception e) {
            AppLogger.error("Failed to parse folder delete result", e);
            return new FolderDeleteResult(false, "Parse error: " + e.getMessage(), 0, 0, List.of());
        }
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

    public record RecycleBinResult(List<RecycleBinEntry> entries, long totalSizeBytes, int fileCount) {}

    public RecycleBinResult getRecycleBinContents() throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("Recycle Bin is only available on Windows.");
        }
        Path script = PowerShellScripts.resolve("list-recyclebin.ps1");
        ProcessResult result = processRunner.run(
                ProcessRunner.powershellScript(script.toString()));
        if (!result.success()) {
            throw new IOException("Failed to list Recycle Bin: " + result.combinedOutput());
        }
        String json = result.stdout().trim();
        if (json.isBlank()) return new RecycleBinResult(List.of(), 0, 0);
        try {
            JsonNode root = JsonMapper.mapper().readTree(json);
            long totalSize = root.has("totalSizeBytes") ? root.get("totalSizeBytes").asLong(0) : 0;
            int fileCount = root.has("fileCount") ? root.get("fileCount").asInt(0) : 0;
            List<RecycleBinEntry> entries = new ArrayList<>();
            JsonNode filesNode = root.has("files") ? root.get("files") : null;
            if (filesNode != null && filesNode.isArray()) {
                for (JsonNode node : filesNode) {
                    entries.add(JsonMapper.mapper().treeToValue(node, RecycleBinEntry.class));
                }
            }
            return new RecycleBinResult(entries, totalSize, fileCount);
        } catch (Exception e) {
            AppLogger.error("Failed to parse Recycle Bin JSON", e);
            throw new IOException("Failed to parse Recycle Bin: " + e.getMessage(), e);
        }
    }

    public FolderDeleteResult secureWipeRecycleBin(List<String> recyclePaths, int passCount,
                                                    Consumer<String> progressCallback,
                                                    AtomicBoolean cancelled) throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("Recycle Bin wipe is only available on Windows.");
        }
        int filesDeleted = 0;
        int foldersDeleted = 0;
        List<String> scheduledForReboot = new ArrayList<>();

        for (int i = 0; i < recyclePaths.size(); i++) {
            if (cancelled != null && cancelled.get()) break;
            String path = recyclePaths.get(i);
            int current = i + 1;
            int total = recyclePaths.size();

            if (progressCallback != null) {
                progressCallback.accept("Securely deleting (" + current + "/" + total + "): " + new File(path).getName());
            }

            File file = new File(path);
            if (!file.exists()) continue;

            try {
                ShredderResult result = secureDelete(path, passCount);
                if (result.isDeleted()) {
                    filesDeleted++;
                } else if (result.isScheduledForReboot()) {
                    scheduledForReboot.add(path);
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                if (msg.contains("in use") || msg.contains("access denied") || msg.contains("unauthorized")) {
                    scheduledForReboot.add(path);
                } else {
                    AppLogger.error("Failed to securely delete recycle bin entry: " + path, e);
                }
            }
        }

        return new FolderDeleteResult(true,
                "Recycle Bin wipe: " + filesDeleted + " files securely deleted.",
                filesDeleted, foldersDeleted, scheduledForReboot);
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

        try {
            for (String driveLetter : driveLetters) {
                if (cancelled != null && cancelled.get()) break;

                List<String> cmd = new ArrayList<>(ProcessRunner.powershellScript(script.toString()));
                cmd.add(driveLetter);
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
                        throw new IOException("Free space wipe timed out for drive " + driveLetter + ".");
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                    throw new IOException("Free space wipe interrupted.", e);
                }
                if (process.exitValue() != 0 && (cancelled == null || !cancelled.get())) {
                    throw new IOException("Free space wipe failed with exit code " + process.exitValue()
                            + " on drive " + driveLetter + ".");
                }
            }
        } finally {
            cleanupTempFiles();
            stopFlag.delete();
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
                if (!root.exists() || !root.canRead()) continue;
                try {
                    File[] orphans = root.listFiles((dir, name) ->
                            name.startsWith("~winzenith-wipe-") && name.endsWith(".tmp"));
                    if (orphans != null) {
                        for (File f : orphans) {
                            if (f.delete()) {
                                AppLogger.info("Cleaned up orphaned temp file: " + f.getAbsolutePath());
                            }
                        }
                    }
                } catch (Exception ignored) {
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
                            process.destroyForcibly();
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
