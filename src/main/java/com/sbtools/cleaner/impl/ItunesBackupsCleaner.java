package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class ItunesBackupsCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.ITUNES_BACKUPS; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        Path backupDir = CleanerUtils.safeEnvPath("APPDATA", "Apple Computer", "MobileSync", "Backup");
        if (backupDir != null && Files.isDirectory(backupDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupDir)) {
                for (Path backup : ds) {
                    if (Files.isDirectory(backup)) {
                        try (Stream<Path> walk = Files.walk(backup, 2)) {
                            var stats = walk.filter(Files::isRegularFile)
                                    .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                            totalSize += stats.getSum();
                            itemCount += (int) stats.getCount();
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        Path backupDir = CleanerUtils.safeEnvPath("APPDATA", "Apple Computer", "MobileSync", "Backup");
        if (backupDir == null || !Files.isDirectory(backupDir)) return 0;
        long cleaned = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupDir)) {
            for (Path backup : ds) {
                if (Files.isDirectory(backup)) {
                    try (Stream<Path> walk = Files.walk(backup, 2)) {
                        List<Path> sorted = walk.sorted(java.util.Comparator.reverseOrder()).toList();
                        for (Path f : sorted) {
                            if (f.equals(backup)) continue;
                            try {
                                if (Files.isRegularFile(f)) {
                                    long size = Files.size(f);
                                    CleanerUtils.deletePermanently(f);
                                    cleaned += size;
                                } else if (Files.isDirectory(f)) {
                                    Files.deleteIfExists(f);
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                    try { Files.deleteIfExists(backup); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return cleaned;
    }
}
