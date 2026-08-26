package com.sbtools.uninstaller;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinReg.HKEY;
import com.sbtools.util.AppLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

public class Win32AppDiscoverer {

    private static final String UNINSTALL_PATH = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall";
    private static final String WOW6432_UNINSTALL_PATH = "SOFTWARE\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall";

    public List<InstalledApp> discoverApps() {
        List<InstalledApp> apps = new ArrayList<>();

        scanRegistryPath(WinReg.HKEY_LOCAL_MACHINE, UNINSTALL_PATH, "HKLM", "64-bit", apps);
        scanRegistryPath(WinReg.HKEY_LOCAL_MACHINE, WOW6432_UNINSTALL_PATH, "HKLM", "32-bit", apps);
        scanRegistryPath(WinReg.HKEY_CURRENT_USER, UNINSTALL_PATH, "HKCU", "Per-User", apps);

        List<InstalledApp> deduped = new ArrayList<>();
        TreeSet<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        for (InstalledApp app : apps) {
            String uniqueKey = app.getName() + "||" + app.getVersion() + "||" + app.getPublisher()
                    + "||" + app.getRegistryHive() + "||" + app.getRegistryKeyPath();
            if (!seen.contains(uniqueKey)) {
                seen.add(uniqueKey);
                deduped.add(app);
            }
        }

        deduped.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return deduped;
    }

    private void scanRegistryPath(HKEY hive, String parentPath, String hiveLabel, String archLabel, List<InstalledApp> apps) {
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
                        String quietUninstallString = getStringValue(hive, fullPath, "QuietUninstallString");
                        String uninstallString = getStringValue(hive, fullPath, "UninstallString");
                        // Prefer QuietUninstallString when available — allows silent uninstall without
                        // hanging on interactive UI. This is the safe option; we never invent silent
                        // flags for custom uninstallers, we only use the vendor-provided quiet command.
                        String effectiveUninstall = !quietUninstallString.isBlank() ? quietUninstallString : uninstallString;
                        String installDate = getStringValue(hive, fullPath, "InstallDate");
                        int estimatedSize = getIntValue(hive, fullPath, "EstimatedSize", 0);

                        apps.add(new InstalledApp(
                                name, publisher, version, installLocation,
                                effectiveUninstall, fullPath, true,
                                "", hiveLabel, installDate, estimatedSize, archLabel
                        ));
                    }
                } catch (Exception e) {
                    AppLogger.debug("Skipping subkey registry read: " + subkeyName + " - " + e.getMessage());
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to scan registry path " + parentPath + " in hive " + hiveLabel + ": " + e.getMessage());
        }
    }

    private boolean isRealApp(HKEY hive, String keyPath) {
        String displayName = getStringValue(hive, keyPath, "DisplayName");
        int systemComponent = getIntValue(hive, keyPath, "SystemComponent", 0);
        String parentKeyName = getStringValue(hive, keyPath, "ParentKeyName");
        String releaseType = getStringValue(hive, keyPath, "ReleaseType");
        String publisher = getStringValue(hive, keyPath, "Publisher");

        if (displayName.isEmpty()) {
            return false;
        }
        if (systemComponent == 1) {
            return false;
        }
        if (!parentKeyName.isEmpty()) {
            return false;
        }
        // Trim releaseType whitespace variants (e.g., "Security Update ")
        String rt = releaseType != null ? releaseType.trim() : "";
        if ("Security Update".equalsIgnoreCase(rt) || "Update".equalsIgnoreCase(rt) || "Hotfix".equalsIgnoreCase(rt)) {
            return false;
        }
        // KB updates now use 7 digits (e.g., KB5034441) — accept 6+ digits, also check case-insensitive with word boundary
        if (displayName.matches("(?i).*KB\\d{6,}.*")) {
            return false;
        }
        if (isMicrosoftOrWindows(publisher, displayName)) {
            return false;
        }
        return true;
    }

    private boolean isMicrosoftOrWindows(String publisher, String displayName) {
        return AppCompatUtils.isMicrosoftOrWindows(publisher, displayName);
    }

    private static String getStringValue(HKEY hive, String keyPath, String valueName) {
        try {
            if (Advapi32Util.registryValueExists(hive, keyPath, valueName)) {
                return Advapi32Util.registryGetStringValue(hive, keyPath, valueName);
            }
        } catch (Exception e) {
        }
        return "";
    }

    private static int getIntValue(HKEY hive, String keyPath, String valueName, int defaultValue) {
        try {
            if (Advapi32Util.registryValueExists(hive, keyPath, valueName)) {
                return Advapi32Util.registryGetIntValue(hive, keyPath, valueName);
            }
        } catch (Exception e) {
        }
        return defaultValue;
    }
}
