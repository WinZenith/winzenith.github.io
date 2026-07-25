package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class YarnCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.YARN_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        Path dir = CleanerUtils.safeEnvPath("LOCALAPPDATA", "Yarn", "Cache");
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> walk = Files.walk(dir, 8)) {
                var stats = walk.filter(Files::isRegularFile)
                        .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                totalSize = stats.getSum();
                itemCount = (int) stats.getCount();
            } catch (Exception ignored) {}
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "Yarn", "Cache");
        return CleanerUtils.cleanDirectoryPattern(dirs);
    }
}
