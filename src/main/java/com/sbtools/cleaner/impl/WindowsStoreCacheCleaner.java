package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class WindowsStoreCacheCleaner implements CleanerExtension {

    private static final long CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.WINDOWS_STORE_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        List<Path> caches = getCacheDirs();
        long cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS;
        CleanerUtils.scanFilesMatching(row, caches, 1, f -> isStaleCacheFile(f, cutoff), token);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS;
        long cleaned = 0;
        for (Path localCache : getCacheDirs()) {
            if (token != null && token.isCancelled()) break;
            cleaned += CleanerUtils.deleteFilesMatching(localCache, 1, token, f -> isStaleCacheFile(f, cutoff));
        }
        return cleaned;
    }

    private static boolean isStaleCacheFile(Path f, long cutoff) {
        try {
            if (Files.isHidden(f)) return false;
            long modified = Files.getLastModifiedTime(f).toMillis();
            return modified > 0 && modified < cutoff;
        } catch (Exception e) {
            return false;
        }
    }

    private List<Path> getCacheDirs() {
        List<Path> dirs = new ArrayList<>();
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        if (localAppData == null) return dirs;
        Path packagesDir = Paths.get(localAppData, "Packages");
        if (!Files.isDirectory(packagesDir)) return dirs;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(packagesDir)) {
            for (Path pkg : ds) {
                if (Files.isDirectory(pkg)) {
                    Path localCache = pkg.resolve("LocalCache");
                    if (Files.isDirectory(localCache)) dirs.add(localCache);
                }
            }
        } catch (Exception ignored) {}
        return dirs;
    }
}
