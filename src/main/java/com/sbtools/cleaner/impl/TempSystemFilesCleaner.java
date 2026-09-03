package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TempSystemFilesCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.TEMPORARY_SYSTEM_FILES; }

    @Override
    public boolean requiresAdmin() { return true; }

    @Override
    public void scan(CleanupRow row) {
        CleanerUtils.scanDirectorySizes(row, getTempSystemDirs());
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        return CleanerUtils.cleanDirectoryPattern(getTempSystemDirs(), token);
    }

    private List<Path> getTempSystemDirs() {
        List<Path> dirs = new ArrayList<>();
        String windir = CleanerUtils.safeEnv("WINDIR");
        if (windir != null) CleanerUtils.addPath(dirs, windir + "\\Prefetch");
        return dirs;
    }
}
