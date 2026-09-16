package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class OfficeDocumentCacheCleaner implements CleanerExtension {

    // Keep recent Upload Center copies: in-progress / unsynced work is typically
    // newer than a week. Only stale cache files are eligible.
    private static final Duration STALE_AGE = Duration.ofDays(7);

    private static final String[] OFFICE_PROCESSES = {
            "winword.exe", "excel.exe", "powerpnt.exe", "onenote.exe"
    };

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.OFFICE_DOCUMENT_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Canceled");
            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
            row.setErrorMessage("Scan canceled by user");
            return;
        }
        if (CleanerUtils.isAnyProcessRunning(OFFICE_PROCESSES)) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Skipped (Office is running — close Word/Excel/PowerPoint/OneNote to clean)");
            return;
        }
        CleanerUtils.scanDirectorySizesOlderThan(row, getCacheDirs(), STALE_AGE, token);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) throws Exception {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) throws Exception {
        if (token != null && token.isCancelled()) return 0L;
        if (CleanerUtils.isAnyProcessRunning(OFFICE_PROCESSES)) {
            throw new IllegalStateException(
                    "Office is running — Office Document Cache was not cleaned");
        }
        return CleanerUtils.cleanDirectoryPatternOlderThan(getCacheDirs(), STALE_AGE, token);
    }

    private List<Path> getCacheDirs() {
        List<Path> dirs = new ArrayList<>();
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        if (localAppData == null) return dirs;
        Path officeParent = Paths.get(localAppData, "Microsoft", "Office");
        if (!CleanerUtils.isRealDirectory(officeParent)) return dirs;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(officeParent)) {
            for (Path versionDir : ds) {
                if (CleanerUtils.isRealDirectory(versionDir)) {
                    Path fileCache = versionDir.resolve("OfficeFileCache");
                    if (CleanerUtils.isRealDirectory(fileCache)) dirs.add(fileCache);
                }
            }
        } catch (Exception ignored) {}
        return dirs;
    }
}
