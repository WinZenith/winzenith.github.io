package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.*;
import java.util.stream.Stream;

public class OfficeDocumentCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.OFFICE_DOCUMENT_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        if (localAppData != null) {
            Path officeParent = Paths.get(localAppData, "Microsoft", "Office");
            if (Files.isDirectory(officeParent)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(officeParent)) {
                    for (Path versionDir : ds) {
                        if (Files.isDirectory(versionDir)) {
                            Path fileCache = versionDir.resolve("OfficeFileCache");
                            if (Files.isDirectory(fileCache)) {
                                try (Stream<Path> walk = Files.walk(fileCache)) {
                                    var stats = walk.filter(Files::isRegularFile)
                                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                                    totalSize += stats.getSum();
                                    itemCount += (int) stats.getCount();
                                }
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
            Path officeParent = Paths.get(localAppData, "Microsoft", "Office");
            if (Files.isDirectory(officeParent)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(officeParent)) {
                    for (Path versionDir : ds) {
                        if (token != null && token.isCancelled()) break;
                        if (Files.isDirectory(versionDir)) {
                            Path fileCache = versionDir.resolve("OfficeFileCache");
                            if (Files.isDirectory(fileCache)) cleaned += CleanerUtils.deleteDirectoryContents(fileCache, token);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return cleaned;
    }
}
