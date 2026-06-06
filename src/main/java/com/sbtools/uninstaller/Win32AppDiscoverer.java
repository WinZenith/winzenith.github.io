package com.sbtools.uninstaller;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinReg.HKEY;
import com.sbtools.util.AppLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

public class Win32AppDiscoverer {

    private static final String UNINSTALL_PATH = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall";
    private static final String WOW6432_UNINSTALL_PATH = "SOFTWARE\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall";

    public List<InstalledApp> discoverApps() {
        List<InstalledApp> apps = new ArrayList<>();

        // 1. Scan HKLM (64-bit apps on 64-bit OS)
        scanRegistryPath(WinReg.HKEY_LOCAL_MACHINE, UNINSTALL_PATH, "HKLM", apps);

        // 2. Scan HKLM Wow6432Node (32-bit apps on 64-bit OS)
        scanRegistryPath(WinReg.HKEY_LOCAL_MACHINE, WOW6432_UNINSTALL_PATH, "HKLM", apps);

        // 3. Scan HKCU (Per-user apps)
        scanRegistryPath(WinReg.HKEY_CURRENT_USER, UNINSTALL_PATH, "HKCU", apps);

        // Deduplicate apps by name and version or uninstall string to avoid double entries
        // from some programs listing themselves in multiple registries.
        List<InstalledApp> deduped = new ArrayList<>();
        TreeSet<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        for (InstalledApp app : apps) {
            // Create a unique key
            String uniqueKey = app.getName() + "||" + app.getVersion();
            if (!seen.contains(uniqueKey)) {
                seen.add(uniqueKey);
                deduped.add(app);
            }
        }

        // Sort by name alphabetically
        deduped.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return deduped;
    }

    private void scanRegistryPath(HKEY hive, String parentPath, String hiveLabel, List<InstalledApp> apps) {
        try {
            if (!Advapi32Util.registryKeyExists(hive, parentPath)) {
                return;
            }
            String[] subkeys = Advapi32Util.registryGetKeys(hive, parentPath);
            if (subkeys == null) {
                return;
            }

            for (String subkeyName : subkeys) {
                String fullPath = parentPath + "\\" + subkeyName;
                try {
                    if (isRealApp(hive, fullPath)) {
                        String name = getStringValue(hive, fullPath, "DisplayName");
                        String publisher = getStringValue(hive, fullPath, "Publisher");
                        String version = getStringValue(hive, fullPath, "DisplayVersion");
                        String installLocation = getStringValue(hive, fullPath, "InstallLocation");
                        String uninstallString = getStringValue(hive, fullPath, "UninstallString");

                        // Add the app
                        apps.add(new InstalledApp(
                                name,
                                publisher,
                                version,
                                installLocation,
                                uninstallString,
                                fullPath,
                                true, // isWin32
                                "",   // no appx full name
                                hiveLabel
                        ));
                    }
                } catch (Exception e) {
                    // Ignore errors reading a specific subkey (e.g. access denied to specific key)
                    AppLogger.debug("Skipping subkey registry read: " + subkeyName + " - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to scan registry path " + parentPath + " in hive " + hiveLabel + ": " + e.getMessage());
        }
    }

    private boolean isRealApp(HKEY hive, String keyPath) {
        String displayName = getStringValue(hive, keyPath, "DisplayName");
        String uninstallString = getStringValue(hive, keyPath, "UninstallString");
        int systemComponent = getIntValue(hive, keyPath, "SystemComponent", 0);
        String parentKeyName = getStringValue(hive, keyPath, "ParentKeyName");
        String releaseType = getStringValue(hive, keyPath, "ReleaseType");

        if (displayName.isEmpty() || uninstallString.isEmpty()) {
            return false;
        }
        if (systemComponent == 1) {
            return false;
        }
        if (!parentKeyName.isEmpty()) {
            return false;
        }
        if ("Security Update".equalsIgnoreCase(releaseType) || "Update".equalsIgnoreCase(releaseType) || "Hotfix".equalsIgnoreCase(releaseType)) {
            return false;
        }
        // Exclude system patches / updates e.g. KBxxxxxx
        if (displayName.matches("(?i).*KB\\d{6}.*")) {
            return false;
        }
        return true;
    }

    private static String getStringValue(HKEY hive, String keyPath, String valueName) {
        try {
            if (Advapi32Util.registryValueExists(hive, keyPath, valueName)) {
                return Advapi32Util.registryGetStringValue(hive, keyPath, valueName);
            }
        } catch (Exception e) {
            // Ignore value-specific read issues
        }
        return "";
    }

    private static int getIntValue(HKEY hive, String keyPath, String valueName, int defaultValue) {
        try {
            if (Advapi32Util.registryValueExists(hive, keyPath, valueName)) {
                return Advapi32Util.registryGetIntValue(hive, keyPath, valueName);
            }
        } catch (Exception e) {
            // Ignore
        }
        return defaultValue;
    }
}
