package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TaskbarJumpListsCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.TASKBAR_JUMP_LISTS; }

    @Override
    public void scan(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "APPDATA", "Microsoft", "Windows", "Recent", "AutomaticDestinations");
        CleanerUtils.addEnvPath(dirs, "APPDATA", "Microsoft", "Windows", "Recent", "CustomDestinations");
        CleanerUtils.scanDirectorySizes(row, dirs);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "APPDATA", "Microsoft", "Windows", "Recent", "AutomaticDestinations");
        CleanerUtils.addEnvPath(dirs, "APPDATA", "Microsoft", "Windows", "Recent", "CustomDestinations");
        return CleanerUtils.cleanDirectoryPattern(dirs, token);
    }
}
