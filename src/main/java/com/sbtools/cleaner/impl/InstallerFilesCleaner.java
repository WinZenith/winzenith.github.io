package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class InstallerFilesCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.INSTALLER_FILES; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;

        Path tempDir = CleanerUtils.safeEnvPath("TEMP");
        if (tempDir != null && Files.isDirectory(tempDir)) {
            try (Stream<Path> files = Files.list(tempDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f)) {
                        String name = f.getFileName().toString().toLowerCase();
                        if ((name.endsWith(".msi") || name.endsWith(".exe"))) {
                            long size = f.toFile().length();
                            if (size > 10 * 1024 * 1024) {
                                totalSize += size;
                                itemCount++;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        long cleaned = 0;
        Path tempDir = CleanerUtils.safeEnvPath("TEMP");
        if (tempDir != null && Files.isDirectory(tempDir)) {
            try (Stream<Path> files = Files.list(tempDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f)) {
                        String name = f.getFileName().toString().toLowerCase();
                        if ((name.endsWith(".msi") || name.endsWith(".exe"))) {
                            long size = f.toFile().length();
                            if (size > 10 * 1024 * 1024) {
                                CleanerUtils.deletePermanently(f);
                                if (!Files.exists(f)) cleaned += size;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        return cleaned;
    }
}
