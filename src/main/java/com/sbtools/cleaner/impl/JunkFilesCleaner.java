package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;

public class JunkFilesCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.JUNK_FILES; }

    @Override
    public boolean requiresAdmin() { return true; }

    @Override
    public void scan(CleanupRow row) {
        CleanerUtils.scanDirectorySizesOlderThan(row, getJunkDirs(), Duration.ofDays(1));
    }

    @Override
    public long clean(Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        return CleanerUtils.cleanDirectoryPatternOlderThan(getJunkDirs(), Duration.ofDays(1), token);
    }

    private List<Path> getJunkDirs() {
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "TEMP");
        CleanerUtils.addEnvPath(dirs, "WINDIR", "Temp");
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "Temp");
        return CleanerUtils.deduplicatePaths(dirs);
    }
}
