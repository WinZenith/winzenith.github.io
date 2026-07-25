package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class WindowsDefenderCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.WINDOWS_DEFENDER_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "PROGRAMDATA", "Microsoft", "Windows Defender", "Scans", "History");
        CleanerUtils.scanDirectorySizes(row, dirs, 4);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        List<Path> dirs = new ArrayList<>();
        CleanerUtils.addEnvPath(dirs, "PROGRAMDATA", "Microsoft", "Windows Defender", "Scans", "History");
        return CleanerUtils.cleanDirectoryPattern(dirs);
    }
}
