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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class DriverBackupService {

    private static final ReentrantLock INDEX_LOCK = new ReentrantLock();
    private final ProcessRunner processRunner = new ProcessRunner(300);

    public List<DriverBackupEntry> listAll() throws IOException {
        return loadIndex().getEntries().stream()
                .sorted(Comparator.comparing(DriverBackupEntry::createdAt).reversed())
                .collect(Collectors.toList());
    }

    public List<DriverBackupEntry> listBackups(String deviceId) throws IOException {
        return loadIndex().getEntries().stream()
                .filter(e -> e.deviceId().equals(deviceId))
                .sorted(Comparator.comparing(DriverBackupEntry::createdAt).reversed())
                .collect(Collectors.toList());
    }

    public DriverBackupEntry backupBeforeUpdate(InstalledDriver driver, AppSettings settings)
            throws IOException, InterruptedException {
        String inf = driver.infName();
        if (inf == null || inf.isBlank()) {
            inf = "driver.inf";
        }
        String safeId = driver.deviceId().replaceAll("[^a-zA-Z0-9_-]", "_");
        Path folder = AppPaths.backupsRoot()
                .resolve(safeId)
                .resolve(Instant.now().toEpochMilli() + "");
        Files.createDirectories(folder);

        Path script = PowerShellScripts.resolve("pnputil-backup.ps1");
        ProcessResult result = processRunner.run(ProcessRunner.powershellScript(
                script.toString(), inf, folder.toString()));
        if (!result.success()) {
            throw new IOException("Driver backup failed: " + result.combinedOutput());
        }

        DriverBackupEntry entry = new DriverBackupEntry(
                UUID.randomUUID().toString(),
                driver.deviceId(),
                driver.friendlyName(),
                Instant.now(),
                folder.toString(),
                driver.driverVersion(),
                inf
        );

        INDEX_LOCK.lock();
        try {
            BackupIndex index = loadIndex();
            index.getEntries().add(entry);
            saveIndex(index);
        } finally {
            INDEX_LOCK.unlock();
        }

        AppLogger.info("Driver backup created: " + entry.friendlyName()
                + " v" + entry.version() + " [" + entry.id() + "]");
        return entry;
    }

    public void revert(DriverBackupEntry entry) throws IOException, InterruptedException {
        Path folder = Path.of(entry.backupFolder());
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
                script.toString(), folder.toString()));
        if (!result.success()) {
            throw new IOException("Driver revert failed: " + result.combinedOutput());
        }

        AppLogger.info("Driver reverted successfully: " + entry.friendlyName());
    }

    public void removeBackupEntry(DriverBackupEntry entry) throws IOException {
        INDEX_LOCK.lock();
        try {
            BackupIndex index = loadIndex();
            index.getEntries().removeIf(e -> e.id().equals(entry.id()));
            saveIndex(index);
        } finally {
            INDEX_LOCK.unlock();
        }

        try {
            Path folder = Path.of(entry.backupFolder());
            if (Files.isDirectory(folder)) {
                deleteDirectory(folder);
            }
        } catch (IOException e) {
            AppLogger.warning("Could not delete backup folder: " + entry.backupFolder(), e);
        }
    }

    public void removeAll() throws IOException {
        INDEX_LOCK.lock();
        try {
            BackupIndex index = loadIndex();
            List<DriverBackupEntry> entries = index.getEntries();
            for (DriverBackupEntry entry : entries) {
                try {
                    Path folder = Path.of(entry.backupFolder());
                    if (Files.isDirectory(folder)) {
                        deleteDirectory(folder);
                    }
                } catch (IOException e) {
                    AppLogger.warning("Could not delete backup folder: " + entry.backupFolder(), e);
                }
            }
            index.getEntries().clear();
            saveIndex(index);
        } finally {
            INDEX_LOCK.unlock();
        }
        AppLogger.info("All driver backups removed");
    }

    public long getTotalSize() throws IOException {
        long total = 0;
        for (DriverBackupEntry entry : listAll()) {
            Path folder = Path.of(entry.backupFolder());
            if (Files.isDirectory(folder)) {
                total += directorySize(folder);
            }
        }
        return total;
    }

    public Map<String, Integer> countByDevice() throws IOException {
        return loadIndex().getEntries().stream()
                .collect(Collectors.groupingBy(
                        DriverBackupEntry::deviceId,
                        Collectors.summingInt(e -> 1)));
    }

    private long countInfFiles(Path folder) throws IOException {
        try (var stream = Files.walk(folder, 5)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".inf"))
                    .count();
        }
    }

    private long directorySize(Path directory) throws IOException {
        try (var stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            Files.walk(directory)
                    .sorted((a, b) -> -a.compareTo(b))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            AppLogger.warning("Could not delete: " + path, e);
                        }
                    });
        }
    }

    private BackupIndex loadIndex() throws IOException {
        Path path = AppPaths.backupIndex();
        if (!Files.exists(path)) {
            return new BackupIndex();
        }
        return JsonMapper.mapper().readValue(path.toFile(), BackupIndex.class);
    }

    private void saveIndex(BackupIndex index) throws IOException {
        Files.createDirectories(AppPaths.backupsRoot());
        JsonMapper.mapper().writerWithDefaultPrettyPrinter()
                .writeValue(AppPaths.backupIndex().toFile(), index);
    }
}
