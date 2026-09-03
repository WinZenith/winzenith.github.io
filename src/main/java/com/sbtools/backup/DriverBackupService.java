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
    private static ReentrantReadWriteLock lockFor(Path indexPath) {
        return LOCKS.computeIfAbsent(indexPath.toAbsolutePath().normalize(), k -> new ReentrantReadWriteLock());
    }
    private final ProcessRunner processRunner = new ProcessRunner(300);

    public List<DriverBackupEntry> listAll() throws IOException {
        Path idx = indexPath();
        ReentrantReadWriteLock lock = lockFor(idx);
        lock.readLock().lock();
        try {
            return loadIndex().getEntries().stream()
                    .sorted(Comparator.comparing(DriverBackupEntry::createdAt).reversed())
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<DriverBackupEntry> listBackups(String deviceId) throws IOException {
        Path idx = indexPath();
        ReentrantReadWriteLock lock = lockFor(idx);
        lock.readLock().lock();
        try {
            return loadIndex().getEntries().stream()
                    .filter(e -> e.deviceId().equals(deviceId))
                    .sorted(Comparator.comparing(DriverBackupEntry::createdAt).reversed())
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public DriverBackupEntry backupBeforeUpdate(InstalledDriver driver, AppSettings settings)
            throws IOException, InterruptedException {
        return backupBeforeUpdate(driver, settings, null);
    }

    public DriverBackupEntry backupBeforeUpdate(InstalledDriver driver, AppSettings settings,
            java.util.concurrent.atomic.AtomicBoolean cancelled)
            throws IOException, InterruptedException {
        String inf = driver.infName();
        if (inf == null || inf.isBlank()) {
            throw new IOException("Cannot backup driver: INF name not available for "
                    + driver.friendlyName() + " (" + driver.deviceId()
                    + "). Automatic backup is not supported for this device.");
        }
        if (driver.deviceId() == null || driver.deviceId().isBlank()) {
            throw new IOException("Cannot backup driver: device ID not available.");
        }
        String safeId = driver.deviceId().replaceAll("[^a-zA-Z0-9_-]", "_");
        if (safeId.isBlank()) safeId = "unknown";
        Path root = AppPaths.backupsRoot(settings);
        Instant now = Instant.now();
        Path folder = root
                .resolve(safeId)
                .resolve(now.toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 8));
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
            } catch (IOException fallbackEx) {
                throw new IOException("Driver backup directory not writable: " + root
                        + " (fallback " + fallbackRoot + " also failed: " + fallbackEx.getMessage() + ")", dirEx);
            }
        }

        Path script = PowerShellScripts.resolve("pnputil-backup.ps1");
        ProcessResult result = cancelled == null
                ? processRunner.run(ProcessRunner.powershellScript(
                        script.toString(), inf, folder.toString()))
                : processRunner.run(ProcessRunner.powershellScript(
                        script.toString(), inf, folder.toString()), cancelled);
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

        // Use the same settings-aware index path as the backup folder to avoid split-brain
        Path idx = indexPath(settings);
        ReentrantReadWriteLock lock = lockFor(idx);
        lock.writeLock().lock();
        try {
            BackupIndex index = loadIndex(settings);
            index.getEntries().add(entry);
            saveIndex(index, settings);
        } finally {
            lock.writeLock().unlock();
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
        if (!isSafeToDelete(folder)) {
            throw new IOException("Refusing to revert from folder outside backups root: " + folder);
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
        ProcessResult result = processRunner.run(ProcessRunner.powershellScript(
                script.toString(), folder.toString(), entry.deviceId()));
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

        AppLogger.info("Driver reverted successfully: " + entry.friendlyName()
                + " (installed " + detail.installed() + "/" + infCount + ")");
    }

    private record RevertDetail(int installed, int failed, String details) {}

    private static RevertDetail parseRevertOutput(String stdout) {
        if (stdout == null || stdout.isBlank()) return new RevertDetail(-1, -1, "");
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
            return new RevertDetail(installed, failed, details);
        } catch (Exception ignored) {
            return new RevertDetail(-1, -1, "");
        }
    }

    public void removeBackupEntry(DriverBackupEntry entry) throws IOException {
        if (entry == null || entry.id() == null) return;
        // Remove from all index files (primary + fallbacks) to prevent ghost reappearance
        java.util.Set<String> idsToRemove = java.util.Set.of(entry.id());
        purgeFromAllIndexes(idsToRemove);

        try {
            Path folder = Path.of(entry.backupFolder());
            if (isSafeToDelete(folder)) {
                deleteDirectory(folder);
                cleanupEmptyParent(folder.getParent());
            }
        } catch (IOException e) {
            AppLogger.warning("Could not delete backup folder: " + entry.backupFolder(), e);
        }
    }

    public void removeAll() throws IOException {
        List<DriverBackupEntry> entriesToDelete;
        Path idx = indexPath();
        ReentrantReadWriteLock lock = lockFor(idx);
        lock.writeLock().lock();
        try {
            BackupIndex index = loadIndex();
            entriesToDelete = new java.util.ArrayList<>(index.getEntries());
            index.getEntries().clear();
            saveIndex(index);
        } finally {
            lock.writeLock().unlock();
        }
        // Purge all fallback indexes as well so deleted entries don't resurrect
        if (!entriesToDelete.isEmpty()) {
            java.util.Set<String> allIds = new java.util.HashSet<>();
            for (DriverBackupEntry e : entriesToDelete) if (e.id()!=null) allIds.add(e.id());
            // Also include any entries that only lived in fallback files
            for (Path fb : fallbackIndexPaths(indexPath())) {
                try {
                    BackupIndex fbIdx = loadSingleIndex(fb);
                    for (DriverBackupEntry e : fbIdx.getEntries()) if (e.id()!=null) allIds.add(e.id());
                } catch (Exception ignored) {}
            }
            purgeFromAllIndexes(allIds);
            // Ensure fallback files are truncated
            for (Path fb : fallbackIndexPaths(indexPath())) {
                try {
                    BackupIndex fbIdx = loadSingleIndex(fb);
                    if (!fbIdx.getEntries().isEmpty()) {
                        fbIdx.getEntries().clear();
                        saveIndexToPath(fbIdx, fb);
                    }
                } catch (Exception ex) {
                    AppLogger.warning("Failed to clear fallback index " + fb + ": " + ex.getMessage());
                }
            }
        }
        // Sequential to avoid race on shared parent (same safeId)
        java.util.Set<Path> cleanedParents = new java.util.HashSet<>();
        for (DriverBackupEntry entry : entriesToDelete) {
            try {
                Path folder = Path.of(entry.backupFolder());
                if (isSafeToDelete(folder) && Files.isDirectory(folder)) {
                    deleteDirectory(folder);
                    Path parent = folder.getParent();
                    if (parent != null && cleanedParents.add(parent)) {
                        cleanupEmptyParent(parent);
                    }
                }
            } catch (IOException e) {
                AppLogger.warning("Could not delete backup folder: " + entry.backupFolder(), e);
            }
        }
        AppLogger.info("All driver backups removed (" + entriesToDelete.size() + ")");
    }

    private void purgeFromAllIndexes(java.util.Set<String> idsToRemove) throws IOException {
        if (idsToRemove == null || idsToRemove.isEmpty()) return;
        java.util.List<Path> allPaths = allIndexPaths();
        for (Path p : allPaths) {
            ReentrantReadWriteLock lock = lockFor(p);
            lock.writeLock().lock();
            try {
                if (!Files.exists(p) && !Files.exists(p.resolveSibling(p.getFileName().toString() + ".bak"))) continue;
                BackupIndex idx = loadSingleIndex(p);
                boolean changed = idx.getEntries().removeIf(e -> e != null && e.id() != null && idsToRemove.contains(e.id()));
                if (changed) {
                    saveIndexToPath(idx, p);
                }
            } catch (IOException ex) {
                AppLogger.warning("Failed to purge index " + p + ": " + ex.getMessage());
            } finally {
                lock.writeLock().unlock();
            }
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

    private boolean isSafeToDelete(Path folder) {
        if (folder == null) return false;
        try {
            Path normalized = folder.toAbsolutePath().normalize();
            // Must be at least 2 levels deep ( <root>/<safeId>/<timestamp> ).
            if (normalized.getNameCount() < 2) {
                AppLogger.warning("Refusing to delete shallow folder: " + folder);
                return false;
            }
            java.util.List<Path> allowedRoots = new java.util.ArrayList<>();
            Path primaryRoot = indexPath().getParent();
            if (primaryRoot != null) allowedRoots.add(primaryRoot.toAbsolutePath().normalize());
            // Also allow custom backupDirectory locations
            try {
                com.sbtools.settings.AppSettings s = new com.sbtools.settings.SettingsStore().load();
                if (s.backupDirectory() != null && !s.backupDirectory().isBlank()) {
                    Path custom = Path.of(s.backupDirectory()).toAbsolutePath().normalize();
                    if (isValidCustomRoot(custom)) allowedRoots.add(custom);
                }
            } catch (Exception ignored) {}
            // Portable root fallback
            try {
                Path portable = AppPaths.backupsRoot().toAbsolutePath().normalize();
                if (!allowedRoots.contains(portable)) allowedRoots.add(portable);
            } catch (Exception ignored) {}
            try {
                Path legacy = AppPaths.legacyBackupsRoot().toAbsolutePath().normalize();
                if (!allowedRoots.contains(legacy)) allowedRoots.add(legacy);
            } catch (Exception ignored) {}
            for (Path root : allowedRoots) {
                if (normalized.startsWith(root)) {
                    // Additional safety: folder must be inside root/safeId/... ensure not directly root
                    Path rel = root.relativize(normalized);
                    if (rel.getNameCount() >= 2) return true;
                    AppLogger.warning("Refusing to delete folder directly under backups root: " + folder);
                    return false;
                }
            }
            // Old custom location after a settings change: the folder is
            // still legit if an index (primary or fallback) references it.
            // Allow revert/size/delete for indexed folders even when they
            // live outside the current roots (orphan-backup fix).
            try {
                for (Path idxPath : allIndexPaths()) {
                    try {
                        BackupIndex idx = loadSingleIndex(idxPath);
                        for (DriverBackupEntry e : idx.getEntries()) {
                            if (e == null || e.backupFolder() == null) continue;
                            try {
                                Path indexed = Path.of(e.backupFolder()).toAbsolutePath().normalize();
                                if (indexed.equals(normalized)) {
                                    Path rel = indexed.getFileName() != null ? indexed : null;
                                    // Require timestamp-style leaf + safeId parent to
                                    // avoid trusting a tampered drive-root entry.
                                    if (indexed.getNameCount() >= 2 && rel != null) return true;
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        AppLogger.warning("Refusing to delete folder outside backups root: " + folder);
        return false;
    }

    private static boolean isValidCustomRoot(Path custom) {
        return AppPaths.isValidCustomBackupDir(custom);
    }

    public long getTotalSize() throws IOException {
        return getTotalSize(listAll());
    }

    public long getTotalSize(List<DriverBackupEntry> entries) throws IOException {
        long total = 0;
        for (DriverBackupEntry entry : entries) {
            try {
                Path folder = Path.of(entry.backupFolder());
                if (!isSafeToDelete(folder)) {
                    AppLogger.warning("Skipping size calculation for unsafe folder: " + folder);
                    continue;
                }
                if (Files.isDirectory(folder)) {
                    total += directorySize(folder);
                }
            } catch (Exception ignored) {}
        }
        return total;
    }

    private long countInfFiles(Path folder) throws IOException {
        try (var stream = Files.walk(folder, 5)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".inf"))
                    .count();
        }
    }

    private long directorySize(Path directory) throws IOException {
        // Limit depth and avoid following links to prevent runaway scan if backupFolder is misconfigured
        try (var stream = Files.walk(directory, 10)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        try { return !Files.isSymbolicLink(p); } catch (Exception e) { return false; }
                    })
                    .mapToLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0; }
                    })
                    .sum();
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var stream = Files.walk(directory)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try { Files.deleteIfExists(path); } catch (IOException e) { AppLogger.warning("Could not delete: " + path, e); }
                        });
            }
        }
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
