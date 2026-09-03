package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.*;
import java.util.stream.Stream;

public class WindowsStoreCacheCleaner implements CleanerExtension {

    private static final long CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.WINDOWS_STORE_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        if (localAppData != null) {
            Path packagesDir = Paths.get(localAppData, "Packages");
            if (Files.isDirectory(packagesDir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(packagesDir)) {
                    for (Path pkg : ds) {
                        if (Files.isDirectory(pkg)) {
                            Path localCache = pkg.resolve("LocalCache");
                            if (Files.isDirectory(localCache)) {
                                try (Stream<Path> walk = Files.walk(localCache, 1)) {
                                    long cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS;
                                    var stats = walk.filter(Files::isRegularFile)
                                            .filter(f -> { try { return !Files.isHidden(f); } catch (Exception e) { return true; } })
                                            .filter(f -> { try { return f.toFile().lastModified() > 0 && f.toFile().lastModified() < cutoff; } catch (Exception e) { return false; } })
                                            .collect(java.util.stream.Collectors.summarizingLong(f -> f.toFile().length()));
                                    totalSize += stats.getSum();
                                    itemCount += (int) stats.getCount();
                                } catch (Exception ignored) {}
                            }
                        }
                    }
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
        long cleaned = 0;
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        if (localAppData != null) {
            Path packagesDir = Paths.get(localAppData, "Packages");
            if (Files.isDirectory(packagesDir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(packagesDir)) {
                    for (Path pkg : ds) {
                        if (token != null && token.isCancelled()) break;
                        if (Files.isDirectory(pkg)) {
                            Path localCache = pkg.resolve("LocalCache");
                            if (Files.isDirectory(localCache)) {
                                try (Stream<Path> walk = Files.walk(localCache, 1)) {
                                    long cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS;
                                    for (Path f : (Iterable<Path>) walk::iterator) {
                                        if (token != null && token.isCancelled()) break;
                                        if (!f.equals(localCache) && Files.isRegularFile(f)) {
                                            try {
                                                if (!Files.isHidden(f) && f.toFile().lastModified() > 0 && f.toFile().lastModified() < cutoff) {
                                                    long size = Files.size(f);
                                                    CleanerUtils.deletePermanently(f, token);
                                                    if (!Files.exists(f)) cleaned += size;
                                                }
                                            } catch (Exception ignored) {}
                                        }
                                    }
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return cleaned;
    }
}
