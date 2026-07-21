package com.sbtools.backup;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RestoreRow {

    private static final ExecutorService SIZE_CALC_POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "size-calc");
        t.setDaemon(true);
        return t;
    });

    private final DriverBackupEntry entry;
    private final StringProperty deviceName = new SimpleStringProperty();
    private final StringProperty version = new SimpleStringProperty();
    private final StringProperty backedUpAt = new SimpleStringProperty();
    private final StringProperty size = new SimpleStringProperty();

    public RestoreRow(DriverBackupEntry entry) {
        this.entry = entry;
        deviceName.set(entry.friendlyName() != null && !entry.friendlyName().isBlank()
                ? entry.friendlyName() : entry.deviceId());
        version.set(entry.version() != null ? entry.version() : "\u2014");
        backedUpAt.set(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(entry.createdAt()));
        size.set("Loading...");
    }

    public void computeSizeAsync() {
        SIZE_CALC_POOL.execute(() -> {
            String result = computeSize(entry.backupFolder());
            Platform.runLater(() -> size.set(result));
        });
    }

    public static CompletableFuture<Void> computeAllSizesAsync(List<RestoreRow> rows) {
        return CompletableFuture.runAsync(() -> {
            for (RestoreRow row : rows) {
                String result = computeSize(row.entry.backupFolder());
                Platform.runLater(() -> row.size.set(result));
            }
        }, SIZE_CALC_POOL);
    }

    public DriverBackupEntry entry() {
        return entry;
    }

    public StringProperty deviceNameProperty() {
        return deviceName;
    }

    public StringProperty versionProperty() {
        return version;
    }

    public StringProperty backedUpAtProperty() {
        return backedUpAt;
    }

    public StringProperty sizeProperty() {
        return size;
    }

    private static String computeSize(String backupFolder) {
        Path folder = Path.of(backupFolder);
        if (!Files.isDirectory(folder)) {
            return "\u2014";
        }
        try (var stream = Files.walk(folder)) {
            long bytes = stream.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
            return formatFileSize(bytes);
        } catch (IOException e) {
            return "\u2014";
        }
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
