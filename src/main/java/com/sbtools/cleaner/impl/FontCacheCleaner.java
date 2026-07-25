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
        stopService("FontCache");
        stopService("FontCache3.0.0.0");
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "WINDIR", "ServiceProfiles", "LocalService", "AppData", "Local", "FontCache");
        long cleaned = CleanerUtils.cleanDirectoryPattern(dirs);
        startService("FontCache");
        startService("FontCache3.0.0.0");
        return cleaned;
    }

    private void stopService(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("net", "stop", serviceName);
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean ok = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) p.destroyForcibly();
        } catch (Exception e) {
            AppLogger.warning("Failed to stop service " + serviceName + ": " + e.getMessage());
        }
    }

    private void startService(String serviceName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("net", "start", serviceName);
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean ok = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) p.destroyForcibly();
        } catch (Exception e) {
            AppLogger.warning("Failed to start service " + serviceName + ": " + e.getMessage());
        }
    }
}
