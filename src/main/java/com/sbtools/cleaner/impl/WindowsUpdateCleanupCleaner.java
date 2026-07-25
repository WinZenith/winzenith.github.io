package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class WindowsUpdateCleanupCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.WINDOWS_UPDATE_CLEANUP; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        try {
            ProcessBuilder pb = new ProcessBuilder("dism", "/Online", "/Cleanup-Image", "/AnalyzeComponentStore");
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = p.waitFor(25, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                for (String line : output.split("\\n")) {
                    if (line.contains("Size of superseded components")) {
                        String[] parts = line.split(":");
                        if (parts.length >= 2) {
                            String sizePart = parts[1].trim();
                            String sizeStr = sizePart.replaceAll("[^0-9.]", "").trim();
                            if (!sizeStr.isEmpty()) {
                                try {
                                    double numericValue = Double.parseDouble(sizeStr);
                                    long bytes;
                                    if (sizePart.toUpperCase().contains("GB")) bytes = (long) (numericValue * 1024L * 1024L * 1024L);
                                    else if (sizePart.toUpperCase().contains("MB")) bytes = (long) (numericValue * 1024L * 1024L);
                                    else if (sizePart.toUpperCase().contains("KB")) bytes = (long) (numericValue * 1024L);
                                    else bytes = (long) (numericValue * 1024L * 1024L);
                                    totalSize += bytes;
                                    itemCount = Math.max(itemCount, 1);
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }
            } else { p.destroyForcibly(); }
        } catch (Exception ignored) {}
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalSize) + (itemCount > 0 ? " (superseded components)" : " (none found)"));
    }

    @Override
    public long getCleanTimeoutSeconds() {
        return 900;
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        long cleaned = 0;
        try {
            ProcessBuilder pb = new ProcessBuilder("dism", "/Online", "/Cleanup-Image", "/StartComponentCleanup");
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = p.waitFor(900, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                AppLogger.info("DISM component cleanup completed successfully");
                String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                cleaned = parseCleanedBytes(output);
            } else {
                AppLogger.warning("DISM cleanup timed out after ~15 minutes");
                p.destroyForcibly();
            }
        } catch (Exception e) { AppLogger.warning("DISM cleanup failed: " + e.getMessage()); }
        return cleaned;
    }

    private long parseCleanedBytes(String output) {
        for (String line : output.split("\\n")) {
            String lower = line.toLowerCase();
            if (lower.contains("successfully") && lower.contains("freed")) {
                String cleanedStr = line.replaceAll("[^0-9.]", "").trim();
                if (!cleanedStr.isEmpty()) {
                    try {
                        double numericValue = Double.parseDouble(cleanedStr);
                        if (lower.contains("gb")) return (long) (numericValue * 1024L * 1024L * 1024L);
                        if (lower.contains("mb")) return (long) (numericValue * 1024L * 1024L);
                        if (lower.contains("kb")) return (long) (numericValue * 1024L);
                        return (long) (numericValue * 1024L * 1024L);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return 0;
    }
}
