package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class NotificationHistoryCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.NOTIFICATION_HISTORY; }

    @Override
    public void scan(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "Windows", "Notifications");
        CleanerUtils.scanDirectorySizes(row, dirs);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "Windows", "Notifications");
        return CleanerUtils.cleanDirectoryPattern(dirs);
    }
}
