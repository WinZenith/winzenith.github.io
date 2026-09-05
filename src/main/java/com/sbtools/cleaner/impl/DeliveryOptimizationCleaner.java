package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Delivery Optimization download cache. Conservative: skipped while servicing
 * is pending (active updates / pending reboot), admin required (WINDIR).
 */
public class DeliveryOptimizationCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.DELIVERY_OPTIMIZATION_FILES; }

    @Override
    public boolean requiresAdmin() { return true; }

    @Override
    public java.util.List<String> describeTargets() {
        return java.util.List.of(
                "%WINDIR%\\SoftwareDistribution\\DeliveryOptimization",
                "%PROGRAMDATA%\\Microsoft\\Windows\\DeliveryOptimization",
                "%LOCALAPPDATA%\\Microsoft\\Windows\\DeliveryOptimization");
    }

    @Override
    public void scan(CleanupRow row) {
        if (com.sbtools.util.WindowsServicingSafety.isServicingPending()) {
            String reasons = String.join("; ", com.sbtools.util.WindowsServicingSafety.getPendingReasons());
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Skipped (pending system restart: " + reasons + ")");
            return;
        }
        CleanerUtils.scanDirectorySizes(row, getDirs(), CleanerUtils.DEFAULT_SCAN_MAX_DEPTH);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        if (com.sbtools.util.WindowsServicingSafety.isServicingPending()) {
            com.sbtools.util.AppLogger.info("Skipping Delivery Optimization cleanup: pending system restart");
            return 0;
        }
        return CleanerUtils.cleanDirectoryPattern(getDirs(), token);
    }

    private List<Path> getDirs() {
        List<Path> dirs = new ArrayList<>();
        // Distinct from SoftwareDistribution\Download (covered by SOFTWARE_DISTRIBUTION_CACHE).
        CleanerUtils.addEnvPath(dirs, "WINDIR", "SoftwareDistribution", "DeliveryOptimization");
        CleanerUtils.addEnvPath(dirs, "PROGRAMDATA", "Microsoft", "Windows", "DeliveryOptimization");
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "Windows", "DeliveryOptimization");
        return CleanerUtils.deduplicatePaths(dirs);
    }
}
