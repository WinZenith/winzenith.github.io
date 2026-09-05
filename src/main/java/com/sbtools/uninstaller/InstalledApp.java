package com.sbtools.uninstaller;

public class InstalledApp implements Comparable<InstalledApp> {
    private final String name;
    private final String publisher;
    private final String version;
    private final String installLocation;
    private final String uninstallString;
    private final String quietUninstallString;
    private final String registryKeyPath;
    private final boolean win32;
    private final String appxPackageFullName;
    private final String appxPackageName;
    private final String registryHive;
    private final String installDate;
    private final int estimatedSize;
    private final String architecture;

    public InstalledApp(String name, String publisher, String version, String installLocation,
                        String uninstallString, String registryKeyPath, boolean win32,
                        String appxPackageFullName, String registryHive,
                        String installDate, int estimatedSize, String architecture) {
        this(name, publisher, version, installLocation, uninstallString, "", registryKeyPath,
                win32, appxPackageFullName, registryHive, installDate, estimatedSize, architecture);
    }

    public InstalledApp(String name, String publisher, String version, String installLocation,
                        String uninstallString, String quietUninstallString, String registryKeyPath, boolean win32,
                        String appxPackageFullName, String registryHive,
                        String installDate, int estimatedSize, String architecture) {
        this(name, publisher, version, installLocation, uninstallString, quietUninstallString, registryKeyPath,
                win32, appxPackageFullName, "", registryHive, installDate, estimatedSize, architecture);
    }

    public InstalledApp(String name, String publisher, String version, String installLocation,
                        String uninstallString, String quietUninstallString, String registryKeyPath, boolean win32,
                        String appxPackageFullName, String appxPackageName, String registryHive,
                        String installDate, int estimatedSize, String architecture) {
        this.name = name != null ? name.trim() : "";
        this.publisher = publisher != null ? publisher.trim() : "";
        this.version = version != null ? version.trim() : "";
        this.installLocation = installLocation != null ? installLocation.trim() : "";
        this.uninstallString = uninstallString != null ? uninstallString.trim() : "";
        this.quietUninstallString = quietUninstallString != null ? quietUninstallString.trim() : "";
        this.registryKeyPath = registryKeyPath != null ? registryKeyPath.trim() : "";
        this.win32 = win32;
        this.appxPackageFullName = appxPackageFullName != null ? appxPackageFullName.trim() : "";
        this.appxPackageName = appxPackageName != null ? appxPackageName.trim() : "";
        this.registryHive = registryHive != null ? registryHive.trim() : "";
        this.installDate = installDate != null ? installDate.trim() : "";
        this.estimatedSize = estimatedSize;
        this.architecture = architecture != null ? architecture.trim() : "";
    }

    public InstalledApp(String name, String publisher, String version, String installLocation,
                        String uninstallString, String registryKeyPath, boolean win32,
                        String appxPackageFullName, String registryHive) {
        this(name, publisher, version, installLocation, uninstallString, registryKeyPath,
                win32, appxPackageFullName, registryHive, "", 0, "");
    }

    public String getName() { return name; }
    public String getPublisher() { return publisher; }
    public String getVersion() { return version; }
    public String getInstallLocation() { return installLocation; }
    public String getUninstallString() { return uninstallString; }
    public String getQuietUninstallString() { return quietUninstallString; }
    public boolean hasQuietUninstallString() { return quietUninstallString != null && !quietUninstallString.isBlank(); }
    /**
     * Returns the command to run. Interactive (UninstallString) by default;
     * quiet only when explicitly requested and available. Silent uninstall
     * must never run without user consent. Falls back to whichever command
     * exists when only one is present.
     */
    public String getEffectiveUninstallString(boolean preferQuiet) {
        if (preferQuiet && hasQuietUninstallString()) return quietUninstallString;
        if (uninstallString != null && !uninstallString.isBlank()) return uninstallString;
        return quietUninstallString;
    }
    public String getRegistryKeyPath() { return registryKeyPath; }
    public boolean isWin32() { return win32; }
    public String getAppxPackageFullName() { return appxPackageFullName; }
    public String getAppxPackageName() { return appxPackageName; }
    public String getRegistryHive() { return registryHive; }
    public String getInstallDate() { return installDate; }
    public int getEstimatedSize() { return estimatedSize; }
    public String getArchitecture() { return architecture; }
    public boolean hasUninstallString() { return (uninstallString != null && !uninstallString.isBlank()) || hasQuietUninstallString(); }
    public boolean hasAppxIdentity() { return appxPackageFullName != null && !appxPackageFullName.isBlank(); }
    /**
     * Whether the normal Uninstall action can run for this entry: Win32 needs an
     * uninstall command, Store apps need a package identity. Used to disable the
     * action instead of letting it fail unconditionally.
     */
    public boolean canUninstall() {
        if (win32) return hasUninstallString();
        return hasAppxIdentity();
    }

    /**
     * Returns a copy with an updated size estimate. Used for lazy AppX size
     * enrichment without mutating the immutable model.
     */
    public InstalledApp withEstimatedSize(int newSizeKB) {
        return new InstalledApp(name, publisher, version, installLocation,
                uninstallString, quietUninstallString, registryKeyPath, win32,
                appxPackageFullName, appxPackageName, registryHive,
                installDate, newSizeKB, architecture);
    }

    @Override
    public int compareTo(InstalledApp other) {
        int cmp = this.name.compareToIgnoreCase(other.name);
        if (cmp != 0) return cmp;
        return this.version.compareToIgnoreCase(other.version);
    }

    @Override
    public String toString() {
        return name + " (" + (win32 ? "Desktop App" : "Windows Store App") + ")";
    }
}
