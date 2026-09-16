package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class WindowsLogFilesCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.WINDOWS_LOG_FILES; }

    @Override
    public boolean requiresAdmin() { return true; }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        if (com.sbtools.util.WindowsServicingSafety.isServicingPending()) {
            String reasons = String.join("; ", com.sbtools.util.WindowsServicingSafety.getPendingReasons());
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Skipped (pending system restart: " + reasons + ")");
            return;
        }
        Path logsDir = logsDir();
        if (logsDir == null) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText(CleanerUtils.formatBytes(0));
            return;
        }
        CleanerUtils.scanFilesMatching(row, List.of(logsDir), 2, WindowsLogFilesCleaner::isLogFile, token);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        if (com.sbtools.util.WindowsServicingSafety.isServicingPending()) {
            com.sbtools.util.AppLogger.info("Skipping Windows log cleanup: pending system restart");
            return 0;
        }
        Path logsDir = logsDir();
        if (logsDir == null || !CleanerUtils.isSafeToCleanDirectory(logsDir)) return 0;
        return CleanerUtils.deleteFilesMatching(logsDir, 2, token, WindowsLogFilesCleaner::isLogFile);
    }

    private static boolean isLogFile(Path p) {
        if (p == null || p.getFileName() == null) return false;
        return p.getFileName().toString().toLowerCase().endsWith(".log");
    }

    private Path logsDir() {
        String windir = CleanerUtils.safeEnv("WINDIR");
        if (windir == null) return null;
        Path logsDir = Paths.get(windir, "Logs");
        return Files.isDirectory(logsDir) ? logsDir : null;
    }
}
