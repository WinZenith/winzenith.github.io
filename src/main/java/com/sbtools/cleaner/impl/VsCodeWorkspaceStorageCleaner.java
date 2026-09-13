package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stale VS Code workspace storage entries older than 30 days.
 * Distinct from Code\Cache (covered by OTHER_PROGRAMS_CACHE).
 * Conservative: age-gated at whole-entry granularity, never touches settings
 * or extensions. An entry is removed only when its newest content is older
 * than the threshold, so active entries with mixed old/new files are kept
 * intact instead of being left half-deleted.
 */
public class VsCodeWorkspaceStorageCleaner implements CleanerExtension {

    private static final Duration MAX_AGE = Duration.ofDays(30);

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.VSCODE_WORKSPACE_STORAGE; }

    @Override
    public java.util.List<String> describeTargets() {
        return java.util.List.of("%APPDATA%\\Code\\User\\workspaceStorage (whole entries older than 30 days)");
    }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        long totalSize = 0;
        int entryCount = 0;
        int fileCount = 0;
        long cutoff = System.currentTimeMillis() - MAX_AGE.toMillis();
        for (Path root : getDirs()) {
            if (token != null && token.isCancelled()) break;
            if (root == null || !Files.isDirectory(root)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path entry : ds) {
                    if (token != null && token.isCancelled()) break;
                    try {
                        if (!Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                        if (Files.isSymbolicLink(entry)) continue;
                        try {
                            Object reparse = Files.getAttribute(entry, "dos:isReparsePoint",
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
                            if (Boolean.TRUE.equals(reparse)) continue;
                        } catch (Exception ignored) {}
                        EntryStats stats = statEntry(entry, token);
                        if (stats == null) continue;
                        if (stats.newestModified > 0 && stats.newestModified < cutoff) {
                            totalSize += stats.totalBytes;
                            fileCount += stats.fileCount;
                            entryCount++;
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(fileCount);
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalSize)
                + (entryCount > 0 ? " (" + entryCount + " stale entries, " + fileCount + " files)" : ""));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long cleaned = 0;
        long cutoff = System.currentTimeMillis() - MAX_AGE.toMillis();
        for (Path root : getDirs()) {
            if (token != null && token.isCancelled()) break;
            if (root == null || !Files.isDirectory(root)) continue;
            if (!CleanerUtils.isSafeToCleanDirectory(root)) {
                AppLogger.warning("Skipping unsafe workspaceStorage root: " + root);
                continue;
            }
            List<Path> staleEntries = new ArrayList<>();
            long[] staleBytes = {0};
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path entry : ds) {
                    if (token != null && token.isCancelled()) break;
                    try {
                        if (!Files.isDirectory(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                        if (Files.isSymbolicLink(entry)) continue;
                        try {
                            Object reparse = Files.getAttribute(entry, "dos:isReparsePoint",
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
                            if (Boolean.TRUE.equals(reparse)) continue;
                        } catch (Exception ignored) {}
                        EntryStats stats = statEntry(entry, token);
                        if (stats != null && stats.newestModified > 0 && stats.newestModified < cutoff) {
                            staleEntries.add(entry);
                            staleBytes[0] += stats.totalBytes;
                        }
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
                continue;
            }
            for (Path entry : staleEntries) {
                if (token != null && token.isCancelled()) break;
                try {
                    // Re-check freshness immediately before delete (TOCTOU):
                    // VS Code may have touched the entry since the scan.
                    EntryStats fresh = statEntry(entry, token);
                    if (fresh == null || fresh.newestModified <= 0
                            || fresh.newestModified >= System.currentTimeMillis() - MAX_AGE.toMillis()) {
                        continue;
                    }
                    long freed = CleanerUtils.deleteDirectoryContents(entry, Integer.MAX_VALUE, token);
                    // deleteDirectoryContents removes contents but keeps the root;
                    // remove the now-empty entry dir itself atomically per entry.
                    CleanerUtils.deleteDirectoryIfEmptySafe(entry, token);
                    // Report actual bytes freed (authoritative), never the
                    // precomputed estimate, so locked/skipped files can't inflate.
                    cleaned += freed;
                } catch (Exception ignored) {
                }
            }
        }
        return cleaned;
    }

    private record EntryStats(long totalBytes, int fileCount, long newestModified) {}

    private EntryStats statEntry(Path entry, com.sbtools.util.CancellationToken token) {
        AtomicLong totalBytes = new AtomicLong(0);
        AtomicLong fileCount = new AtomicLong(0);
        AtomicLong newest = new AtomicLong(0);
        try {
            Files.walkFileTree(entry, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
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
                    long m = attrs.lastModifiedTime() != null ? attrs.lastModifiedTime().toMillis() : 0L;
                    newest.accumulateAndGet(m, Math::max);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    totalBytes.addAndGet(attrs.size());
                    fileCount.incrementAndGet();
                    long m = attrs.lastModifiedTime() != null ? attrs.lastModifiedTime().toMillis() : 0L;
                    newest.accumulateAndGet(m, Math::max);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
            return null;
        }
        if (token != null && token.isCancelled()) return null;
        return new EntryStats(totalBytes.get(), (int) fileCount.get(), newest.get());
    }

    private List<Path> getDirs() {
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "APPDATA", "Code", "User", "workspaceStorage");
        return CleanerUtils.deduplicatePaths(dirs);
    }
}
