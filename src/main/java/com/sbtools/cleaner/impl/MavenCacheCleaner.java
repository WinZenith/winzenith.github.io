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
                try (Stream<Path> walk = Files.walk(repo)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                String n = p.getFileName().toString().toLowerCase();
                                return n.endsWith("-snapshot.jar") || n.endsWith("-snapshot.pom");
                            })
                            .collect(java.util.stream.Collectors.summarizingLong(p -> {
                                try { return Files.size(p); } catch (Exception e) { return p.toFile().length(); }
                            }));
                    totalSize = stats.getSum();
                    itemCount = (int) stats.getCount();
                } catch (Exception ignored) {}
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(totalSize > 0
                ? CleanerUtils.formatBytes(totalSize) + " (" + itemCount + " snapshot files)"
                : "No snapshot artifacts found");
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long cleaned = 0;
        String userHome = CleanerUtils.safeEnv("USERPROFILE");
        if (userHome == null) return 0;
        Path repo = Paths.get(userHome, ".m2", "repository");
        if (!Files.isDirectory(repo)) return 0;
        try (Stream<Path> walk = Files.walk(repo)) {
            var matched = walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase();
                        return n.endsWith("-snapshot.jar") || n.endsWith("-snapshot.pom");
                    })
                    .toList();
            for (Path f : matched) {
                if (token != null && token.isCancelled()) break;
                try {
                    long size = Files.size(f);
                    CleanerUtils.deletePermanently(f, token);
                    if (!Files.exists(f)) cleaned += size;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return cleaned;
    }
}
