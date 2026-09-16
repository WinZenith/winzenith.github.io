package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;
import com.sbtools.util.WindowsServicingSafety;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SoftwareDistributionCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.SOFTWARE_DISTRIBUTION_CACHE; }

    @Override
    public boolean requiresAdmin() { return true; }

    record BlockReason(boolean blocked, String message) {}

    BlockReason eligibilityForScanOrClean() {
        if (WindowsServicingSafety.isServicingPending()) {
            String reasons = String.join("; ", WindowsServicingSafety.getPendingReasons());
            return new BlockReason(true, "Skipped (pending system restart: " + reasons + ")");
        }
        if (CleanerUtils.isWindowsUpdateBusy()) {
            return new BlockReason(true, "Skipped (Windows Update active)");
        }
        return new BlockReason(false, null);
    }

    @Override
    public void scan(CleanupRow row) {
        BlockReason block = eligibilityForScanOrClean();
        if (block.blocked()) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText(block.message());
            return;
        }
        String windir = CleanerUtils.safeEnv("WINDIR");
        List<Path> dirs = new ArrayList<>();
        if (windir != null) {
            CleanerUtils.addPath(dirs, windir + "\\SoftwareDistribution\\Download");
        }
        CleanerUtils.scanDirectorySizes(row, dirs, CleanerUtils.DEFAULT_SCAN_MAX_DEPTH);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        BlockReason block = eligibilityForScanOrClean();
        if (block.blocked()) {
            AppLogger.info("Skipping SoftwareDistribution cache: " + block.message());
            return 0;
        }
        if (token != null && token.isCancelled()) return 0L;
        long cleaned = 0;
        String windir = CleanerUtils.safeEnv("WINDIR");
        if (windir != null) {
            List<Path> dirs = new ArrayList<>();
            CleanerUtils.addPath(dirs, windir + "\\SoftwareDistribution\\Download");
            for (Path dir : dirs) {
                if (token != null && token.isCancelled()) break;
                if (dir != null && Files.isDirectory(dir)
                        && CleanerUtils.isSafeToCleanDirectory(dir)) {
                    cleaned += CleanerUtils.deleteDirectoryContents(dir, CleanerUtils.DEFAULT_SCAN_MAX_DEPTH, token);
                }
            }
        }
        return cleaned;
    }
}
