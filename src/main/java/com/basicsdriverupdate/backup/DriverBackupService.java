package com.basicsdriverupdate.backup;

import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.settings.AppSettings;
import com.basicsdriverupdate.util.AppLogger;
import com.basicsdriverupdate.util.AppPaths;
import com.basicsdriverupdate.util.JsonMapper;
import com.basicsdriverupdate.util.PowerShellScripts;
import com.basicsdriverupdate.util.ProcessResult;
import com.basicsdriverupdate.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class DriverBackupService {

    private static final int MAX_BACKUPS_PER_DEVICE = 3;

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
        if (settings.createSystemRestorePoint()) {
            try {
                Path cp = PowerShellScripts.resolve("checkpoint-restore.ps1");
                processRunner.run(ProcessRunner.powershellScript(cp.toString()));
            } catch (Exception e) {
                AppLogger.warning("System restore point skipped", e);
            }
        }
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
        BackupIndex index = loadIndex();
        index.getEntries().add(entry);
        pruneDeviceBackups(index, driver.deviceId());
        saveIndex(index);
        return entry;
    }

    public void revert(DriverBackupEntry entry) throws IOException, InterruptedException {
        Path folder = Path.of(entry.backupFolder());
        if (!Files.isDirectory(folder)) {
            throw new IOException("Backup folder missing: " + folder);
        }
        Path script = PowerShellScripts.resolve("pnputil-restore.ps1");
        ProcessResult result = processRunner.run(ProcessRunner.powershellScript(
                script.toString(), folder.toString()));
        if (!result.success()) {
            throw new IOException("Driver revert failed: " + result.combinedOutput());
        }
    }

    private void pruneDeviceBackups(BackupIndex index, String deviceId) {
        List<DriverBackupEntry> deviceEntries = index.getEntries().stream()
                .filter(e -> e.deviceId().equals(deviceId))
                .sorted(Comparator.comparing(DriverBackupEntry::createdAt))
                .collect(Collectors.toCollection(ArrayList::new));
        while (deviceEntries.size() > MAX_BACKUPS_PER_DEVICE) {
            DriverBackupEntry oldest = deviceEntries.remove(0);
            index.getEntries().removeIf(e -> e.id().equals(oldest.id()));
            try {
                Files.deleteIfExists(Path.of(oldest.backupFolder()));
            } catch (IOException e) {
                AppLogger.warning("Could not delete old backup folder", e);
            }
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
