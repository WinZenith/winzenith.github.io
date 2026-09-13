package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public class ItunesBackupsCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.ITUNES_BACKUPS; }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        long totalSize = 0;
        int itemCount = 0;
        Path backupDir = CleanerUtils.safeEnvPath("APPDATA", "Apple Computer", "MobileSync", "Backup");
        if (backupDir != null && Files.isDirectory(backupDir) && CleanerUtils.isSafeToCleanDirectory(backupDir)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupDir)) {
                for (Path backup : ds) {
                    if (token != null && token.isCancelled()) break;
                    if (!isBackupChildDir(backup)) continue;
                    long[] stats = measureBackupTree(backup, token);
                    totalSize += stats[0];
                    itemCount += (int) stats[1];
                }
            } catch (Exception ignored) {}
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
        Path backupDir = CleanerUtils.safeEnvPath("APPDATA", "Apple Computer", "MobileSync", "Backup");
        if (backupDir == null || !Files.isDirectory(backupDir) || !CleanerUtils.isSafeToCleanDirectory(backupDir)) {
            return 0;
        }
        long cleaned = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupDir)) {
            for (Path backup : ds) {
                if (token != null && token.isCancelled()) break;
                if (!isBackupChildDir(backup)) continue;
                cleaned += CleanerUtils.deleteDirectoryContents(backup, Integer.MAX_VALUE, token);
                CleanerUtils.deleteDirectoryIfEmptySafe(backup, token);
            }
        } catch (Exception ignored) {}
        return cleaned;
    }

    static boolean isBackupChildDir(Path backup) {
        if (backup == null || !Files.isDirectory(backup)) return false;
        try {
            if (Files.isSymbolicLink(backup)) return false;
            Object reparse = Files.getAttribute(backup, "dos:isReparsePoint",
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (Boolean.TRUE.equals(reparse)) return false;
        } catch (Exception ignored) {}
        return true;
    }

    static long[] measureBackupTree(Path backup, com.sbtools.util.CancellationToken token) {
        java.util.concurrent.atomic.AtomicLong totalSize = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong itemCount = new java.util.concurrent.atomic.AtomicLong();
        try {
            Files.walkFileTree(backup, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (attrs.isSymbolicLink() || attrs.isOther()) return FileVisitResult.SKIP_SUBTREE;
                    try {
                        if (Files.isSymbolicLink(d)) return FileVisitResult.SKIP_SUBTREE;
                        Object reparse = Files.getAttribute(d, "dos:isReparsePoint",
                                java.nio.file.LinkOption.NOFOLLOW_LINKS);
                        if (Boolean.TRUE.equals(reparse)) return FileVisitResult.SKIP_SUBTREE;
                    } catch (Exception ignored) {}
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (attrs.isRegularFile()) {
                        totalSize.addAndGet(attrs.size());
                        itemCount.incrementAndGet();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {}
        return new long[]{totalSize.get(), itemCount.get()};
    }
}
