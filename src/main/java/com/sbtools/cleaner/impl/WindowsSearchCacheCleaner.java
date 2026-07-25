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
                try (Stream<Path> walk = Files.walk(dir, 2)) {
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
        stopService("WSearch");
        long cleaned = 0;
        for (Path dir : getSafeSearchCacheDirs()) {
            if (dir != null && Files.isDirectory(dir)) {
                cleaned += CleanerUtils.deleteDirectoryContents(dir);
            }
        }
        startService("WSearch");
        return cleaned;
    }

    private void stopService(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("net", "stop", serviceName);
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean ok = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) p.destroyForcibly();
        } catch (Exception e) {
            AppLogger.warning("Failed to stop service " + serviceName + ": " + e.getMessage());
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
            CleanerUtils.addPath(dirs, searchData.resolve("Applications").toString());
            CleanerUtils.addPath(dirs, searchData.resolve("Temp").toString());
        }
        Path programData = CleanerUtils.safeEnvPath("PROGRAMDATA");
        if (programData != null) {
            CleanerUtils.addPath(dirs, programData.resolve("Microsoft\\Search\\Data\\Plugins").toString());
        }
        return dirs;
    }
}
