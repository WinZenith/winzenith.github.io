package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Temp files under Windows service profiles (LocalService / NetworkService).
 * Distinct from user TEMP (covered by JUNK_FILES). Only files older than
 * 1 day are counted/cleaned. Admin required.
 */
public class ServiceProfileTempCleaner implements CleanerExtension {

    private static final Duration MAX_AGE = Duration.ofDays(1);

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.SERVICE_PROFILE_TEMP; }

    @Override
    public boolean requiresAdmin() { return true; }

    @Override
    public java.util.List<String> describeTargets() {
        return java.util.List.of(
                "%WINDIR%\\ServiceProfiles\\LocalService\\AppData\\Local\\Temp (files older than 1 day)",
                "%WINDIR%\\ServiceProfiles\\NetworkService\\AppData\\Local\\Temp (files older than 1 day)");
    }

    @Override
    public void scan(CleanupRow row) {
        CleanerUtils.scanDirectorySizesOlderThan(row, getDirs(), MAX_AGE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        return CleanerUtils.cleanDirectoryPatternOlderThan(getDirs(), MAX_AGE, token);
    }

    private List<Path> getDirs() {
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "WINDIR", "ServiceProfiles", "LocalService", "AppData", "Local", "Temp");
        CleanerUtils.addEnvPath(dirs, "WINDIR", "ServiceProfiles", "NetworkService", "AppData", "Local", "Temp");
        return CleanerUtils.deduplicatePaths(dirs);
    }
}
