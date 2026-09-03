package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class JetBrainsCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.JETBRAINS_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        for (Path dir : getCacheDirs()) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir, 3)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {}
            }
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
        return CleanerUtils.cleanDirectoryPattern(getCacheDirs(), token);
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
