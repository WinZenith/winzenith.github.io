package com.sbtools.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AppSettings(
        boolean autoBackupDrivers,
        boolean createSystemRestorePoint,
        boolean eulaAccepted,
        List<String> excludedDriverIds,
        List<String> skippedSoftwareIds,
        String networkOptimizationPreset,
        String downloadDirectory,
        boolean minimizeToTray,
        boolean startMinimized,
        boolean scanOnStartup,
        boolean notifyOnDriverUpdate,
        String backupDirectory,
        String powerShellPath,
        int windowWidth,
        int windowHeight,
        boolean windowMaximized,
        boolean autoCheckForUpdates
) {
    public static AppSettings defaults() {
        return new AppSettings(true, true, false,
                Collections.emptyList(), Collections.emptyList(),
                "DEFAULT", System.getProperty("user.home") + "\\Downloads",
                false, false, false, true,
                "", "powershell", 960, 600, true, true);
    }
}
