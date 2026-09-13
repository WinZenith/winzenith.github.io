package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class JetBrainsCacheCleaner implements CleanerExtension {

    private static final int SCAN_DEPTH = 3;

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.JETBRAINS_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        CleanerUtils.scanDirectorySizes(row, getCacheDirs(), SCAN_DEPTH, token);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        return CleanerUtils.cleanDirectoryPattern(getCacheDirs(), SCAN_DEPTH, token);
    }

    private List<Path> getCacheDirs() {
        List<Path> dirs = new ArrayList<>();
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData != null) {
            Path jetbrains = Path.of(appData, "JetBrains");
            if (Files.isDirectory(jetbrains)) {
                addCacheSubdirs(dirs, jetbrains);
            }
        }
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        if (localAppData != null) {
            Path jetbrains = Path.of(localAppData, "JetBrains");
            if (Files.isDirectory(jetbrains)) {
                addCacheSubdirs(dirs, jetbrains);
            }
        }
        return dirs;
    }

    private void addCacheSubdirs(List<Path> dirs, Path jetbrains) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(jetbrains)) {
            for (Path versionDir : ds) {
                if (Files.isDirectory(versionDir)) {
                    Path cache = versionDir.resolve("caches");
                    if (Files.isDirectory(cache)) dirs.add(cache);
                    Path cache2 = versionDir.resolve("cache");
                    if (Files.isDirectory(cache2)) dirs.add(cache2);
                }
            }
        } catch (Exception ignored) {}
    }
}
