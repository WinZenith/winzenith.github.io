package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class EmptyFoldersCleaner implements CleanerExtension {

    private static final int MAX_DEPTH = 3;

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.EMPTY_FOLDERS; }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        int count = 0;
        for (Path root : getRoots()) {
            if (token != null && token.isCancelled()) break;
            count += collectEmptyDirs(root, token).size();
        }
        row.setTotalBytes(0);
        row.setItemCount(count);
        row.setSizeOrCountText(count + " empty folder" + (count == 1 ? "" : "s"));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long deleted = 0;
        for (Path root : getRoots()) {
            if (token != null && token.isCancelled()) break;
            List<Path> emptyDirs = collectEmptyDirs(root, token);
            emptyDirs.sort(Comparator.comparingInt(Path::getNameCount).reversed());
            for (Path dir : emptyDirs) {
                if (token != null && token.isCancelled()) break;
                if (CleanerUtils.deleteDirectoryIfEmptySafe(dir, token)) deleted++;
            }
        }
        return deleted;
    }

    private List<Path> collectEmptyDirs(Path root, com.sbtools.util.CancellationToken token) {
        List<Path> emptyDirs = new ArrayList<>();
        if (root == null || !Files.isDirectory(root) || !CleanerUtils.isSafeToCleanDirectory(root)) {
            return emptyDirs;
        }
        try {
            Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                            if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                            if (CleanerUtils.shouldSkipWalkDir(d, root, attrs)) return FileVisitResult.SKIP_SUBTREE;
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path d, java.io.IOException exc) {
                            if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                            if (!d.equals(root) && CleanerUtils.isEmptyDirectory(d)) emptyDirs.add(d);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (Exception ignored) {}
        return emptyDirs;
    }

    private List<Path> getRoots() {
        List<Path> roots = new ArrayList<>();
        CleanerUtils.addEnvPath(roots, "TEMP");
        return roots;
    }
}
