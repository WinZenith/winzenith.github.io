package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * OneDrive sync/setup logs only (*.odl, *.odlsent, *.log under OneDrive logs dirs).
 * Never touches actual synced files. Conservative LOW risk.
 */
public class OneDriveSyncLogsCleaner implements CleanerExtension {

    private static final List<String> LOG_EXTENSIONS = List.of(".odl", ".odlsent", ".log", ".etl.log");

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.ONEDRIVE_SYNC_LOGS; }

    @Override
    public java.util.List<String> describeTargets() {
        return java.util.List.of(
                "%LOCALAPPDATA%\\Microsoft\\OneDrive\\logs (*.odl, *.odlsent, *.log, *.etl.log)",
                "%LOCALAPPDATA%\\Microsoft\\OneDrive\\setup\\logs (*.odl, *.odlsent, *.log, *.etl.log)",
                "%LOCALAPPDATA%\\Microsoft\\OneDrive\\Update\\logs (*.odl, *.odlsent, *.log, *.etl.log)");
    }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        CleanerUtils.scanFilesMatching(row, getDirs(), CleanerUtils.DEFAULT_SCAN_MAX_DEPTH,
                OneDriveSyncLogsCleaner::isLogFile, token);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long cleaned = 0;
        for (Path dir : getDirs()) {
            if (token != null && token.isCancelled()) break;
            cleaned += CleanerUtils.deleteFilesMatching(dir, CleanerUtils.DEFAULT_SCAN_MAX_DEPTH, token,
                    OneDriveSyncLogsCleaner::isLogFile);
        }
        return cleaned;
    }

    private static boolean isLogFile(Path p) {
        if (p == null || p.getFileName() == null) return false;
        String name = p.getFileName().toString().toLowerCase();
        for (String ext : LOG_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private List<Path> getDirs() {
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "OneDrive", "logs");
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "OneDrive", "setup", "logs");
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "OneDrive", "Update", "logs");
        return CleanerUtils.deduplicatePaths(dirs);
    }
}
