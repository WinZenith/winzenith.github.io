package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

public class GradleCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.GRADLE_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String userHome = CleanerUtils.safeEnv("USERPROFILE");
        if (userHome != null) {
            Path caches = Paths.get(userHome, ".gradle", "caches");
            if (Files.isDirectory(caches)) {
                try (Stream<Path> walk = Files.walk(caches, 3)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize = stats.getSum();
                    itemCount = (int) stats.getCount();
                } catch (Exception ignored) {}
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        String userHome = CleanerUtils.safeEnv("USERPROFILE");
        if (userHome == null) return 0;
        Path caches = Paths.get(userHome, ".gradle", "caches");
        if (!Files.isDirectory(caches)) return 0;
        long cleaned = 0;
        try (Stream<Path> walk = Files.walk(caches, 3)) {
            List<Path> sorted = walk.sorted(java.util.Comparator.reverseOrder()).toList();
            for (Path f : sorted) {
                if (f.equals(caches)) continue;
                try {
                    if (Files.isRegularFile(f)) {
                        long size = Files.size(f);
                        CleanerUtils.deletePermanently(f);
                        cleaned += size;
                    } else if (Files.isDirectory(f)) {
                        Files.deleteIfExists(f);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return cleaned;
    }
}
