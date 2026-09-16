package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FontCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.FONT_CACHE; }

    @Override
    public boolean requiresAdmin() { return true; }

    @Override
    public void scan(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "WINDIR", "ServiceProfiles", "LocalService", "AppData", "Local", "FontCache");
        CleanerUtils.scanDirectorySizes(row, dirs);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        List<Path> probe = new ArrayList<>();
        CleanerUtils.addEnvPath(probe, "WINDIR", "ServiceProfiles", "LocalService", "AppData", "Local", "FontCache");
        if (probe.isEmpty()) return 0L;
        // Only restart services that were running: starting a stopped/disabled
        // service changes system state beyond the cleanup.
        boolean fontWasRunning = CleanerUtils.serviceShouldBeRestoredAfterStop("FontCache");
        boolean font3WasRunning = CleanerUtils.serviceShouldBeRestoredAfterStop("FontCache3.0.0.0");
        try {
            stopService("FontCache");
            if (token != null && token.isCancelled()) return 0L;
            stopService("FontCache3.0.0.0");
            if (token != null && token.isCancelled()) return 0L;
            List<Path> dirs = new ArrayList<>();
            CleanerUtils.addEnvPath(dirs, "WINDIR", "ServiceProfiles", "LocalService", "AppData", "Local", "FontCache");
            return CleanerUtils.cleanDirectoryPattern(dirs, token);
        } finally {
            if (fontWasRunning) startService("FontCache");
            if (font3WasRunning) startService("FontCache3.0.0.0");
        }
    }

    private void stopService(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("net", "stop", serviceName);
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            if (!CleanerUtils.waitForProcessUninterruptibly(p, 15_000L)) p.destroyForcibly();
        } catch (Exception e) {
            AppLogger.warning("Failed to stop service " + serviceName + ": " + e.getMessage());
        }
    }

    private void startService(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("net", "start", serviceName);
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            // Must finish even if the clean was cancelled — otherwise fonts stay broken.
            CleanerUtils.waitForProcessUninterruptibly(p, 15_000L);
        } catch (Exception e) {
            AppLogger.warning("Failed to start service " + serviceName + ": " + e.getMessage());
        }
    }
}
