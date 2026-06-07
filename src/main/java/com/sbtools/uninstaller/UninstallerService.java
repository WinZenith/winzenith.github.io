package com.sbtools.uninstaller;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbtools.util.AppLogger;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinReg.HKEY;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class UninstallerService {

    private final Win32AppDiscoverer win32Discoverer = new Win32AppDiscoverer();
    private final ProcessRunner processRunner = new ProcessRunner(1800); // 30-minute timeout for uninstallers

    public List<InstalledApp> listWin32Apps() {
        return win32Discoverer.discoverApps();
    }

    public List<InstalledApp> listAppxApps() {
        List<InstalledApp> apps = new ArrayList<>();
        try {
            Path script = PowerShellScripts.resolve("appx-list.ps1");
            ProcessResult result = processRunner.run(ProcessRunner.powershellScript(script.toString()));
            if (result.success()) {
                String json = result.stdout();
                if (json != null && !json.isBlank()) {
                    JsonNode rootNode = JsonMapper.parseTree(json);
                    if (rootNode.isArray()) {
                        for (JsonNode node : rootNode) {
                            apps.add(parseAppxNode(node));
                        }
                    } else if (rootNode.isObject()) {
                        apps.add(parseAppxNode(rootNode));
                    }
                }
            } else {
                AppLogger.warning("Appx package scan failed: " + result.combinedOutput());
            }
        } catch (Exception e) {
            AppLogger.error("Failed to list Appx packages", e);
        }

        // Sort alphabetically
        apps.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return apps;
    }

    private InstalledApp parseAppxNode(JsonNode node) {
        String name = node.path("Name").asText("");
        String packageFullName = node.path("PackageFullName").asText("");
        String version = node.path("Version").asText("");
        String publisher = node.path("Publisher").asText("");
        String installLocation = node.path("InstallLocation").asText("");
        String installDate = node.path("InstallDate").asText("");
        int installedSize = node.path("InstalledSize").asInt(0);

        return new InstalledApp(
                name, publisher, version, installLocation,
                "", "", false, packageFullName, "",
                installDate, installedSize, "Store"
        );
    }

    /**
     * Triggers the uninstaller and monitors it until it completes.
     */
    public ProcessResult runUninstaller(InstalledApp app) throws IOException, InterruptedException {
        if (!app.isWin32()) {
            // Execute Appx removal
            Path script = PowerShellScripts.resolve("appx-uninstall.ps1");
            return processRunner.run(ProcessRunner.powershellScript(script.toString(), "-PackageFullName", app.getAppxPackageFullName()));
        } else {
            // Execute Win32 UninstallString via cmd /c
            String uninstallCmd = app.getUninstallString();
            List<String> command = List.of("cmd.exe", "/c", uninstallCmd);
            return processRunner.run(command);
        }
    }

    /**
     * Scans filesystem (%ProgramFiles%, %ProgramFiles(x86)%, %AppData%, %LocalAppData%, %ProgramData%)
     * for remnants matching the application name, publisher or install location.
     */
    public List<String> scanFilesystemLeftovers(InstalledApp app) {
        List<String> leftovers = new ArrayList<>();

        // Add primary install location if it exists
        if (app.getInstallLocation() != null && !app.getInstallLocation().isBlank()) {
            File installDir = new File(app.getInstallLocation());
            if (installDir.exists()) {
                leftovers.add(installDir.getAbsolutePath());
            }
        }

        List<String> roots = new ArrayList<>();
        addIfNotNull(roots, System.getenv("ProgramFiles"));
        addIfNotNull(roots, System.getenv("ProgramFiles(x86)"));
        addIfNotNull(roots, System.getenv("AppData"));
        addIfNotNull(roots, System.getenv("LocalAppData"));
        addIfNotNull(roots, System.getenv("ProgramData"));

        for (String root : roots) {
            File rootDir = new File(root);
            if (!rootDir.exists() || !rootDir.isDirectory()) {
                continue;
            }

            File[] children = rootDir.listFiles();
            if (children == null) {
                continue;
            }

            for (File child : children) {
                if (child.isDirectory()) {
                    if (isFolderMatch(child.getName(), app.getName(), app.getPublisher())) {
                        String absPath = child.getAbsolutePath();
                        if (!leftovers.contains(absPath)) {
                            leftovers.add(absPath);
                        }
                    }
                }
            }
        }

        return leftovers;
    }

    /**
     * Scans Registry SOFTWARE keys (HKLM, HKLM-Wow6432, HKCU) for remnants.
     */
    public List<String> scanRegistryLeftovers(InstalledApp app) {
        List<String> leftovers = new ArrayList<>();

        // Add the primary uninstaller registry key itself if it exists (for Win32 apps)
        if (app.isWin32() && !app.getRegistryKeyPath().isEmpty()) {
            HKEY hive = "HKLM".equalsIgnoreCase(app.getRegistryHive()) ? WinReg.HKEY_LOCAL_MACHINE : WinReg.HKEY_CURRENT_USER;
            try {
                if (Advapi32Util.registryKeyExists(hive, app.getRegistryKeyPath())) {
                    leftovers.add(app.getRegistryHive() + "\\" + app.getRegistryKeyPath());
                }
            } catch (Exception ignored) {}
        }

        // Search in Software paths
        scanRegistryForLeftovers(WinReg.HKEY_LOCAL_MACHINE, "HKLM", "SOFTWARE", app.getName(), app.getPublisher(), leftovers);
        scanRegistryForLeftovers(WinReg.HKEY_LOCAL_MACHINE, "HKLM", "SOFTWARE\\Wow6432Node", app.getName(), app.getPublisher(), leftovers);
        scanRegistryForLeftovers(WinReg.HKEY_CURRENT_USER, "HKCU", "SOFTWARE", app.getName(), app.getPublisher(), leftovers);

        return leftovers;
    }

    private void scanRegistryForLeftovers(HKEY hive, String hiveLabel, String rootPath, String appName, String publisher, List<String> leftovers) {
        try {
            if (!Advapi32Util.registryKeyExists(hive, rootPath)) {
                return;
            }
            String[] subkeys = Advapi32Util.registryGetKeys(hive, rootPath);
            if (subkeys == null) {
                return;
            }

            for (String subkey : subkeys) {
                String fullPath = rootPath + "\\" + subkey;

                // Check if the subkey matches publisher name or app name
                if (isRegistryKeyMatch(subkey, appName, publisher)) {
                    String formattedPath = hiveLabel + "\\" + fullPath;
                    if (!leftovers.contains(formattedPath)) {
                        leftovers.add(formattedPath);
                    }
                } else {
                    // Check if publisher folder (e.g. SOFTWARE\PublisherName) and check inside it
                    if (isPublisherMatch(subkey, publisher)) {
                        try {
                            String[] innerKeys = Advapi32Util.registryGetKeys(hive, fullPath);
                            if (innerKeys != null) {
                                for (String innerKey : innerKeys) {
                                    if (isRegistryKeyMatch(innerKey, appName, null)) {
                                        String formattedPath = hiveLabel + "\\" + fullPath + "\\" + innerKey;
                                        if (!leftovers.contains(formattedPath)) {
                                            leftovers.add(formattedPath);
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.debug("Skipping registry scan branch: " + rootPath + " - " + e.getMessage());
        }
    }

    private void addIfNotNull(List<String> list, String value) {
        if (value != null && !value.isBlank()) {
            list.add(value);
        }
    }

    private boolean isFolderMatch(String folderName, String appName, String publisher) {
        if (folderName == null || folderName.isBlank()) return false;
        String fName = folderName.toLowerCase();
        String aName = appName != null ? appName.toLowerCase().trim() : "";
        String pName = publisher != null ? publisher.toLowerCase().trim() : "";

        if (aName.isEmpty()) return false;

        // Exact match
        if (fName.equals(aName) || fName.equals(pName)) {
            return true;
        }

        // Substring match for App Name
        if (aName.length() >= 4 && (fName.contains(aName) || aName.contains(fName))) {
            if (!isGenericName(fName)) {
                return true;
            }
        }

        // Substring match for Publisher
        if (pName.length() >= 4 && (fName.contains(pName) || pName.contains(fName))) {
            if (!isGenericName(fName)) {
                return true;
            }
        }

        return false;
    }

    private boolean isRegistryKeyMatch(String keyName, String appName, String publisher) {
        if (keyName == null || keyName.isBlank()) return false;
        String kName = keyName.toLowerCase();
        String aName = appName != null ? appName.toLowerCase().trim() : "";
        String pName = publisher != null ? publisher.toLowerCase().trim() : "";

        if (aName.isEmpty()) return false;

        // Exact matches
        if (kName.equals(aName) || (!pName.isEmpty() && kName.equals(pName))) {
            return true;
        }

        // Substring match
        if (aName.length() >= 4 && (kName.contains(aName) || aName.contains(kName))) {
            return !isGenericName(kName);
        }

        return false;
    }

    private boolean isPublisherMatch(String keyName, String publisher) {
        if (keyName == null || keyName.isBlank() || publisher == null || publisher.isBlank()) return false;
        String kName = keyName.toLowerCase();
        String pName = publisher.toLowerCase().trim();

        if (pName.length() < 3) return false;
        return kName.equals(pName) || kName.contains(pName) || pName.contains(kName);
    }

    private boolean isGenericName(String name) {
        String[] generic = {
                "software", "program", "app", "application", "microsoft", "windows",
                "common", "temp", "local", "roaming", "data", "uninstall", "utilities",
                "tools", "drivers", "updates"
        };
        for (String gen : generic) {
            if (name.equals(gen)) return true;
        }
        return false;
    }

    /**
     * Deletes a list of files or folders and logs failures (which are scheduled for reboot deletion).
     *
     * @param paths List of absolute folder or file paths.
     * @param failedDeletions Output list to append paths that could not be deleted immediately (e.g. locked).
     */
    public void deleteFilesystemLeftovers(List<String> paths, List<String> failedDeletions) {
        for (String pathStr : paths) {
            File file = new File(pathStr);
            if (file.exists()) {
                boolean success = NativeFileHelper.deleteOrQueue(file);
                if (!success) {
                    failedDeletions.add(pathStr);
                }
            }
        }
    }

    /**
     * Deletes a list of registry keys recursively.
     * Paths must be formatted as "HKLM\SOFTWARE\..." or "HKCU\SOFTWARE\...".
     *
     * @param registryPaths List of full registry paths.
     */
    public void deleteRegistryLeftovers(List<String> registryPaths) {
        for (String fullPath : registryPaths) {
            int separatorIdx = fullPath.indexOf('\\');
            if (separatorIdx == -1) continue;

            String hiveStr = fullPath.substring(0, separatorIdx);
            String subKeyPath = fullPath.substring(separatorIdx + 1);

            HKEY hive = "HKLM".equalsIgnoreCase(hiveStr) ? WinReg.HKEY_LOCAL_MACHINE : WinReg.HKEY_CURRENT_USER;
            deleteRegistryKeyRecursively(hive, subKeyPath);
        }
    }

    private void deleteRegistryKeyRecursively(HKEY hive, String keyPath) {
        try {
            if (!Advapi32Util.registryKeyExists(hive, keyPath)) {
                return;
            }
            String[] subkeys = Advapi32Util.registryGetKeys(hive, keyPath);
            if (subkeys != null) {
                for (String subkey : subkeys) {
                    deleteRegistryKeyRecursively(hive, keyPath + "\\" + subkey);
                }
            }
            Advapi32Util.registryDeleteKey(hive, keyPath);
            AppLogger.info("Deleted registry leftover key: " + keyPath);
        } catch (Exception e) {
            AppLogger.warning("Failed to delete registry key: " + keyPath + " - " + e.getMessage());
        }
    }
}
