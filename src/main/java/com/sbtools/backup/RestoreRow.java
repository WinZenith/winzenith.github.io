package com.sbtools.backup;

import com.sbtools.util.AppExecutors;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class RestoreRow {

    private static final ExecutorService SIZE_CALC_POOL = AppExecutors.ioPool();

    private final DriverBackupEntry entry;
    private final StringProperty deviceName = new SimpleStringProperty();
    private final StringProperty version = new SimpleStringProperty();
    private final StringProperty backedUpAt = new SimpleStringProperty();
    private final StringProperty size = new SimpleStringProperty();
    private final StringProperty status = new SimpleStringProperty(BackupHealth.statusLabel(BackupHealth.Status.MISSING));
    private volatile boolean sizeComputed = false;
    private volatile BackupHealth.Status health = BackupHealth.Status.MISSING;
    private volatile long fileCount = 0;
    private volatile long infCount = 0;
    private volatile long bytes = 0;

    public RestoreRow(DriverBackupEntry entry) {
        this.entry = entry;
        String name = entry.friendlyName();
        if (name == null || name.isBlank()) name = entry.deviceId();
        if (name == null || name.isBlank()) name = "Unknown device";
        deviceName.set(name);
        version.set(entry.version() != null && !entry.version().isBlank() ? entry.version() : "\u2014");
        if (entry.createdAt() != null) {
            backedUpAt.set(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault())
                    .format(entry.createdAt()));
        } else {
            backedUpAt.set("\u2014");
        }
        size.set("Loading...");
    }

    public void computeSizeAsync() {
        if (sizeComputed) return;
        SIZE_CALC_POOL.execute(() -> {
            BackupHealth.Stats stats = BackupHealth.inspect(entry.backupFolder());
            applyStats(stats);
        });
    }

    public void ensureSizeComputed() {
        if (!sizeComputed) computeSizeAsync();
    }

    /** Re-inspects disk state (used by Verify) even if previously computed. */
    public void refreshHealthAsync() {
        SIZE_CALC_POOL.execute(() -> {
            BackupHealth.Stats stats = BackupHealth.inspect(entry.backupFolder());
            sizeComputed = false;
            applyStats(stats);
        });
    }

    private void applyStats(BackupHealth.Stats stats) {
        health = stats.status();
        fileCount = stats.fileCount();
        infCount = stats.infCount();
        bytes = stats.bytes();
        sizeComputed = true;
        String sizeStr = BackupHealth.isHealthy(stats.status())
                || stats.status() == BackupHealth.Status.EMPTY
                ? formatFileSize(stats.bytes()) : "\u2014";
        String statusStr = BackupHealth.statusLabel(stats.status());
        Platform.runLater(() -> {
            size.set(sizeStr);
            status.set(statusStr);
        });
    }

    public static CompletableFuture<Void> computeAllSizesAsync(List<RestoreRow> rows) {
        return CompletableFuture.runAsync(() -> {
            for (RestoreRow row : rows) {
                if (row.sizeComputed) continue;
                BackupHealth.Stats stats = BackupHealth.inspect(row.entry.backupFolder());
                row.health = stats.status();
                row.fileCount = stats.fileCount();
                row.infCount = stats.infCount();
                row.bytes = stats.bytes();
                row.sizeComputed = true;
                String sizeStr = BackupHealth.isHealthy(stats.status())
                        || stats.status() == BackupHealth.Status.EMPTY
                        ? formatFileSize(stats.bytes()) : "\u2014";
                String statusStr = BackupHealth.statusLabel(stats.status());
                Platform.runLater(() -> {
                    row.size.set(sizeStr);
                    row.status.set(statusStr);
                });
            }
        }, SIZE_CALC_POOL);
    }

    public DriverBackupEntry entry() { return entry; }

    public StringProperty deviceNameProperty() { return deviceName; }
    public StringProperty versionProperty() { return version; }
    public StringProperty backedUpAtProperty() { return backedUpAt; }
    public StringProperty sizeProperty() { return size; }
    public StringProperty statusProperty() { return status; }

    public BackupHealth.Status getHealth() { return health; }
    public boolean isHealthy() { return BackupHealth.isHealthy(health); }
    public long getFileCount() { return fileCount; }
    public long getInfCount() { return infCount; }
    public long getBytes() { return bytes; }

    public static String formatFileSize(long bytes) {
        if (bytes < 0) bytes = 0;
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
