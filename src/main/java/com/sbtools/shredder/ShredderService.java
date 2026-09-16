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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class ShredderService {

    private static final long TIMEOUT_SECONDS = 0;
    private final ProcessRunner processRunner = new ProcessRunner(TIMEOUT_SECONDS);
    private final List<String> knownTempFiles = new CopyOnWriteArrayList<>();

    public ShredderResult secureDelete(String filePath) throws IOException, InterruptedException {
        return secureDelete(filePath, 3);
    }

    public ShredderResult secureDelete(String filePath, int passCount) throws IOException, InterruptedException {
        return secureDelete(filePath, passCount, false);
    }

    private ShredderResult secureDelete(String filePath, int passCount, boolean recycleWipe)
            throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("Secure erase is only available on Windows.");
        }
        // Defense-in-depth: UI validates, but direct service callers must not bypass it.
        String blocked = recycleWipe
                ? ShredderSafety.validateRecycleBinItemForWipe(filePath)
                : ShredderSafety.validateFileForShred(filePath);
        if (blocked != null) {
            return new ShredderResult(filePath, false, false, false, "Blocked for safety: " + blocked);
        }
        Path script = PowerShellScripts.resolve("secure-delete.ps1");
        ProcessResult result = runWithFallback(script.toString(), filePath, String.valueOf(passCount));
        return parseResult(result, filePath);
    }

    public FolderDeleteResult secureDeleteFolder(String folderPath, int passCount) throws IOException, InterruptedException {
        return secureDeleteFolder(folderPath, passCount, null, null);
    }

    /**
     * Streaming variant with per-file progress and cooperative cancellation.
     * Previously per-file {progress,phase:overwrite} lines were ignored and cancel
     * was unsupported. Progress lines are filtered out of the final JSON parse
     * (last {success} line wins) so mixed stdout no longer causes parse errors.
     */
    public FolderDeleteResult secureDeleteFolder(String folderPath, int passCount,
                                                 Consumer<String> progressCallback,
                                                 AtomicBoolean cancelled) throws IOException, InterruptedException {
        return secureDeleteFolder(folderPath, passCount, progressCallback, cancelled, false);
    }

    private FolderDeleteResult secureDeleteFolder(String folderPath, int passCount,
                                                 Consumer<String> progressCallback,
                                                 AtomicBoolean cancelled,
                                                 boolean recycleWipe) throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("Secure erase is only available on Windows.");
        }
        // Defense-in-depth: UI validates, but direct service callers must not bypass it.
        String blocked = recycleWipe
                ? ShredderSafety.validateRecycleBinItemForWipe(folderPath)
                : ShredderSafety.validateFolderForShred(folderPath);
        if (blocked != null) {
            return new FolderDeleteResult(false, "Blocked for safety: " + blocked, 0, 0, List.of());
        }
        Path script = PowerShellScripts.resolve("secure-delete-folder.ps1");
        ProcessResult result = runStreamingWithFallback(script.toString(),
                line -> {
                    if (line == null) return;
                    String t = line.trim();
                    // Surface per-file progress, hide raw JSON from status text
                    if (t.startsWith("{") && t.contains("\"phase\"") && progressCallback != null) {
                        try {
                            JsonNode n = JsonMapper.mapper().readTree(t);
                            if (n.has("phase") && "overwrite".equals(n.get("phase").asText())
                                    && n.has("current") && n.has("total")) {
                                progressCallback.accept("Overwriting file "
                                        + n.get("current").asInt() + "/" + n.get("total").asInt()
                                        + (n.has("file") ? ": " + n.get("file").asText() : ""));
                                return;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    if (t.startsWith("{") && t.contains("\"progress\"")) return;
                    if (progressCallback != null) progressCallback.accept(line);
                }, null, cancelled, folderPath, String.valueOf(passCount));
        if (cancelled != null && cancelled.get()) {
            return new FolderDeleteResult(false, "Secure folder delete cancelled by user.", 0, 0, List.of());
        }
        if (!result.success()) {
            return new FolderDeleteResult(false, "Process failed: " + result.combinedOutput(), 0, 0, List.of());
        }
        String json = extractLastSuccessJson(result.stdout());
        if (json.isBlank()) {
            return new FolderDeleteResult(false, "No result returned by secure folder delete.", 0, 0, List.of());
        }
        FolderDeleteResult parsed;
        try {
            parsed = JsonMapper.mapper().readValue(json, FolderDeleteResult.class);
        } catch (Exception e) {
            AppLogger.error("Failed to parse folder delete result", e);
            return new FolderDeleteResult(false, "Parse error: " + e.getMessage(), 0, 0, List.of());
        }
        return finalizeFolderRebootSchedule(parsed, recycleWipe);
    }

    /**
     * PS lists locked paths as scheduledForReboot but does not write the registry.
     * Actually schedule here so callers cannot treat a leftover list as success.
     */
    FolderDeleteResult finalizeFolderRebootSchedule(FolderDeleteResult parsed, boolean recycleWipe)
            throws IOException, InterruptedException {
        if (parsed == null) {
            return new FolderDeleteResult(false, "No result returned by secure folder delete.", 0, 0, List.of());
        }
        List<String> pending = parsed.getScheduledForReboot();
        if (pending == null || pending.isEmpty()) return parsed;
        List<String> scheduled = new ArrayList<>();
        int failures = 0;
        for (String path : pending) {
            if (path == null || path.isBlank()) {
                failures++;
                continue;
            }
            if (recycleWipe && !isTrustedRecycleBinPath(path)) {
                AppLogger.warning("Skipping untrusted inner Recycle Bin path: " + path);
                failures++;
                continue;
            }
            try {
                ShredderResult sched = scheduleForReboot(path);
                if (sched.isSuccess()) {
                    scheduled.add(path);
                } else {
                    AppLogger.warning("Failed to schedule reboot delete: " + path
                            + " - " + (sched.getMessage() != null ? sched.getMessage() : "unknown"));
                    failures++;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception e) {
                AppLogger.error("Failed to schedule reboot delete: " + path, e);
                failures++;
            }
        }
        return applyScheduleOutcome(parsed, scheduled, failures);
    }

    static FolderDeleteResult applyScheduleOutcome(FolderDeleteResult parsed,
                                                   List<String> scheduled, int failures) {
        if (parsed == null) {
            return new FolderDeleteResult(false, "No result.", 0, 0, List.of());
        }
        List<String> done = scheduled != null ? scheduled : List.of();
        boolean success = parsed.isSuccess() && failures == 0;
        String msg = parsed.getMessage() != null ? parsed.getMessage() : "";
        if (failures > 0) {
            success = false;
            msg = msg + (msg.isBlank() ? "" : " ")
                    + failures + " file(s) could not be scheduled for reboot delete.";
        }
        return new FolderDeleteResult(success, msg.trim(), parsed.getFilesDeleted(),
                parsed.getFoldersDeleted(), done);
    }

    public ShredderResult scheduleForReboot(String filePath) throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("Reboot scheduling is only available on Windows.");
        }
        String blocked = ShredderSafety.validateForRebootDelete(filePath);
        if (blocked != null) {
            return new ShredderResult(filePath, false, false, false, "Blocked for safety: " + blocked);
        }
        Path script = PowerShellScripts.resolve("schedule-reboot-delete.ps1");
        ProcessResult result = runWithFallback(script.toString(), filePath);
        return parseResult(result, filePath);
    }

    private ProcessResult runWithFallback(String scriptPath, String... args) throws IOException, InterruptedException {
        IOException pending = null;
        for (List<String> cmd : List.of(ProcessRunner.powershellScriptNonInteractive(scriptPath, args), ProcessRunner.pwshScriptNonInteractive(scriptPath, args))) {
            try {
                return processRunner.run(cmd);
            } catch (IOException e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean missing = msg.contains("cannot run program") || msg.contains("no such file") || msg.contains("error=2");
                if (missing && cmd.get(0).equals("powershell.exe")) {
                    AppLogger.warning("Shredder: powershell.exe not found, trying pwsh.exe");
                    pending = e;
                    continue;
                }
                throw e;
            }
        }
        if (pending != null) throw pending;
        throw new IOException("No PowerShell executable available");
    }

    private ProcessResult runStreamingWithFallback(String scriptPath, Consumer<String> lineCallback,
                                                    Consumer<Double> progressCallback,
                                                    AtomicBoolean cancelled, String... args)
            throws IOException {
        IOException pending = null;
        for (List<String> cmd : List.of(ProcessRunner.powershellScriptNonInteractive(scriptPath, args),
                ProcessRunner.pwshScriptNonInteractive(scriptPath, args))) {
            try {
                return processRunner.runStreaming(cmd, lineCallback, progressCallback, cancelled);
            } catch (IOException e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean missing = msg.contains("cannot run program") || msg.contains("no such file") || msg.contains("error=2");
                if (missing && cmd.get(0).equals("powershell.exe")) {
                    AppLogger.warning("Shredder: powershell.exe not found, trying pwsh.exe");
                    pending = e;
                    continue;
                }
                throw e;
            }
        }
        if (pending != null) throw pending;
        throw new IOException("No PowerShell executable available");
    }

    private static String extractLastSuccessJson(String output) {
        if (output == null || output.isBlank()) return "";
        String last = "";
        for (String line : output.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("{") && t.contains("\"success\"")) last = t;
        }
        return last.isBlank() ? output.trim() : last;
    }

    public record RecycleBinResult(List<RecycleBinEntry> entries, long totalSizeBytes, int fileCount) {}

    /**
     * Trust boundary for Recycle Bin wipe: destructive input crosses COM -&gt; PS -&gt; JSON,
     * so every path must prove containment under X:\$Recycle.Bin\ and must not be a link.
     */
    private static boolean isTrustedRecycleBinPath(String rawPath) {
        return ShredderSafety.isTrustedRecycleBinPath(rawPath);
    }

    /** $R / $r leaf under X:\$Recycle.Bin\<sid>\ — the actual recycle payload. */
    static boolean isRecycleBinStorageName(String name) {
        if (name == null || name.length() < 2) return false;
        return name.charAt(0) == '$' && (name.charAt(1) == 'R' || name.charAt(1) == 'r');
    }

    static List<String> mergeRecycleWipeTargets(List<String> recyclePaths) {
        LinkedHashMap<String, String> byKey = new LinkedHashMap<>();
        if (recyclePaths != null) {
            for (String path : recyclePaths) {
                addRecycleWipeTarget(byKey, path);
            }
        }
        for (String found : listRecycleBinStorageItems()) {
            addRecycleWipeTarget(byKey, found);
        }
        return new ArrayList<>(byKey.values());
    }

    private static void addRecycleWipeTarget(LinkedHashMap<String, String> byKey, String path) {
        if (path == null || path.isBlank()) return;
        if (!isTrustedRecycleBinPath(path)) {
            AppLogger.warning("Skipping untrusted Recycle Bin path (outside $Recycle.Bin or link): " + path);
            return;
        }
        String key = path.replace('/', '\\').toLowerCase(Locale.ROOT);
        byKey.putIfAbsent(key, path);
    }

    static List<String> listRecycleBinStorageItems() {
        List<String> out = new ArrayList<>();
        File[] roots;
        try {
            roots = File.listRoots();
        } catch (Exception e) {
            return out;
        }
        if (roots == null) return out;
        for (File root : roots) {
            if (root == null) continue;
            Path rb = root.toPath().resolve("$Recycle.Bin");
            if (!isUsableDirectory(rb)) continue;
            try (DirectoryStream<Path> sids = Files.newDirectoryStream(rb)) {
                for (Path sid : sids) {
                    if (!isUsableDirectory(sid)) continue;
                    try (DirectoryStream<Path> kids = Files.newDirectoryStream(sid)) {
                        for (Path kid : kids) {
                            Path name = kid.getFileName();
                            if (name == null || !isRecycleBinStorageName(name.toString())) continue;
                            if (isReparsePoint(kid)) continue;
                            String abs = kid.toAbsolutePath().normalize().toString();
                            if (isTrustedRecycleBinPath(abs)) out.add(abs);
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static boolean isUsableDirectory(Path p) {
        try {
            return p != null
                    && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                    && !isReparsePoint(p);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isReparsePoint(Path p) {
        try {
            if (p == null || Files.isSymbolicLink(p)) return true;
            Object rp = Files.getAttribute(p, "dos:isReparsePoint", LinkOption.NOFOLLOW_LINKS);
            return rp instanceof Boolean && (Boolean) rp;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Deletes the $I metadata sibling of a shredded $R recycle file.
     * $Rxxx -&gt; $Ixxx in the same $Recycle.Bin directory. Best-effort only.
     */
    private static void deleteSiblingRecycleMetadata(String recyclePath) {
        try {
            if (recyclePath == null || recyclePath.isBlank()) return;
            if (!isTrustedRecycleBinPath(recyclePath)) return;
            File r = new File(recyclePath);
            String name = r.getName();
            if (name.length() < 2) return;
            // $R<id>.<ext> -> $I<id>.<ext>; also handle $R without extension
            String siblingName = null;
            if (name.startsWith("$R") || name.startsWith("$r")) {
                siblingName = "$I" + name.substring(2);
            } else {
                return;
            }
            File parent = r.getParentFile();
            if (parent == null) return;
            File sibling = new File(parent, siblingName);
            if (!isTrustedRecycleBinPath(sibling.getAbsolutePath())) return;
            if (sibling.exists()) {
                try {
                    java.nio.file.Files.deleteIfExists(sibling.toPath());
                    AppLogger.info("Removed orphan recycle metadata: " + sibling.getAbsolutePath());
                } catch (Exception e) {
                    AppLogger.warning("Could not remove recycle metadata " + sibling + ": " + e.getMessage());
                }
            }
        } catch (Exception ignored) {
        }
    }

    public RecycleBinResult getRecycleBinContents() throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("Recycle Bin is only available on Windows.");
        }
        Path script = PowerShellScripts.resolve("list-recyclebin.ps1");
        ProcessResult result = runWithFallback(script.toString());
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
            } else if (filesNode != null && filesNode.isObject()) {
                entries.add(JsonMapper.mapper().treeToValue(filesNode, RecycleBinEntry.class));
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
        int failed = 0;
        int skippedMissing = 0;
        boolean wasCancelled = false;

        // COM Path is often a shell namespace GUID, so recyclePath is empty even
        // when $R* files exist. Union trusted COM paths with a $Recycle.Bin walk.
        List<String> targets = mergeRecycleWipeTargets(recyclePaths);

        for (int i = 0; i < targets.size(); i++) {
            if (cancelled != null && cancelled.get()) {
                wasCancelled = true;
                break;
            }
            String path = targets.get(i);
            int current = i + 1;
            int total = targets.size();

            if (progressCallback != null) {
                progressCallback.accept("Securely deleting (" + current + "/" + total + "): " + new File(path).getName());
            }

            if (!isTrustedRecycleBinPath(path)) {
                AppLogger.warning("Skipping untrusted Recycle Bin path (outside $Recycle.Bin or link): " + path);
                failed++;
                continue;
            }
            File file = new File(path);
            if (!file.exists()) {
                skippedMissing++;
                continue;
            }

            try {
                boolean isDir = java.nio.file.Files.isDirectory(file.toPath(),
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (isDir) {
                    FolderDeleteResult fr = secureDeleteFolder(path, passCount, progressCallback, cancelled, true);
                    if (cancelled != null && cancelled.get()) {
                        wasCancelled = true;
                        break;
                    }
                    filesDeleted += fr.getFilesDeleted();
                    foldersDeleted += fr.getFoldersDeleted();
                    List<String> innerScheduled = fr.getScheduledForReboot() != null
                            ? fr.getScheduledForReboot() : List.of();
                    scheduledForReboot.addAll(innerScheduled);
                    if (!file.exists()) {
                        deleteSiblingRecycleMetadata(path);
                    }
                    if (!fr.isSuccess()) {
                        AppLogger.warning("Failed to securely delete recycle bin folder: " + path
                                + " - " + (fr.getMessage() != null ? fr.getMessage() : "unknown error"));
                        failed++;
                    }
                } else {
                    ShredderResult result = secureDelete(path, passCount, true);
                    if (result.isDeleted()) {
                        filesDeleted++;
                        // Reliability fix: deleting $R data directly leaves the $I metadata
                        // orphaned (ghost entries in Explorer). Remove the sibling $I file
                        // (plain delete — tiny metadata, no shred needed) after success.
                        deleteSiblingRecycleMetadata(path);
                    } else if (result.isScheduledForReboot()) {
                        try {
                            ShredderResult sched = scheduleForReboot(path);
                            if (sched.isSuccess()) {
                                scheduledForReboot.add(path);
                            } else {
                                AppLogger.warning("Failed to schedule recycle bin entry for reboot: " + path + " - " + sched.getMessage());
                                failed++;
                            }
                        } catch (Exception se) {
                            AppLogger.error("Failed to schedule reboot delete for: " + path, se);
                            failed++;
                        }
                    } else {
                        AppLogger.warning("Failed to securely delete recycle bin entry: " + path
                                + " - " + (result.getMessage() != null ? result.getMessage() : "unknown error"));
                        failed++;
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                if (msg.contains("in use") || msg.contains("access denied") || msg.contains("unauthorized")) {
                    try {
                        ShredderResult sched = scheduleForReboot(path);
                        if (sched.isSuccess()) {
                            scheduledForReboot.add(path);
                        } else {
                            AppLogger.warning("Failed to schedule recycle bin entry for reboot: " + path
                                    + " - " + sched.getMessage());
                            failed++;
                        }
                    } catch (Exception se) {
                        AppLogger.error("Failed to schedule reboot delete for: " + path, se);
                        failed++;
                    }
                } else {
                    AppLogger.error("Failed to securely delete recycle bin entry: " + path, e);
                    failed++;
                }
            }
        }

        if (wasCancelled || (cancelled != null && cancelled.get())) {
            return new FolderDeleteResult(false, "Recycle Bin wipe cancelled by user.",
                    filesDeleted, foldersDeleted, scheduledForReboot);
        }
        boolean anyHandled = filesDeleted > 0 || foldersDeleted > 0 || !scheduledForReboot.isEmpty();
        boolean success = anyHandled && failed == 0;
        String message;
        if (success) {
            message = "Recycle Bin wipe: " + filesDeleted + " files securely deleted"
                    + (foldersDeleted > 0 ? ", " + foldersDeleted + " folders removed." : ".");
        } else if (!anyHandled) {
            message = "Recycle Bin wipe failed: no items were deleted"
                    + (failed > 0 ? " (" + failed + " failed)" : "")
                    + (skippedMissing > 0 ? " (" + skippedMissing + " already gone)" : "")
                    + ". See app.log for details.";
        } else {
            message = "Recycle Bin wipe incomplete: " + filesDeleted + " deleted, "
                    + scheduledForReboot.size() + " scheduled for reboot, " + failed + " failed.";
        }
        return new FolderDeleteResult(success, message,
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
        // Blocker fix: createTempFile() creates the file, but Test-Path $StopFlagPath
        // in wipe-free-space.ps1 treats existence as "stop requested", so the wipe
        // would break immediately and report success without doing anything.
        // Delete it now; it is re-created only when cancellation is requested.
        try {
            java.nio.file.Files.deleteIfExists(stopFlag.toPath());
        } catch (Exception ignored) {
            // Fallback: best-effort delete; script checks existence only.
            stopFlag.delete();
        }

        java.util.List<String> driveFailures = new java.util.ArrayList<>();
        try {
            for (String driveLetter : driveLetters) {
                if (cancelled != null && cancelled.get()) break;

                // Track terminal (done=true) progress signals per drive so a silent
                // no-op or per-drive error cannot be reported as success.
                java.util.List<WipeProgress> doneSignals = new java.util.concurrent.CopyOnWriteArrayList<>();
                java.util.function.Consumer<WipeProgress> wrappedCallback = prog -> {
                    if (prog != null && prog.isDone()) {
                        doneSignals.add(prog);
                    }
                    if (progressCallback != null) {
                        progressCallback.accept(prog);
                    }
                };

                List<List<String>> candidates = List.of(
                        new ArrayList<>(ProcessRunner.powershellScriptNonInteractive(script.toString())),
                        new ArrayList<>(ProcessRunner.pwshScriptNonInteractive(script.toString()))
                );
                // Append args to each candidate
                for (List<String> c : candidates) {
                    c.add(driveLetter);
                    c.add("-StopFlagPath");
                    c.add(stopFlag.getAbsolutePath());
                    c.add("-PassCount");
                    c.add(String.valueOf(passCount));
                }
                Process process = null;
                IOException lastIo = null;
                for (List<String> cmd : candidates) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(cmd);
                        pb.redirectErrorStream(true);
                        process = ProcessManager.start(pb);
                        lastIo = null;
                        break;
                    } catch (IOException e) {
                        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                        boolean missing = msg.contains("cannot run program") || msg.contains("no such file") || msg.contains("error=2");
                        if (missing && cmd.get(0).equals("powershell.exe")) {
                            AppLogger.warning("Wipe: powershell.exe not found, trying pwsh.exe for " + driveLetter);
                            lastIo = e;
                            continue;
                        }
                        throw e;
                    }
                }
                if (process == null) {
                    if (lastIo != null) throw lastIo;
                    throw new IOException("No PowerShell executable available for wipe");
                }

                ProcessWatcher watcher = new ProcessWatcher(process, wrappedCallback, cancelled, stopFlag);
                Thread watcherThread = watcher.watch();

                // Cancellation poller: ensures stopFlag is created and process killed even if no output
                final Process procForPoller = process;
                Thread cancelPoller = new Thread(() -> {
                    try {
                        while (procForPoller.isAlive()) {
                            if (cancelled != null && cancelled.get()) {
                                try {
                                    if (!stopFlag.exists()) stopFlag.createNewFile();
                                } catch (Exception ignored) {}
                                // Give PowerShell a moment to observe stopFlag, then force kill
                                try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                                if (procForPoller.isAlive()) {
                                    try { procForPoller.destroyForcibly(); } catch (Exception ignored) {}
                                }
                                break;
                            }
                            try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        }
                    } catch (Exception ignored) {}
                }, "wipe-cancel-poller-" + driveLetter.replace(":", ""));
                cancelPoller.setDaemon(true);
                cancelPoller.start();

                try {
                    // No wall-clock kill: HDD free-space wipe of hundreds of GB
                    // (multi-pass) routinely exceeds 1 hour. Cancel poller + stop
                    // flag remain the only abort path.
                    process.waitFor();
                    try { cancelPoller.interrupt(); } catch (Exception ignored) {}
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    try { cancelPoller.interrupt(); } catch (Exception ignored) {}
                    Thread.currentThread().interrupt();
                    throw new IOException("Free space wipe interrupted.", e);
                }
                // Allow the async stream reader to deliver the terminal done=true signal
                // before evaluating per-drive success. Without this join, a fast exit
                // could be treated as success before the error line is parsed.
                try {
                    watcherThread.join(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (cancelled != null && cancelled.get()) {
                    break;
                }
                if (process.exitValue() != 0) {
                    throw new IOException("Free space wipe failed with exit code " + process.exitValue()
                            + " on drive " + driveLetter + ".");
                }
                if (doneSignals.isEmpty()) {
                    throw new IOException("Free space wipe produced no completion signal on drive "
                            + driveLetter + ". Aborted to avoid false success.");
                }
                WipeProgress errorSignal = null;
                for (WipeProgress p : doneSignals) {
                    if (isWipeTerminalError(p)) errorSignal = p;
                }
                if (errorSignal != null) {
                    String detail = errorSignal.getMessage() != null && !errorSignal.getMessage().isBlank()
                            ? errorSignal.getMessage() : "unknown error (no completion message)";
                    driveFailures.add(driveLetter + ": " + detail);
                    if (driveLetters.size() <= 1) {
                        throw new IOException("Free space wipe failed on drive " + driveLetter + ": " + detail);
                    }
                    continue;
                }
                WipeProgress terminal = doneSignals.get(doneSignals.size() - 1);
                String terminalMsg = terminal.getMessage() != null ? terminal.getMessage() : "";
                String lowerMsg = terminalMsg.toLowerCase();
                if (lowerMsg.contains("stopped by user")) {
                    continue;
                }
            }
            if ((cancelled == null || !cancelled.get()) && !driveFailures.isEmpty()) {
                throw new IOException("Free space wipe failed: " + String.join("; ", driveFailures));
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

    static boolean isWipeTerminalError(WipeProgress p) {
        if (p == null || !p.isDone()) return false;
        String m = p.getMessage() != null ? p.getMessage().toLowerCase() : "";
        if (m.contains("wipe completed")) return false;
        if (m.contains("stopped by user")) return false;
        if (m.contains("insufficient") || m.contains("not found") || m.contains("drive not found")
                || m.contains("failed") || m.contains("timed out") || m.contains("no completion")
                || m.contains("blocked") || m.contains("ssd")) {
            return true;
        }
        return p.getPercent() == 0 && p.getPass() == 0;
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

        Thread watch() {
            Thread t = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (cancelled != null && cancelled.get()) {
                            try { if (!stopFlag.exists()) stopFlag.createNewFile(); } catch (Exception ignored) {}
                            try { process.destroyForcibly(); } catch (Exception ignored) {}
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
            return t;
        }
    }

    private ShredderResult parseResult(ProcessResult result, String filePath) {
        if (!result.success()) {
            return new ShredderResult(filePath, false, false, false, "Process failed: " + result.combinedOutput());
        }
        // Reliability fix: scripts may emit progress/noise lines; parse the last
        // {success} line instead of the whole stdout blob.
        String json = extractLastSuccessJson(result.stdout());
        if (json.isBlank()) {
            return new ShredderResult(filePath, false, false, false, "No result returned.");
        }
        try {
            return JsonMapper.mapper().readValue(json, ShredderResult.class);
        } catch (Exception e) {
            AppLogger.error("Failed to parse shredder result", e);
            return new ShredderResult(filePath, false, false, false, "Parse error: " + e.getMessage());
        }
    }
}
