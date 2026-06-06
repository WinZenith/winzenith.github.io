package com.sbtools.uninstaller;

/**
 * Represents an installed application on the system (either a Win32 app or a Windows Store Appx package).
 */
public class InstalledApp {
    private final String name;
    private final String publisher;
    private final String version;
    private final String installLocation;
    private final String uninstallString;
    private final String registryKeyPath;
    private final boolean win32;
    private final String appxPackageFullName;
    private final String registryHive; // "HKLM" or "HKCU"

    public InstalledApp(String name, String publisher, String version, String installLocation,
                        String uninstallString, String registryKeyPath, boolean win32,
                        String appxPackageFullName, String registryHive) {
        this.name = name != null ? name.trim() : "";
        this.publisher = publisher != null ? publisher.trim() : "";
        this.version = version != null ? version.trim() : "";
        this.installLocation = installLocation != null ? installLocation.trim() : "";
        this.uninstallString = uninstallString != null ? uninstallString.trim() : "";
        this.registryKeyPath = registryKeyPath != null ? registryKeyPath.trim() : "";
        this.win32 = win32;
        this.appxPackageFullName = appxPackageFullName != null ? appxPackageFullName.trim() : "";
        this.registryHive = registryHive != null ? registryHive.trim() : "";
    }

    public String getName() {
        return name;
    }

    public String getPublisher() {
        return publisher;
    }

    public String getVersion() {
        return version;
    }

    public String getInstallLocation() {
        return installLocation;
    }

    public String getUninstallString() {
        return uninstallString;
    }

    public String getRegistryKeyPath() {
        return registryKeyPath;
    }

    public boolean isWin32() {
        return win32;
    }

    public String getAppxPackageFullName() {
        return appxPackageFullName;
    }

    public String getRegistryHive() {
        return registryHive;
    }

    @Override
    public String toString() {
        return name + " (" + (win32 ? "Desktop App" : "Windows Store App") + ")";
    }
}
