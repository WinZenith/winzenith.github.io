package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * OneDrive sync/setup logs only (*.odl, *.odlsent, *.log under OneDrive logs dirs).
 * Never touches actual synced files. Conservative LOW risk.
 */
public class OneDriveSyncLogsCleaner implements CleanerExtension {

    private static final List<String> LOG_EXTENSIONS = List.of(".odl", ".odlsent", ".log", ".etl.log");

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.ONEDRIVE_SYNC_LOGS; }

    @Override
    public java.util.List<String> describeTargets() {
        return java.util.List.of(
                "%LOCALAPPDATA%\\Microsoft\\OneDrive\\logs (*.odl, *.log only)",
                "%LOCALAPPDATA%\\Microsoft\\OneDrive\\setup\\logs (*.odl, *.log only)");
    }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        for (Path dir : getDirs()) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir, CleanerUtils.DEFAULT_SCAN_MAX_DEPTH)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .filter(OneDriveSyncLogsCleaner::isLogFile)
                            .collect(java.util.stream.Collectors.summarizingLong(p -> {
                                try { return Files.size(p); } catch (Exception e) { return p.toFile().length(); }
                            }));
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
        for (Path dir : getDirs()) {
            if (token != null && token.isCancelled()) break;
            if (dir == null || !Files.isDirectory(dir) || !CleanerUtils.isSafeToCleanDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                List<Path> sorted = walk.filter(Files::isRegularFile)
                        .filter(OneDriveSyncLogsCleaner::isLogFile)
                        .sorted(java.util.Comparator.comparingInt(Path::getNameCount).reversed())
                        .toList();
                for (Path f : sorted) {
                    if (token != null && token.isCancelled()) break;
                    if (CleanerUtils.isProtectedPath(f)) continue;
                    try {
                        long size = Files.size(f);
                        CleanerUtils.deletePermanently(f, token);
                        if (!Files.exists(f)) cleaned += size;
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return cleaned;
    }

    private static boolean isLogFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        for (String ext : LOG_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private List<Path> getDirs() {
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "OneDrive", "logs");
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "OneDrive", "setup", "logs");
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "OneDrive", "Update", "logs");
        return CleanerUtils.deduplicatePaths(dirs);
    }
}
