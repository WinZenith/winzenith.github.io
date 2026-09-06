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
        boolean autoCheckForUpdates,
        List<String> ignoredBrowserExtensionIds,
        List<String> ignoredCleanupCategories,
        boolean autoCreateRestoreBeforeCleanup,
        List<String> duplicateScanRoots,
        long duplicateMinSizeBytes,
        String duplicateIncludeFilter,
        String duplicateKeeperStrategy
) {
    public static AppSettings defaults() {
        return new AppSettings(true, true, false,
                Collections.emptyList(), Collections.emptyList(),
                "DEFAULT", System.getProperty("user.home") + "\\Downloads",
                false, false, false, true,
                "", "powershell", 960, 600, true, true,
                Collections.emptyList(), Collections.emptyList(), true,
                Collections.emptyList(), 1L, "", "NEWEST");
    }

    /**
     * Returns a builder seeded with the current values of this instance.
     * This avoids the brittle 17-argument constructor call sites.
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    public AppSettings withExcludedDriverIds(List<String> newExcludedDriverIds) {
        return toBuilder().excludedDriverIds(newExcludedDriverIds).build();
    }

    public static final class Builder {
        private boolean autoBackupDrivers;
        private boolean createSystemRestorePoint;
        private boolean eulaAccepted;
        private List<String> excludedDriverIds;
        private List<String> skippedSoftwareIds;
        private String networkOptimizationPreset;
        private String downloadDirectory;
        private boolean minimizeToTray;
        private boolean startMinimized;
        private boolean scanOnStartup;
        private boolean notifyOnDriverUpdate;
        private String backupDirectory;
        private String powerShellPath;
        private int windowWidth;
        private int windowHeight;
        private boolean windowMaximized;
        private boolean autoCheckForUpdates;
        private List<String> ignoredBrowserExtensionIds;
        private List<String> ignoredCleanupCategories;
        private boolean autoCreateRestoreBeforeCleanup;
        private List<String> duplicateScanRoots;
        private long duplicateMinSizeBytes;
        private String duplicateIncludeFilter;
        private String duplicateKeeperStrategy;

        private Builder(AppSettings s) {
            this.autoBackupDrivers = s.autoBackupDrivers;
            this.createSystemRestorePoint = s.createSystemRestorePoint;
            this.eulaAccepted = s.eulaAccepted;
            this.excludedDriverIds = s.excludedDriverIds;
            this.skippedSoftwareIds = s.skippedSoftwareIds;
            this.networkOptimizationPreset = s.networkOptimizationPreset;
            this.downloadDirectory = s.downloadDirectory;
            this.minimizeToTray = s.minimizeToTray;
            this.startMinimized = s.startMinimized;
            this.scanOnStartup = s.scanOnStartup;
            this.notifyOnDriverUpdate = s.notifyOnDriverUpdate;
            this.backupDirectory = s.backupDirectory;
            this.powerShellPath = s.powerShellPath;
            this.windowWidth = s.windowWidth;
            this.windowHeight = s.windowHeight;
            this.windowMaximized = s.windowMaximized;
            this.autoCheckForUpdates = s.autoCheckForUpdates;
            this.ignoredBrowserExtensionIds = s.ignoredBrowserExtensionIds;
            this.ignoredCleanupCategories = s.ignoredCleanupCategories;
            this.autoCreateRestoreBeforeCleanup = s.autoCreateRestoreBeforeCleanup;
            this.duplicateScanRoots = s.duplicateScanRoots;
            this.duplicateMinSizeBytes = s.duplicateMinSizeBytes;
            this.duplicateIncludeFilter = s.duplicateIncludeFilter;
            this.duplicateKeeperStrategy = s.duplicateKeeperStrategy;
        }

        public Builder autoBackupDrivers(boolean v) { this.autoBackupDrivers = v; return this; }
        public Builder createSystemRestorePoint(boolean v) { this.createSystemRestorePoint = v; return this; }
        public Builder eulaAccepted(boolean v) { this.eulaAccepted = v; return this; }
        public Builder excludedDriverIds(List<String> v) { this.excludedDriverIds = v; return this; }
        public Builder skippedSoftwareIds(List<String> v) { this.skippedSoftwareIds = v; return this; }
        public Builder networkOptimizationPreset(String v) { this.networkOptimizationPreset = v; return this; }
        public Builder downloadDirectory(String v) { this.downloadDirectory = v; return this; }
        public Builder minimizeToTray(boolean v) { this.minimizeToTray = v; return this; }
        public Builder startMinimized(boolean v) { this.startMinimized = v; return this; }
        public Builder scanOnStartup(boolean v) { this.scanOnStartup = v; return this; }
        public Builder notifyOnDriverUpdate(boolean v) { this.notifyOnDriverUpdate = v; return this; }
        public Builder backupDirectory(String v) { this.backupDirectory = v; return this; }
        public Builder powerShellPath(String v) { this.powerShellPath = v; return this; }
        public Builder windowWidth(int v) { this.windowWidth = v; return this; }
        public Builder windowHeight(int v) { this.windowHeight = v; return this; }
        public Builder windowMaximized(boolean v) { this.windowMaximized = v; return this; }
        public Builder autoCheckForUpdates(boolean v) { this.autoCheckForUpdates = v; return this; }
        public Builder ignoredBrowserExtensionIds(List<String> v) { this.ignoredBrowserExtensionIds = v; return this; }
        public Builder ignoredCleanupCategories(List<String> v) { this.ignoredCleanupCategories = v; return this; }
        public Builder autoCreateRestoreBeforeCleanup(boolean v) { this.autoCreateRestoreBeforeCleanup = v; return this; }
        public Builder duplicateScanRoots(List<String> v) { this.duplicateScanRoots = v; return this; }
        public Builder duplicateMinSizeBytes(long v) { this.duplicateMinSizeBytes = Math.max(1L, v); return this; }
        public Builder duplicateIncludeFilter(String v) { this.duplicateIncludeFilter = v; return this; }
        public Builder duplicateKeeperStrategy(String v) { this.duplicateKeeperStrategy = v; return this; }

        public AppSettings build() {
            return new AppSettings(
                    autoBackupDrivers,
                    createSystemRestorePoint,
                    eulaAccepted,
                    excludedDriverIds,
                    skippedSoftwareIds,
                    networkOptimizationPreset,
                    downloadDirectory,
                    minimizeToTray,
                    startMinimized,
                    scanOnStartup,
                    notifyOnDriverUpdate,
                    backupDirectory,
                    powerShellPath,
                    windowWidth,
                    windowHeight,
                    windowMaximized,
                    autoCheckForUpdates,
                    ignoredBrowserExtensionIds,
                    ignoredCleanupCategories,
                    autoCreateRestoreBeforeCleanup,
                    duplicateScanRoots,
                    duplicateMinSizeBytes,
                    duplicateIncludeFilter,
                    duplicateKeeperStrategy);
        }
    }
}
