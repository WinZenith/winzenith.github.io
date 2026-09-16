package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class GradleCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.GRADLE_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        Path caches = cachesDir();
        if (caches == null) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText(CleanerUtils.formatBytes(0));
            return;
        }
        CleanerUtils.scanDirectorySizes(row, List.of(caches), CleanerUtils.DEFAULT_SCAN_MAX_DEPTH, token);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        Path caches = cachesDir();
        if (caches == null || !CleanerUtils.isSafeToCleanDirectory(caches)) return 0;
        return CleanerUtils.deleteDirectoryContents(caches, CleanerUtils.DEFAULT_SCAN_MAX_DEPTH, token);
    }

    private Path cachesDir() {
        String userHome = CleanerUtils.safeEnv("USERPROFILE");
        if (userHome == null) return null;
        Path caches = Paths.get(userHome, ".gradle", "caches");
        return Files.isDirectory(caches) ? caches : null;
    }
}
