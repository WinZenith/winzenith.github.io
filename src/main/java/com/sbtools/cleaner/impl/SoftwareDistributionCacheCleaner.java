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

public class SoftwareDistributionCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.SOFTWARE_DISTRIBUTION_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        if (isWindowsUpdateRunning()) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Skipped (Windows Update active)");
            return;
        }
        long totalSize = 0;
        int itemCount = 0;
        String windir = CleanerUtils.safeEnv("WINDIR");
        if (windir != null) {
            List<Path> dirs = new ArrayList<>();
            CleanerUtils.addPath(dirs, windir + "\\SoftwareDistribution\\Download");
            for (Path dir : dirs) {
                if (dir != null && Files.isDirectory(dir)) {
                    try (Stream<Path> walk = Files.walk(dir)) {
                        var stats = walk.filter(Files::isRegularFile)
                                .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                        totalSize += stats.getSum();
                        itemCount += (int) stats.getCount();
                    } catch (Exception ignored) {}
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        if (isWindowsUpdateRunning() || isDismRunning()) {
            AppLogger.info("Skipping SoftwareDistribution cache: Windows Update or DISM is running");
            return 0;
        }
        long cleaned = 0;
        String windir = CleanerUtils.safeEnv("WINDIR");
        if (windir != null) {
            List<Path> dirs = new ArrayList<>();
            CleanerUtils.addPath(dirs, windir + "\\SoftwareDistribution\\Download");
            for (Path dir : dirs) {
                if (dir != null && Files.isDirectory(dir)) cleaned += CleanerUtils.deleteDirectoryContents(dir);
            }
        }
        return cleaned;
    }

    private boolean isWindowsUpdateRunning() {
        return isServiceRunning("wuauserv") || isServiceRunning("UsoSvc");
    }

    private boolean isServiceRunning(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sc", "query", serviceName);
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                return output.contains("RUNNING");
            } else {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isDismRunning() {
        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH");
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                return output.toLowerCase().contains("dism.exe");
            } else {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {}
        return false;
    }
}
