package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TempSystemFilesCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.TEMPORARY_SYSTEM_FILES; }

    @Override
    public void scan(CleanupRow row) {
        CleanerUtils.scanDirectorySizes(row, getTempSystemDirs());
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return CleanerUtils.cleanDirectoryPattern(getTempSystemDirs());
    }

    private List<Path> getTempSystemDirs() {
        List<Path> dirs = new ArrayList<>();
        String windir = CleanerUtils.safeEnv("WINDIR");
        if (windir != null) CleanerUtils.addPath(dirs, windir + "\\Prefetch");
        if (!isUpgradeInProgress()) {
            for (java.io.File root : java.io.File.listRoots()) {
                Path btDir = root.toPath().resolve("$Windows.~BT");
                Path wsDir = root.toPath().resolve("$Windows.~WS");
                Path resetDir = root.toPath().resolve("$SysReset");
                if (Files.exists(btDir)) dirs.add(btDir);
                if (Files.exists(wsDir)) dirs.add(wsDir);
                if (Files.exists(resetDir)) dirs.add(resetDir);
            }
        }
        return dirs;
    }

    private boolean isUpgradeInProgress() {
        try {
            String keyPath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Setup\\State";
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, keyPath)) {
                String state = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, keyPath, "ImageState");
                if (state != null && !state.isEmpty()) {
                    state = state.toLowerCase();
                    return !state.contains("complete") && !state.contains("finalize");
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
