package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessManager;

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
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long size = getRecycleBinSize();
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-Command",
                    "Clear-RecycleBin -Force -ErrorAction SilentlyContinue");
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = false;
            long deadline = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < deadline) {
                if (token != null && token.isCancelled()) {
                    p.destroyForcibly();
                    throw new java.util.concurrent.CancellationException("Recycle Bin cleanup canceled");
                }
                if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) { finished = true; break; }
            }
            if (finished && p.exitValue() == 0) {
                if (token != null && token.isCancelled()) return 0L;
                // Verify PowerShell actually emptied the bin before reporting pre-scan size.
                long remaining = getRecycleBinSize();
                if (remaining == 0) return size;
                AppLogger.warning("Recycle Bin PowerShell exit 0 but " + remaining + " bytes remain; using fallback");
            }
            if (!finished) { p.destroyForcibly(); AppLogger.warning("Recycle Bin cleanup timed out"); }
        } catch (java.util.concurrent.CancellationException ce) {
            throw ce;
        } catch (Exception ex) {
            AppLogger.warning("Failed to empty Recycle Bin via PowerShell: " + ex.getMessage());
        }
        if (token != null && token.isCancelled()) return 0L;
        java.util.concurrent.atomic.AtomicLong fallbackCleaned = new java.util.concurrent.atomic.AtomicLong(0);
        java.util.List<Path> failedDeletes = new java.util.ArrayList<>();
        try {
            for (java.io.File root : java.io.File.listRoots()) {
                if (token != null && token.isCancelled()) break;
                Path recycleBin = root.toPath().resolve("$Recycle.Bin");
                if (Files.isDirectory(recycleBin)) {
                    java.util.List<Path> toDelete;
                    try (Stream<Path> walk = Files.walk(recycleBin)) {
                        toDelete = walk.sorted(Comparator.reverseOrder()).filter(f -> !f.equals(recycleBin)).toList();
                    }
                    for (Path f : toDelete) {
                        if (token != null && token.isCancelled()) break;
                        try {
                            long sz = Files.isRegularFile(f) ? Files.size(f) : 0L;
                            CleanerUtils.deletePermanently(f, token);
                            if (!Files.exists(f)) {
                                if (sz > 0) fallbackCleaned.addAndGet(sz);
                            } else {
                                failedDeletes.add(f);
                            }
                        } catch (Exception ignored) {
                            failedDeletes.add(f);
                        }
                    }
                }
            }
        } catch (Exception ex2) {
            AppLogger.warning("Failed to empty Recycle Bin: " + ex2.getMessage());
        }
        long fallback = fallbackCleaned.get();
        if (!failedDeletes.isEmpty()) {
            AppLogger.warning("Recycle Bin fallback incomplete, failed to delete " + failedDeletes.size() + " entries");
            return fallback;
        }
        if (fallback > 0) return fallback;
        // Verify PowerShell fallback actually emptied the bin before reporting pre-scan size
        long remaining = getRecycleBinSize();
        if (remaining == 0 && size > 0) return size;
        return fallback;
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
