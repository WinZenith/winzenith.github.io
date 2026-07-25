package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public class MavenCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.MAVEN_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String userHome = CleanerUtils.safeEnv("USERPROFILE");
        if (userHome != null) {
            Path repo = Paths.get(userHome, ".m2", "repository");
            if (Files.isDirectory(repo)) {
                Path snapshots = repo.resolve("snapshots");
                if (Files.isDirectory(snapshots)) {
                    try (Stream<Path> walk = Files.walk(snapshots, 6)) {
                        var stats = walk.filter(Files::isRegularFile)
                                .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                        totalSize = stats.getSum();
                        itemCount = (int) stats.getCount();
                    } catch (Exception ignored) {}
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(totalSize > 0
                ? CleanerUtils.formatBytes(totalSize) + " (old snapshots)"
                : "No snapshot artifacts found");
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        long cleaned = 0;
        String userHome = CleanerUtils.safeEnv("USERPROFILE");
        if (userHome == null) return 0;
        Path snapshots = Paths.get(userHome, ".m2", "repository", "snapshots");
        if (!Files.isDirectory(snapshots)) return 0;
        try (Stream<Path> walk = Files.walk(snapshots, 6)) {
            var matched = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith("-snapshot.jar") ||
                                 p.toString().toLowerCase().endsWith("-snapshot.pom"))
                    .toList();
            for (Path f : matched) {
                long size = f.toFile().length();
                CleanerUtils.deletePermanently(f);
                cleaned += size;
            }
        } catch (Exception ignored) {}
        return cleaned;
    }
}
