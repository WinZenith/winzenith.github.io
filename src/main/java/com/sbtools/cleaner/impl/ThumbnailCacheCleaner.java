package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public class ThumbnailCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.THUMBNAIL_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        if (localAppData != null) {
            Path explorerDir = Paths.get(localAppData, "Microsoft", "Windows", "Explorer");
            if (Files.isDirectory(explorerDir)) {
                try (Stream<Path> files = Files.list(explorerDir)) {
                    var matched = files.filter(Files::isRegularFile)
                            .filter(p -> { String name = p.getFileName().toString().toLowerCase(); return name.startsWith("thumbcache_"); })
                            .toList();
                    for (Path f : matched) { totalSize += f.toFile().length(); itemCount++; }
                } catch (Exception ignored) {}
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        long cleaned = 0;
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        if (localAppData != null) {
            Path explorerDir = Paths.get(localAppData, "Microsoft", "Windows", "Explorer");
            if (Files.isDirectory(explorerDir)) {
                try (Stream<Path> files = Files.list(explorerDir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        if (Files.isRegularFile(f)) {
                            String name = f.getFileName().toString().toLowerCase();
                            if (name.startsWith("thumbcache_")) {
                                long size = Files.size(f); CleanerUtils.deletePermanently(f); if (!Files.exists(f)) cleaned += size;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return cleaned;
    }
}
