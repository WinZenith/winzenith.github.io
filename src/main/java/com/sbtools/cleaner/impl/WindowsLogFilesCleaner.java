package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class WindowsLogFilesCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.WINDOWS_LOG_FILES; }

    @Override
    public boolean requiresAdmin() { return true; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String windir = CleanerUtils.safeEnv("WINDIR");
        if (windir != null) {
            Path logsDir = Paths.get(windir, "Logs");
            if (Files.isDirectory(logsDir)) {
                try (Stream<Path> walk = Files.walk(logsDir, 2)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .filter(p -> { String name = p.getFileName().toString().toLowerCase(); return name.endsWith(".log"); })
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {}
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long cleaned = 0;
        String windir = CleanerUtils.safeEnv("WINDIR");
        if (windir != null) {
            Path logsDir = Paths.get(windir, "Logs");
            if (Files.isDirectory(logsDir)) {
                try (Stream<Path> walk = Files.walk(logsDir, 2)) {
                    List<Path> toDelete = walk.filter(Files::isRegularFile)
                            .filter(p -> { String name = p.getFileName().toString().toLowerCase(); return name.endsWith(".log"); })
                            .toList();
                    for (Path f : toDelete) { if (token != null && token.isCancelled()) break; long size = Files.size(f); CleanerUtils.deletePermanently(f, token); if (!Files.exists(f)) cleaned += size; }
                } catch (Exception ignored) {}
            }
        }
        return cleaned;
    }
}
