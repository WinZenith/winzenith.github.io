package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicLong;

public class RecycleBinCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.EMPTY_RECYCLE_BIN; }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Canceled");
            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
            row.setErrorMessage("Scan canceled by user");
            return;
        }
        long[] stats = scanRecycleBinSizeAndCount(token);
        long size = stats[0];
        int count = (int) stats[1];
        if (token != null && token.isCancelled()) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Canceled");
            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
            row.setErrorMessage("Scan canceled by user");
            return;
        }
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
            java.nio.file.Path script =
                    com.sbtools.util.PowerShellScripts.resolve("clear-recyclebin.ps1");
            com.sbtools.util.ProcessRunner runner = new com.sbtools.util.ProcessRunner(30);
            com.sbtools.util.ProcessResult r = runner.run(
                    com.sbtools.util.ProcessRunner.powershellScriptNonInteractive(script.toString()),
                    30, token != null ? token.asAtomicBoolean() : null);
            if (r.success()) {
                if (token != null && token.isCancelled()) return 0L;
                // Verify PowerShell actually emptied the bin before reporting pre-scan size.
                long remaining = getRecycleBinSize();
                if (remaining == 0) return size;
                AppLogger.warning("Recycle Bin PowerShell exit 0 but " + remaining + " bytes remain; using fallback");
            } else {
                AppLogger.warning("Recycle Bin cleanup script failed: " + r.combinedOutput());
            }
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
                    java.util.List<Path> filesToDelete = new java.util.ArrayList<>();
                    java.util.List<Path> dirsToDelete = new java.util.ArrayList<>();
                    try {
                        final com.sbtools.util.CancellationToken walkToken = token;
                        Files.walkFileTree(recycleBin, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                                if (walkToken != null && walkToken.isCancelled()) return FileVisitResult.TERMINATE;
                                // Never descend into links/junctions: $Recycle.Bin must not
                                // become a vehicle for deleting files outside the bin.
                                if (attrs.isSymbolicLink() || attrs.isOther()) return FileVisitResult.SKIP_SUBTREE;
                                try {
                                    if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                                    Object reparse = Files.getAttribute(dir, "dos:isReparsePoint",
                                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
                                    if (Boolean.TRUE.equals(reparse)) return FileVisitResult.SKIP_SUBTREE;
                                } catch (Exception ignored) {}
                                if (!dir.equals(recycleBin)) {
                                    dirsToDelete.add(dir);
                                }
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                if (walkToken != null && walkToken.isCancelled()) return FileVisitResult.TERMINATE;
                                filesToDelete.add(file);
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                                return FileVisitResult.CONTINUE;
                            }
                        });
                    } catch (Exception ignored) {}

                    for (Path f : filesToDelete) {
                        if (token != null && token.isCancelled()) break;
                        try {
                            // $I control files are metadata, excluded from scan —
                            // exclude here too so freed never exceeds scanned.
                            boolean isControl = f.getFileName() != null
                                    && f.getFileName().toString().startsWith("$I");
                            long sz = !isControl && Files.isRegularFile(f) ? Files.size(f) : 0L;
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
                    dirsToDelete.sort(Comparator.comparingInt(Path::getNameCount).reversed());
                    for (Path d : dirsToDelete) {
                        if (token != null && token.isCancelled()) break;
                        try { Files.deleteIfExists(d); } catch (Exception ignored) {}
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

    private long[] scanRecycleBinSizeAndCount() {
        return scanRecycleBinSizeAndCount(null);
    }

    private long[] scanRecycleBinSizeAndCount(com.sbtools.util.CancellationToken token) {
        AtomicLong totalSize = new AtomicLong(0);
        AtomicLong totalCount = new AtomicLong(0);
        try {
            for (java.io.File root : java.io.File.listRoots()) {
                if (token != null && token.isCancelled()) break;
                Path recycleBin = root.toPath().resolve("$Recycle.Bin");
                if (Files.isDirectory(recycleBin)) {
                    try {
                        Files.walkFileTree(recycleBin, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                                if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                                if (attrs.isSymbolicLink() || attrs.isOther()) return FileVisitResult.SKIP_SUBTREE;
                                try {
                                    if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                                    Object reparse = Files.getAttribute(dir, "dos:isReparsePoint",
                                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
                                    if (Boolean.TRUE.equals(reparse)) return FileVisitResult.SKIP_SUBTREE;
                                } catch (Exception ignored) {}
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                                if (attrs.isRegularFile() && !file.getFileName().toString().startsWith("$I")) {
                                    totalSize.addAndGet(attrs.size());
                                    totalCount.incrementAndGet();
                                }
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                                return FileVisitResult.CONTINUE;
                            }
                        });
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return new long[]{totalSize.get(), totalCount.get()};
    }

    private long getRecycleBinSize() {
        return scanRecycleBinSizeAndCount()[0];
    }
}
