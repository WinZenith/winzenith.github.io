package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class WindowsSearchCacheCleaner implements CleanerExtension {

    private static final int SCAN_DEPTH = 2;

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.WINDOWS_SEARCH_CACHE; }

    @Override
    public boolean requiresAdmin() { return true; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        for (Path dir : getSafeSearchCacheDirs()) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir, SCAN_DEPTH)) {
                    var stats = walk.filter(Files::isRegularFile)
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
        List<Path> cacheDirs = getSafeSearchCacheDirs();
        if (cacheDirs.isEmpty()) return 0L;
        boolean searchWasRunning = CleanerUtils.isWindowsServiceRunning("WSearch");
        boolean stoppedByUs = false;
        if (searchWasRunning) {
            stoppedByUs = stopService("WSearch");
            if (!stoppedByUs) {
                AppLogger.warning("Skipping Windows Search cache: could not stop WSearch");
                return 0;
            }
        }
        try {
            if (token != null && token.isCancelled()) return 0L;
            long cleaned = 0;
            for (Path dir : cacheDirs) {
                if (token != null && token.isCancelled()) break;
                if (dir != null && Files.isDirectory(dir) && CleanerUtils.isSafeToCleanDirectory(dir)) {
                    cleaned += CleanerUtils.deleteDirectoryContents(dir, SCAN_DEPTH, token);
                }
            }
            return cleaned;
        } finally {
            if (stoppedByUs) startService("WSearch");
        }
    }

    private boolean stopService(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("net", "stop", serviceName);
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean ok = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            AppLogger.warning("Failed to stop service " + serviceName + ": " + e.getMessage());
            return false;
        }
    }

    private void startService(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("net", "start", serviceName);
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean ok = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) p.destroyForcibly();
        } catch (Exception e) {
            AppLogger.warning("Failed to start service " + serviceName + ": " + e.getMessage());
        }
    }

    private List<Path> getSafeSearchCacheDirs() {
        List<Path> dirs = new ArrayList<>();
        Path searchData = CleanerUtils.safeEnvPath("PROGRAMDATA", "Microsoft", "Search", "Data");
        if (searchData != null && Files.isDirectory(searchData)) {
            CleanerUtils.addPath(dirs, searchData.resolve("Temp").toString());
        }
        return dirs;
    }
}
