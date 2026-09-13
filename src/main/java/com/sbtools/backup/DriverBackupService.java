package com.sbtools.backup;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.settings.AppSettings;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class DriverBackupService {

    private static final java.util.concurrent.ConcurrentHashMap<Path, ReentrantReadWriteLock> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Serializes merged index reads and cross-file index mutations in-process. */
    private static final ReentrantReadWriteLock INDEX_STORE_LOCK = new ReentrantReadWriteLock(true);
    static final java.util.regex.Pattern RECORDED_INF_NAME = java.util.regex.Pattern.compile("(?i)[\\w\\-]+\\.inf");
    private static final java.util.regex.Pattern BACKUP_LEAF_DIR =
            java.util.regex.Pattern.compile("\\d+_[0-9a-fA-F]{8}");

    private static ReentrantReadWriteLock lockFor(Path indexPath) {
        return LOCKS.computeIfAbsent(indexPath.toAbsolutePath().normalize(), k -> new ReentrantReadWriteLock());
    }
    // Short-TTL cache for allowed backup roots: avoids re-reading
    // SettingsStore + index files on every size/health check (was O(n^2)).
    private static volatile List<Path> cachedAllowedRoots;
    private static volatile long cachedAllowedRootsAt;
    private static final long ALLOWED_ROOTS_TTL_MS = 5_000;
    private final ProcessRunner processRunner = new ProcessRunner(300);

    public List<DriverBackupEntry> listAll() throws IOException {
        INDEX_STORE_LOCK.readLock().lock();
        try {
            // Null-safe: a single corrupt entry (createdAt=null) must never NPE the whole tab.
            return loadIndex().getEntries().stream()
                    .sorted(Comparator.comparing(DriverBackupEntry::createdAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .collect(Collectors.toList());
        } finally {
            INDEX_STORE_LOCK.readLock().unlock();
        }
    }

    public List<DriverBackupEntry> listBackups(String deviceId) throws IOException {
        INDEX_STORE_LOCK.readLock().lock();
        try {
            return loadIndex().getEntries().stream()
                    .filter(e -> deviceId != null && deviceId.equals(e.deviceId()))
                    .sorted(Comparator.comparing(DriverBackupEntry::createdAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .collect(Collectors.toList());
        } finally {
            INDEX_STORE_LOCK.readLock().unlock();
        }
    }

    /**
     * @return human-readable reason when automatic backup is not supported, or null when supported
     */
    public static String backupSupportIssue(InstalledDriver driver) {
        if (driver == null) {
            return "device information unavailable";
        }
        String inf = driver.infName();
        if (inf == null || inf.isBlank()) {
            return "INF name not available for " + driver.friendlyName();
        }
        if (!inf.matches("(?i)[\\w\\-]+\\.inf")) {
            return "unexpected INF name \"" + inf + "\"";
        }
        if (driver.deviceId() == null || driver.deviceId().isBlank()) {
            return "device ID not available";
        }
        return null;
    }

    public DriverBackupEntry backupBeforeUpdate(InstalledDriver driver, AppSettings settings)
            throws IOException, InterruptedException {
        return backupBeforeUpdate(driver, settings, null);
    }

    public DriverBackupEntry backupBeforeUpdate(InstalledDriver driver, AppSettings settings,
            java.util.concurrent.atomic.AtomicBoolean cancelled)
            throws IOException, InterruptedException {
        String supportIssue = backupSupportIssue(driver);
        if (supportIssue != null) {
            throw new IOException("Cannot backup driver: " + supportIssue
                    + ". Automatic backup is not supported for this device.");
        }
        String inf = driver.infName();
        if (driver.deviceId() == null || driver.deviceId().isBlank()) {
            throw new IOException("Cannot backup driver: device ID not available.");
        }
        String safeId = sanitizeDeviceId(driver.deviceId());
        Path root = AppPaths.backupsRoot(settings);
        Instant now = Instant.now();
        Path folder = root
                .resolve(safeId)
                .resolve(now.toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8));
        // Tracks the portable-fallback below so the index is written next to
        // the actual files (never primary-index + fallback-files split).
        boolean usedFallbackRoot = false;
        try {
            Files.createDirectories(folder);
        } catch (IOException dirEx) {
            // Portable fallback: exe-dir installs (Program Files) are not
            // writable. Fall back to LOCALAPPDATA instead of silently
            // skipping the safety net.
            Path fallbackRoot = AppPaths.legacyBackupsRoot();
            Path fallback = fallbackRoot.resolve(safeId)
                    .resolve(now.toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8));
            try {
                Files.createDirectories(fallback);
                AppLogger.warning("Backups root not writable (" + root + "), using fallback " + fallbackRoot);
                folder = fallback;
                usedFallbackRoot = true;
            } catch (IOException fallbackEx) {
                throw new IOException("Driver backup directory not writable: " + root
                        + " (fallback " + fallbackRoot + " also failed: " + fallbackEx.getMessage() + ")", dirEx);
            }
        }

        Path script = PowerShellScripts.resolve("pnputil-backup.ps1");
        ProcessResult result;
        try {
            // Non-interactive: prompts hang to the 300s timeout.
            result = cancelled == null
                    ? processRunner.run(ProcessRunner.powershellScriptNonInteractive(
                            script.toString(), inf, folder.toString()))
                    : processRunner.run(ProcessRunner.powershellScriptNonInteractive(
                            script.toString(), inf, folder.toString()), cancelled);
        } catch (java.util.concurrent.CancellationException | InterruptedException cancelEx) {
            // Stop during backup: remove the orphan folder, propagate cancel
            // (callers map this to "cancelled", never "proceed without backup").
            try { deleteDirectory(folder); } catch (Exception ignored) {}
            throw cancelEx;
        }
        if (!result.success()) {
            // clean up empty folder on failure
            try { deleteDirectory(folder); } catch (Exception ignored) {}
            throw new IOException("Driver backup failed: " + result.combinedOutput());
        }
        // Verify backup actually produced files - fail fast if no INF was exported
        if (countInfFiles(folder) == 0) {
            try { deleteDirectory(folder); } catch (Exception ignored) {}
            throw new IOException("Driver backup produced no INF files in " + folder
                    + ". The driver may not be exported via pnputil on this system or the INF name is incorrect.");
        }

        DriverBackupEntry entry = new DriverBackupEntry(
                UUID.randomUUID().toString(),
                driver.deviceId(),
                driver.friendlyName(),
                now,
                folder.toString(),
                driver.driverVersion(),
                inf
        );

        // Index next to the actual files: on fallback the primary index is
        // unwritable by definition, so writing it there would throw and orphan
        // the files (unrevertable). Load merges all locations on read.
        final Path idx = usedFallbackRoot
                ? AppPaths.legacyBackupsRoot().resolve("index.json")
                : indexPath(settings);
        final Path savedFolder = folder;
        INDEX_STORE_LOCK.writeLock().lock();
        try {
            ReentrantReadWriteLock lock = lockFor(idx);
            lock.writeLock().lock();
            try {
                BackupIndex index = loadIndex(settings);
                index.getEntries().add(entry);
                try {
                    if (usedFallbackRoot) {
                        saveIndexToPath(index, idx);
                    } else {
                        saveIndex(index, settings);
                    }
                } catch (IOException | RuntimeException saveEx) {
                    // No index, no rollback: remove the files rather than leak an
                    // orphan backup the UI can never revert.
                    try { deleteDirectory(savedFolder); } catch (Exception ignored) {}
                    throw saveEx;
                }
            } finally {
                lock.writeLock().unlock();
            }
        } finally {
            INDEX_STORE_LOCK.writeLock().unlock();
        }

        AppLogger.info("Driver backup created: " + entry.friendlyName()
                + " v" + entry.version() + " [" + entry.id() + "] -> " + folder);
        return entry;
    }

    public void revert(DriverBackupEntry entry) throws IOException, InterruptedException {
        if (entry == null || entry.backupFolder() == null || entry.backupFolder().isBlank()) {
            throw new IOException("Invalid backup entry");
        }
        Path folder = Path.of(entry.backupFolder());
        if (!BackupHealth.isPathShapeSafe(folder)) {
            throw new IOException("Refusing to revert from unsafe folder: " + folder);
        }
        if (!isUnderCurrentRoots(folder)) {
            throw new IOException("Backup is outside the current backup locations: " + folder
                    + ". Point the backup directory back to its original location and retry.");
        }
        String infName = entry.infName();
        if (infName == null || !RECORDED_INF_NAME.matcher(infName).matches()) {
            throw new IOException("This backup has no recorded INF name. Automatic revert is not supported.\n"
                    + "Use Device Manager → Update driver → Browse → Let me pick → Have Disk\n"
                    + "and point at: " + folder);
        }
        int infMatches = BackupHealth.countMatchingInfFiles(folder, infName);
        if (infMatches < 0) {
            throw new IOException("Backup folder is too large to verify: " + folder);
        }
        if (infMatches != 1) {
            throw new IOException("Expected exactly one " + infName + " in backup folder, found " + infMatches
                    + ". Refusing ambiguous revert.");
        }
        if (!Files.isDirectory(folder)) {
            throw new IOException("Backup folder missing: " + folder);
        }

        long infCount = countInfFiles(folder);
        if (infCount == 0) {
            throw new IOException("Backup folder contains no .inf files: " + folder);
        }

        AppLogger.info("Reverting driver: " + entry.friendlyName()
                + " from backup [" + entry.id() + "] (" + infCount + " INF file(s))");

        Path script = PowerShellScripts.resolve("pnputil-restore.ps1");
        // deviceId may be null on corrupt index entries — script arg is optional, never pass null
        // into ProcessBuilder (would NPE). Empty string means "stage only, skip device restart".
        String deviceArg = entry.deviceId() != null ? entry.deviceId() : "";
        // Wildcards would let a planted index entry disable an arbitrary
        // device (Get-PnpDevice -InstanceId globs). Stage-only fallback.
        if (deviceArg.matches(".*[*?\\[\\]].*")) {
            AppLogger.warning("Revert: deviceId contains wildcards, skipping device restart for entry " + entry.id());
            deviceArg = "";
        }
        String infArg = infName;
        // Non-interactive: prompts hang to the timeout.
        ProcessResult result = processRunner.run(ProcessRunner.powershellScriptNonInteractive(
                script.toString(), folder.toString(), deviceArg, infArg));
        RevertDetail detail = parseRevertOutput(result.stdout());
        if (!result.success()) {
            String msg = "Driver revert failed for " + entry.friendlyName()
                    + " (installed " + detail.installed() + "/" + infCount
                    + ", failed " + detail.failed() + ").\n";
            if (detail.details() != null && !detail.details().isBlank()) {
                String d = detail.details();
                msg += d.length() > 1500 ? d.substring(0, 1500) + "…" : d;
                msg += "\n";
            } else if (!result.combinedOutput().isBlank()) {
                String out = result.combinedOutput();
                msg += out.length() > 1500 ? out.substring(0, 1500) + "…" : out;
                msg += "\n";
            }
            msg += "The backup was staged but Windows did not switch the active driver.\n"
                    + "Reboot, then use Device Manager → Update driver → Browse → Let me pick → Have Disk\n"
                    + "and point at: " + folder;
            throw new IOException(msg);
        }

        AppLogger.info("Driver staged from backup: " + entry.friendlyName()
                + " (installed " + detail.installed() + "/" + infCount
                + ", restartAttempted=" + detail.restartAttempted()
                + ", restartOk=" + detail.restartOk() + ")"
                + " — active-driver bind still requires UI verification (downgrade may need reboot/manual Have-Disk).");
    }

    private record RevertDetail(int installed, int failed, String details,
                                boolean restartAttempted, boolean restartOk) {}

    private static RevertDetail parseRevertOutput(String stdout) {
        if (stdout == null || stdout.isBlank()) return new RevertDetail(-1, -1, "", false, false);
        try {
            // Script emits a single compressed JSON object; output may contain extra lines.
            String json = stdout.trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) json = json.substring(start, end + 1);
            var tree = JsonMapper.mapper().readTree(json);
            int installed = tree.path("installed").asInt(-1);
            int failed = tree.path("failed").asInt(-1);
            String details = tree.path("details").asText("");
            boolean restartAttempted = tree.path("restartAttempted").asBoolean(false);
            boolean restartOk = tree.path("restartOk").asBoolean(false);
            return new RevertDetail(installed, failed, details, restartAttempted, restartOk);
        } catch (Exception ignored) {
            return new RevertDetail(-1, -1, "", false, false);
        }
    }

    public void removeBackupEntry(DriverBackupEntry entry) throws IOException {
        if (entry == null || entry.id() == null) return;
        Path folder;
        try {
            folder = Path.of(entry.backupFolder());
        } catch (Exception e) {
            throw new IOException("Invalid backup folder: " + entry.backupFolder());
        }
        if (Files.isDirectory(folder)) {
            if (!isSafeToDelete(folder, entry)) {
                throw new IOException("Refusing to delete folder outside current backup locations: " + folder
                        + ". Point the backup directory to its original location, or use Repair to remove only the index entry.");
            }
            deleteDirectory(folder);
            cleanupEmptyParent(folder.getParent());
        }
        purgeFromAllIndexes(java.util.Set.of(entry.id()));
    }

    public void removeAll() throws IOException {
        List<DriverBackupEntry> entriesToDelete = listAll();
        java.util.List<String> folderFailures = new java.util.ArrayList<>();
        java.util.Set<String> purgedIds = new java.util.HashSet<>();
        java.util.Set<Path> cleanedParents = new java.util.HashSet<>();
        for (DriverBackupEntry entry : entriesToDelete) {
            if (entry == null || entry.id() == null) {
                continue;
            }
            Path folder;
            try {
                folder = Path.of(entry.backupFolder());
            } catch (Exception e) {
                folderFailures.add(entry.id() + ": invalid path");
                continue;
            }
            if (Files.isDirectory(folder)) {
                if (!isSafeToDelete(folder, entry)) {
                    folderFailures.add(folder.toString() + ": outside current backup roots");
                    continue;
                }
                try {
                    deleteDirectory(folder);
                    Path parent = folder.getParent();
                    if (parent != null && cleanedParents.add(parent)) {
                        cleanupEmptyParent(parent);
                    }
                } catch (IOException e) {
                    folderFailures.add(folder + ": " + e.getMessage());
                    continue;
                }
            }
            purgedIds.add(entry.id());
        }
        if (!purgedIds.isEmpty()) {
            purgeFromAllIndexes(purgedIds);
        }
        if (!folderFailures.isEmpty()) {
            throw new IOException("Deleted " + purgedIds.size() + " backup(s); "
                    + folderFailures.size() + " could not be removed completely:\n"
                    + String.join("\n", folderFailures.subList(0, Math.min(5, folderFailures.size())))
                    + (folderFailures.size() > 5 ? "\n…" : ""));
        }
        AppLogger.info("All driver backups removed (" + entriesToDelete.size() + ")");
    }

    private void purgeFromAllIndexes(java.util.Set<String> idsToRemove) throws IOException {
        if (idsToRemove == null || idsToRemove.isEmpty()) return;
        java.util.List<Path> allPaths = allIndexPaths().stream()
                .map(p -> p.toAbsolutePath().normalize())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        java.util.List<String> errors = new java.util.ArrayList<>();
        INDEX_STORE_LOCK.writeLock().lock();
        try {
            for (Path p : allPaths) {
                ReentrantReadWriteLock lock = lockFor(p);
                lock.writeLock().lock();
                try {
                    if (!Files.exists(p) && !Files.exists(p.resolveSibling(p.getFileName().toString() + ".bak"))) {
                        continue;
                    }
                    BackupIndex idx = loadSingleIndex(p);
                    boolean changed = idx.getEntries().removeIf(e -> e != null && e.id() != null && idsToRemove.contains(e.id()));
                    if (changed) {
                        saveIndexToPath(idx, p);
                    }
                } catch (IOException ex) {
                    errors.add(p + ": " + ex.getMessage());
                } finally {
                    lock.writeLock().unlock();
                }
            }
        } finally {
            INDEX_STORE_LOCK.writeLock().unlock();
        }
        if (!errors.isEmpty()) {
            throw new IOException("Failed to update backup index: " + String.join("; ", errors));
        }
    }

    private void cleanupEmptyParent(Path parent) {
        if (parent == null || !Files.isDirectory(parent)) return;
        // Only delete parent if it is directly under backupsRoot and empty
        try {
            Path backupsRoot = indexPath().getParent();
            if (backupsRoot == null || !parent.startsWith(backupsRoot)) return;
            try (var stream = Files.list(parent)) {
                if (stream.findFirst().isEmpty()) {
                    Files.deleteIfExists(parent);
                }
            }
        } catch (IOException e) {
            AppLogger.warning("Could not clean parent: " + parent, e);
        }
    }

    private static List<Path> cachedAllowedRoots() {
        long now = System.currentTimeMillis();
        List<Path> cached = cachedAllowedRoots;
        if (cached != null && (now - cachedAllowedRootsAt) < ALLOWED_ROOTS_TTL_MS) {
            return cached;
        }
        List<Path> roots = new java.util.ArrayList<>();
        try {
            Path primaryRoot = AppPaths.backupsRoot().toAbsolutePath().normalize();
            roots.add(primaryRoot);
        } catch (Exception ignored) {
        }
        try {
            com.sbtools.settings.AppSettings s = new com.sbtools.settings.SettingsStore().load();
            if (s.backupDirectory() != null && !s.backupDirectory().isBlank()) {
                Path custom = Path.of(s.backupDirectory()).toAbsolutePath().normalize();
                if (AppPaths.isValidCustomBackupDir(custom) && !roots.contains(custom)) {
                    roots.add(custom);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            Path legacy = AppPaths.legacyBackupsRoot().toAbsolutePath().normalize();
            if (!roots.contains(legacy)) {
                roots.add(legacy);
            }
        } catch (Exception ignored) {
        }
        cachedAllowedRoots = roots;
        cachedAllowedRootsAt = now;
        return roots;
    }

    /**
     * Current-roots membership for destructive paths (revert): shape + depth +
     * under a present-day allowed root. Unlike {@link #isSafeToDelete}, it never
     * consults index references — an index entry must not authorize itself.
     */
    private boolean isUnderCurrentRoots(Path folder) {
        if (folder == null) return false;
        if (!BackupHealth.isPathShapeSafe(folder)) return false;
        try {
            Path normalized = folder.toAbsolutePath().normalize();
            if (normalized.getNameCount() < 2) return false;
            java.util.List<Path> allowedRoots = cachedAllowedRoots();
            try {
                Path primaryRoot = indexPath().getParent();
                if (primaryRoot != null) {
                    Path normPrimary = primaryRoot.toAbsolutePath().normalize();
                    if (!allowedRoots.contains(normPrimary)) {
                        allowedRoots = new java.util.ArrayList<>(allowedRoots);
                        allowedRoots.add(normPrimary);
                    }
                }
            } catch (Exception ignored) {}
            for (Path root : allowedRoots) {
                if (normalized.startsWith(root)) {
                    Path rel = root.relativize(normalized);
                    if (rel.getNameCount() >= 2) return true;
                    return false;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Fast read-only guard for size/health scans: shape + allowed-roots only,
     * no index scan. Keeps per-entry scans O(1) instead of O(indexes).
     */
    private boolean isSafeForRead(Path folder) {
        if (folder == null) {
            return false;
        }
        if (!BackupHealth.isPathShapeSafe(folder)) {
            return false;
        }
        try {
            Path normalized = folder.toAbsolutePath().normalize();
            for (Path root : cachedAllowedRoots()) {
                if (normalized.startsWith(root)) {
                    Path rel = root.relativize(normalized);
                    if (rel.getNameCount() >= 2) {
                        return true;
                    }
                    return false;
                }
            }
            return isIndexedBackupFolder(folder);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isSafeToDelete(Path folder, DriverBackupEntry entry) {
        if (folder == null || !BackupHealth.isPathShapeSafe(folder)) {
            return false;
        }
        try {
            Path normalized = folder.toAbsolutePath().normalize();
            if (!isUnderCurrentRoots(normalized)) {
                return false;
            }
            if (normalized.getNameCount() < 2) {
                return false;
            }
            String leaf = normalized.getFileName().toString();
            if (!BACKUP_LEAF_DIR.matcher(leaf).matches()) {
                return false;
            }
            if (entry != null && entry.deviceId() != null && !entry.deviceId().isBlank()) {
                Path parent = normalized.getParent();
                if (parent == null) {
                    return false;
                }
                String expectedParent = sanitizeDeviceId(entry.deviceId());
                if (!parent.getFileName().toString().equals(expectedParent)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String sanitizeDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return "unknown";
        }
        String safe = deviceId.replaceAll("[^a-zA-Z0-9_-]", "_");
        return safe.isBlank() ? "unknown" : safe;
    }

    private static boolean isValidCustomRoot(Path custom) {
        return AppPaths.isValidCustomBackupDir(custom);
    }

    public long getTotalSize() throws IOException {
        return getTotalSize(listAll());
    }

    public long getTotalSize(List<DriverBackupEntry> entries) throws IOException {
        long total = 0;
        if (entries == null) {
            return 0;
        }
        for (DriverBackupEntry entry : entries) {
            try {
                if (entry == null || entry.backupFolder() == null || entry.backupFolder().isBlank()) {
                    continue;
                }
                Path folder = Path.of(entry.backupFolder());
                if (!isSafeForRead(folder)) {
                    AppLogger.warning("Skipping size calculation for unsafe folder: " + folder);
                    continue;
                }
                if (Files.isDirectory(folder)) {
                    // Single-pass stats (bytes + INF) shared with UI health checks.
                    total += BackupHealth.inspect(folder).bytes();
                }
            } catch (Exception ignored) {}
        }
        return total;
    }

    /**
     * Returns index entries whose folder is missing, unsafe, unreadable or
     * contains no INF files. Used for the manual "Repair" affordance — this
     * method never deletes anything.
     */
    public List<DriverBackupEntry> findStaleEntries() throws IOException {
        List<DriverBackupEntry> all = listAll();
        List<DriverBackupEntry> stale = new java.util.ArrayList<>();
        for (DriverBackupEntry e : all) {
            if (e == null) {
                continue;
            }
            try {
                BackupHealth.Stats stats = BackupHealth.inspect(e.backupFolder());
                if (!BackupHealth.isHealthy(stats.status())) {
                    stale.add(e);
                }
            } catch (Exception ex) {
                stale.add(e);
            }
        }
        return stale;
    }

    /**
     * Removes only the given stale entries from all indexes (manual repair).
     * Folders that still exist are left on disk; use
     * {@link #removeBackupEntry} for full folder deletion.
     */
    public void purgeStaleIndexEntries(List<DriverBackupEntry> stale) throws IOException {
        if (stale == null || stale.isEmpty()) {
            return;
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (DriverBackupEntry e : stale) {
            if (e != null && e.id() != null) {
                ids.add(e.id());
            }
        }
        purgeFromAllIndexes(ids);
        AppLogger.info("Purged " + ids.size() + " stale backup index entries (folders left untouched)");
    }

    /** Free bytes available on the current backups volume, or -1 if unknown. */
    public long usableSpaceForBackups() {
        try {
            Path root = indexPath().getParent();
            if (root == null) {
                root = AppPaths.backupsRoot();
            }
            return BackupHealth.usableSpace(root);
        } catch (Exception e) {
            return -1;
        }
    }

    private long countInfFiles(Path folder) throws IOException {
        return BackupHealth.inspect(folder).infCount();
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        java.util.List<Path> failed = new java.util.ArrayList<>();
        try (var stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try { Files.setAttribute(path, "dos:readonly", Boolean.FALSE); } catch (Exception ignored) {}
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            failed.add(path);
                            AppLogger.warning("Could not delete: " + path, e);
                        }
                    });
        }
        if (!failed.isEmpty() || Files.exists(directory)) {
            throw new IOException("Could not delete backup folder completely: " + directory
                    + (failed.isEmpty() ? "" : " (" + failed.size() + " path(s) failed)"));
        }
    }

    private boolean isIndexedBackupFolder(Path folder) {
        if (folder == null || !BackupHealth.isPathShapeSafe(folder)) {
            return false;
        }
        try {
            Path normalized = folder.toAbsolutePath().normalize();
            String leaf = normalized.getFileName().toString();
            if (!BACKUP_LEAF_DIR.matcher(leaf).matches()) {
                return false;
            }
            for (Path idxPath : allIndexPaths()) {
                try {
                    BackupIndex idx = loadSingleIndex(idxPath);
                    for (DriverBackupEntry e : idx.getEntries()) {
                        if (e == null || e.backupFolder() == null) continue;
                        if (Path.of(e.backupFolder()).toAbsolutePath().normalize().equals(normalized)) {
                            return true;
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return false;
    }

    private Path indexPath() {
        // Resolve settings-aware index path; fallback to portable/legacy with merge support
        try {
            com.sbtools.settings.AppSettings s = new com.sbtools.settings.SettingsStore().load();
            Path withSettings = indexPath(s);
            // Validate custom path - if invalid, fallback to portable
            if (withSettings != null) return withSettings;
        } catch (Exception ignored) {}
        return AppPaths.backupIndexNoCreate();
    }

    private Path indexPath(com.sbtools.settings.AppSettings settings) {
        if (settings != null && settings.backupDirectory() != null && !settings.backupDirectory().isBlank()) {
            String raw = settings.backupDirectory().trim();
            if (!raw.isBlank()) {
                Path custom = Path.of(raw);
                if (isValidCustomRoot(custom.toAbsolutePath().normalize())) {
                    return custom.resolve("index.json");
                } else {
                    AppLogger.warning("Ignoring invalid backupDirectory: " + raw);
                }
            }
        }
        return AppPaths.backupIndexNoCreate();
    }

    private java.util.List<Path> fallbackIndexPaths(Path primary) {
        java.util.List<Path> fallbacks = new java.util.ArrayList<>();
        Path portableIdx = AppPaths.backupIndexNoCreate();
        Path legacyIdx = AppPaths.legacyBackupsRoot().resolve("index.json");
        if (!portableIdx.equals(primary)) fallbacks.add(portableIdx);
        if (!legacyIdx.equals(primary) && !legacyIdx.equals(portableIdx)) fallbacks.add(legacyIdx);
        return fallbacks;
    }

    private java.util.List<Path> allIndexPaths() {
        Path primary = indexPath();
        java.util.List<Path> all = new java.util.ArrayList<>();
        all.add(primary);
        all.addAll(fallbackIndexPaths(primary));
        return all.stream().distinct().collect(Collectors.toList());
    }

    private BackupIndex loadIndex() throws IOException {
        return loadIndex(null);
    }

    private BackupIndex loadIndex(com.sbtools.settings.AppSettings settings) throws IOException {
        Path primary = settings != null ? indexPath(settings) : indexPath();
        BackupIndex merged = new BackupIndex();
        java.util.Set<String> seenIds = new java.util.HashSet<>();

        // Primary
        BackupIndex primaryIdx = loadSingleIndex(primary);
        for (DriverBackupEntry e : primaryIdx.getEntries()) {
            if (e != null && e.id() != null && seenIds.add(e.id())) {
                merged.getEntries().add(e);
            }
        }

        // Fallback: also check portable and legacy if different from primary, to avoid hiding old backups
        for (Path fb : fallbackIndexPaths(primary)) {
            if (Files.exists(fb)) {
                try {
                    BackupIndex fbIdx = loadSingleIndex(fb);
                    for (DriverBackupEntry e : fbIdx.getEntries()) {
                        if (e != null && e.id() != null && seenIds.add(e.id())) {
                            merged.getEntries().add(e);
                        }
                    }
                    if (!fbIdx.getEntries().isEmpty()) {
                        AppLogger.info("Loaded " + fbIdx.getEntries().size() + " entries from fallback index " + fb);
                    }
                } catch (Exception ex) {
                    AppLogger.warning("Failed to load fallback index " + fb + ": " + ex.getMessage());
                }
            }
        }

        // Filter out entries with missing backup folder or null id to avoid NPE downstream
        merged.getEntries().removeIf(e -> e == null || e.id() == null || e.backupFolder() == null || e.backupFolder().isBlank());
        // Prune entries whose backup folder no longer exists on disk (stale index after manual delete)
        // Keep them if folder missing but we have not yet cleaned? For now keep missing but mark - UI will show  — size.
        return merged;
    }

    private BackupIndex loadSingleIndex(Path path) throws IOException {
        if (!Files.exists(path)) {
            // Try .bak
            Path bak = path.resolveSibling(path.getFileName().toString() + ".bak");
            if (Files.exists(bak)) {
                try {
                    return JsonMapper.mapper().readValue(bak.toFile(), BackupIndex.class);
                } catch (Exception ignored) {}
            }
            return new BackupIndex();
        }
        try {
            return JsonMapper.mapper().readValue(path.toFile(), BackupIndex.class);
        } catch (IOException e) {
            // Try .bak on corrupted primary
            Path bak = path.resolveSibling(path.getFileName().toString() + ".bak");
            if (Files.exists(bak)) {
                try {
                    AppLogger.warning("Primary index corrupted, loading backup: " + bak);
                    return JsonMapper.mapper().readValue(bak.toFile(), BackupIndex.class);
                } catch (Exception ignored) {}
            }
            throw e;
        }
    }

    private void saveIndex(BackupIndex index) throws IOException {
        saveIndex(index, null);
    }

    private void saveIndex(BackupIndex index, com.sbtools.settings.AppSettings settings) throws IOException {
        Path path = settings != null ? indexPath(settings) : indexPath();
        saveIndexToPath(index, path);
    }

    private void saveIndexToPath(BackupIndex index, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        // Atomic write via temp file + backup
        Path tmp = path.resolveSibling("." + path.getFileName().toString() + ".tmp");
        Path bak = path.resolveSibling(path.getFileName().toString() + ".bak");
        try {
            JsonMapper.mapper().writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), index);
            // Keep backup of previous
            if (Files.exists(path)) {
                try { Files.copy(path, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
            }
            try {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
        }
    }
}
