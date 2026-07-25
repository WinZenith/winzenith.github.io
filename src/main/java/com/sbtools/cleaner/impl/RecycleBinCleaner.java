package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessManager;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.stream.Stream;

public class RecycleBinCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.EMPTY_RECYCLE_BIN; }

    @Override
    public void scan(CleanupRow row) {
        long size = 0;
        int count = 0;
        try {
            for (java.io.File root : java.io.File.listRoots()) {
                Path recycleBin = root.toPath().resolve("$Recycle.Bin");
                if (Files.isDirectory(recycleBin)) {
                    try (Stream<Path> walk = Files.walk(recycleBin)) {
                        var result = walk.filter(Files::isRegularFile)
                                .filter(p -> !p.getFileName().toString().startsWith("$I"))
                                .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                        size += result.getSum();
                        count += (int) result.getCount();
                    }
                }
            }
        } catch (Exception ignored) {}
        row.setTotalBytes(size);
        row.setItemCount(count);
        row.setSizeOrCountText(size > 0 ? CleanerUtils.formatBytes(size) + " (" + count + " files)" : "Empty");
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        long size = getRecycleBinSize();
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-Command",
                    "Clear-RecycleBin -Force -ErrorAction SilentlyContinue");
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); AppLogger.warning("Recycle Bin cleanup timed out"); }
        } catch (Exception ex) {
            AppLogger.warning("Failed to empty Recycle Bin via PowerShell, trying fallback: " + ex.getMessage());
            try {
                for (java.io.File root : java.io.File.listRoots()) {
                    Path recycleBin = root.toPath().resolve("$Recycle.Bin");
                    if (Files.isDirectory(recycleBin)) {
                        try (Stream<Path> walk = Files.walk(recycleBin)) {
                            walk.sorted(Comparator.reverseOrder()).forEach(f -> {
                                if (!f.equals(recycleBin)) {
                                    try { CleanerUtils.deletePermanently(f); } catch (Exception ignored) {}
                                }
                            });
                        }
                    }
                }
            } catch (Exception ex2) {
                AppLogger.warning("Failed to empty Recycle Bin: " + ex2.getMessage());
            }
        }
        return size;
    }

    private long getRecycleBinSize() {
        long size = 0;
        try {
            for (java.io.File root : java.io.File.listRoots()) {
                Path recycleBin = root.toPath().resolve("$Recycle.Bin");
                if (Files.isDirectory(recycleBin)) {
                    try (Stream<Path> walk = Files.walk(recycleBin)) {
                        size += walk.filter(Files::isRegularFile)
                                .filter(p -> !p.getFileName().toString().startsWith("$I"))
                                .mapToLong(p -> p.toFile().length())
                                .sum();
                    }
                }
            }
        } catch (Exception ignored) {}
        return size;
    }
}
