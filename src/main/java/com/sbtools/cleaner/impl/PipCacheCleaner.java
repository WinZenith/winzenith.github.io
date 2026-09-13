package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class PipCacheCleaner implements CleanerExtension {

    private static final int SCAN_DEPTH = 8;

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.PIP_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "pip", "Cache");
        CleanerUtils.scanDirectorySizes(row, dirs, SCAN_DEPTH, token);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "pip", "Cache");
        return CleanerUtils.cleanDirectoryPattern(dirs, SCAN_DEPTH, token);
    }
}
