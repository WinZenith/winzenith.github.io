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
 * Stale VS Code workspace storage entries older than 30 days.
 * Distinct from Code\Cache (covered by OTHER_PROGRAMS_CACHE).
 * Conservative: age-gated, never touches settings or extensions.
 */
public class VsCodeWorkspaceStorageCleaner implements CleanerExtension {

    private static final Duration MAX_AGE = Duration.ofDays(30);

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.VSCODE_WORKSPACE_STORAGE; }

    @Override
    public java.util.List<String> describeTargets() {
        return java.util.List.of("%APPDATA%\\Code\\User\\workspaceStorage (entries older than 30 days)");
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
        CleanerUtils.addEnvPath(dirs, "APPDATA", "Code", "User", "workspaceStorage");
        return CleanerUtils.deduplicatePaths(dirs);
    }
}
