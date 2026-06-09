package com.sbtools.uninstaller;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

public class InstalledApp {
    private final BooleanProperty selected = new SimpleBooleanProperty(false);

    private final String name;
    private final String publisher;
    private final String version;
    private final String installLocation;
    private final String uninstallString;
    private final String registryKeyPath;
    private final boolean win32;
    private final String appxPackageFullName;
    private final String registryHive;
    private final String installDate;
    private final int estimatedSize;
    private final String architecture;

    public InstalledApp(String name, String publisher, String version, String installLocation,
                        String uninstallString, String registryKeyPath, boolean win32,
                        String appxPackageFullName, String registryHive,
                        String installDate, int estimatedSize, String architecture) {
        this.name = name != null ? name.trim() : "";
        this.publisher = publisher != null ? publisher.trim() : "";
        this.version = version != null ? version.trim() : "";
        this.installLocation = installLocation != null ? installLocation.trim() : "";
        this.uninstallString = uninstallString != null ? uninstallString.trim() : "";
        this.registryKeyPath = registryKeyPath != null ? registryKeyPath.trim() : "";
        this.win32 = win32;
        this.appxPackageFullName = appxPackageFullName != null ? appxPackageFullName.trim() : "";
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

    public BooleanProperty selectedProperty() { return selected; }
    public boolean isSelected() { return selected.get(); }
    public void setSelected(boolean selected) { this.selected.set(selected); }

    public String getName() { return name; }
    public String getPublisher() { return publisher; }
    public String getVersion() { return version; }
    public String getInstallLocation() { return installLocation; }
    public String getUninstallString() { return uninstallString; }
    public String getRegistryKeyPath() { return registryKeyPath; }
    public boolean isWin32() { return win32; }
    public String getAppxPackageFullName() { return appxPackageFullName; }
    public String getRegistryHive() { return registryHive; }
    public String getInstallDate() { return installDate; }
    public int getEstimatedSize() { return estimatedSize; }
    public String getArchitecture() { return architecture; }

    @Override
    public String toString() {
        return name + " (" + (win32 ? "Desktop App" : "Windows Store App") + ")";
    }
}
