package com.sbtools.uninstaller;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbtools.util.AppLogger;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessManager;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinReg.HKEY;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
                            InstalledApp app = parseAppxNode(node);
                            if (!isMicrosoftOrWindows(app)) {
                                apps.add(app);
                            }
                        }
                    } else if (rootNode.isObject()) {
                        InstalledApp app = parseAppxNode(rootNode);
                        if (!isMicrosoftOrWindows(app)) {
                            apps.add(app);
                        }
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

    private boolean isMicrosoftOrWindows(InstalledApp app) {
        return AppCompatUtils.isMicrosoftOrWindows(app);
    }

    /**
     * Triggers the uninstaller and monitors it until it completes.
     */
    public ProcessResult runUninstaller(InstalledApp app) throws IOException, InterruptedException {
        if (!app.isWin32()) {
            Path script = PowerShellScripts.resolve("appx-uninstall.ps1");
            return processRunner.run(ProcessRunner.powershellScript(script.toString(), "-PackageFullName", app.getAppxPackageFullName()));
        } else {
            String uninstallCmd = app.getUninstallString();
            if (uninstallCmd == null || uninstallCmd.isBlank()) {
                throw new IOException("No uninstall command available for " + app.getName());
            }
            List<String> command = parseUninstallCommand(uninstallCmd);
            return processRunner.run(command);
        }
    }

    /**
     * Runs the uninstaller and waits for all related child processes to exit.
     * This ensures file locks are released before scanning for leftovers.
     */
    public ProcessResult runUninstallerAndWait(InstalledApp app, long timeoutSeconds) throws IOException, InterruptedException {
        if (!app.isWin32()) {
            Path script = PowerShellScripts.resolve("appx-uninstall.ps1");
            return processRunner.run(ProcessRunner.powershellScript(script.toString(), "-PackageFullName", app.getAppxPackageFullName()), timeoutSeconds);
        } else {
            String uninstallCmd = app.getUninstallString();
            if (uninstallCmd == null || uninstallCmd.isBlank()) {
                throw new IOException("No uninstall command available for " + app.getName());
            }
            List<String> command = parseUninstallCommand(uninstallCmd);

            // Start the process
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            AppLogger.info("Running uninstaller: " + String.join(" ", command));
            Process process = pb.start();
            try {
                ProcessManager.register(process);
            } catch (Throwable ignored) {}

            // Wait for the main process to complete
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Uninstaller timed out after " + timeoutSeconds + "s");
            }

            int exitCode = process.exitValue();

            // Collect stdout/stderr (drain streams in background)
            Thread drainStdout = new Thread(() -> {
                try { process.getInputStream().readAllBytes(); } catch (Exception ignored) {}
            }, "uninstaller-stdout-drain");
            drainStdout.setDaemon(true);
            drainStdout.start();
            Thread drainStderr = new Thread(() -> {
                try { process.getErrorStream().readAllBytes(); } catch (Exception ignored) {}
            }, "uninstaller-stderr-drain");
            drainStderr.setDaemon(true);
            drainStderr.start();

            // Wait for all descendant processes to exit (catches msiexec and other spawned uninstaller processes)
            try {
                List<ProcessHandle> descendants = process.descendants().toList();
                AppLogger.info("Waiting for " + descendants.size() + " descendant process(es) to exit");
                long descendantDeadline = System.currentTimeMillis() + (30_000);
                for (ProcessHandle child : descendants) {
                    long remaining = descendantDeadline - System.currentTimeMillis();
                    if (remaining <= 0) break;
                    try {
                        child.onExit().get(remaining, TimeUnit.MILLISECONDS);
                    } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                AppLogger.debug("Failed to wait for descendants: " + e.getMessage());
            }

            // Additionally wait for child processes matching app name/path (fallback)
            waitForChildProcesses(app, 30);

            // Additionally wait for the install directory to be removed,
            // as some uninstallers perform cleanup after child processes exit.
            waitForInstallDirRemoval(app, 30);

            return new ProcessResult(exitCode, "", "");
        }
    }

    /**
     * Waits for any child processes related to the app to exit.
     * Checks periodically for processes whose command line matches the app's install location.
     */
    private void waitForChildProcesses(InstalledApp app, int maxWaitSeconds) {
        String installLoc = app.getInstallLocation();
        String appName = app.getName().toLowerCase();
        long deadline = System.currentTimeMillis() + (maxWaitSeconds * 1000L);

        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean found = new AtomicBoolean(false);
            try {
                ProcessHandle.allProcesses().forEach(ph -> {
                    if (found.get()) return;
                    if (!ph.isAlive()) return;
                    ProcessHandle.Info info = ph.info();
                    String cmdLine = info.commandLine().orElse("").toLowerCase();
                    String execPath = info.command().map(String::toLowerCase).orElse("");

                    boolean matchByPath = installLoc != null && !installLoc.isBlank()
                            && (cmdLine.contains(installLoc.toLowerCase()) || execPath.contains(installLoc.toLowerCase()));
                    boolean matchByName = appName.length() >= 4 && (cmdLine.contains(appName) || execPath.contains(appName));

                    if (matchByPath || matchByName) {
                        found.set(true);
                    }
                });
            } catch (Exception ignored) {}

            if (!found.get()) break;

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Waits until the install directory is removed from disk.
     * This handles uninstallers that spawn a final cleanup process
     * which deletes the install directory and its contents after
     * the main uninstaller process has exited.
     */
    private void waitForInstallDirRemoval(InstalledApp app, int maxWaitSeconds) {
        String installLoc = app.getInstallLocation();
        if (installLoc == null || installLoc.isBlank()) return;

        File installDir = new File(installLoc);
        if (!installDir.exists()) return;

        AppLogger.info("Waiting for install directory to be removed: " + installLoc);
        long deadline = System.currentTimeMillis() + (maxWaitSeconds * 1000L);

        while (System.currentTimeMillis() < deadline && installDir.exists()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (installDir.exists()) {
            AppLogger.warning("Install directory still exists after waiting: " + installLoc);
        } else {
            AppLogger.info("Install directory successfully removed: " + installLoc);
        }
    }

    private List<String> parseUninstallCommand(String uninstallCmd) {
        String trimmed = uninstallCmd.trim();
        String lower = trimmed.toLowerCase();

        if (lower.startsWith("msiexec")) {
            return List.of("cmd.exe", "/c", trimmed);
        }

        List<String> tokens = splitCommandLine(trimmed);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Empty uninstall command: " + uninstallCmd);
        }
        return tokens;
    }

    /**
     * Splits a command line string into tokens, respecting quoted segments.
     * For example: a quoted path with args gets properly separated.
     */
    private static List<String> splitCommandLine(String commandLine) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;

        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ' ' && !inQuote) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    /**
     * Scans filesystem (%ProgramFiles%, %ProgramFiles(x86)%, %AppData%, %LocalAppData%, %ProgramData%)
     * for remnants matching the application name, publisher or install location.
     * Also scans additional locations: LocalAppData\Programs, AppData\LocalLow,
     * Public\Documents, Desktop, Quick Launch, and environment PATH entries.
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
        addIfNotNull(roots, System.getenv("CommonProgramFiles"));
        addIfNotNull(roots, System.getenv("CommonProgramFiles(x86)"));
        addIfNotNull(roots, System.getenv("AppData"));
        addIfNotNull(roots, System.getenv("LocalAppData"));
        addIfNotNull(roots, System.getenv("ProgramData"));

        // Additional scan locations
        String localAppData = System.getenv("LocalAppData");
        String appData = System.getenv("AppData");
        String userProfile = System.getenv("USERPROFILE");
        String publicDir = System.getenv("PUBLIC");
        if (localAppData != null) addIfNotNull(roots, localAppData + "\\Programs");
        if (appData != null) addIfNotNull(roots, appData + "\\LocalLow");
        if (publicDir != null) addIfNotNull(roots, publicDir + "\\Documents");
        if (userProfile != null) addIfNotNull(roots, userProfile + "\\Desktop");
        if (appData != null) addIfNotNull(roots, appData + "\\Microsoft\\Internet Explorer\\Quick Launch");

        // Scan depth: 1 for standard roots, 2 for vendor directories
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
                    } else if (isPublisherMatch(child.getName(), app.getPublisher())) {
                        // Deeper scan: if this is a vendor folder, scan inside it
                        File[] vendorChildren = child.listFiles(File::isDirectory);
                        if (vendorChildren != null) {
                            for (File vendorChild : vendorChildren) {
                                if (isFolderMatch(vendorChild.getName(), app.getName(), null)) {
                                    String absPath = vendorChild.getAbsolutePath();
                                    if (!leftovers.contains(absPath)) {
                                        leftovers.add(absPath);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Check PATH entries for app install location
        if (app.getInstallLocation() != null && !app.getInstallLocation().isBlank()) {
            checkPathEntriesForLeftover(app.getInstallLocation(), leftovers);
        }

        return leftovers;
    }

    /**
     * Checks if the app's install location is referenced in system or user PATH.
     */
    private void checkPathEntriesForLeftover(String installLocation, List<String> leftovers) {
        try {
            String lowerLoc = installLocation.toLowerCase().trim();
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                for (String entry : pathEnv.split(";")) {
                    String trimmed = entry.trim();
                    if (!trimmed.isEmpty() && trimmed.toLowerCase().startsWith(lowerLoc)) {
                        String warning = "PATH entry references app: " + trimmed;
                        if (!leftovers.contains(warning)) {
                            leftovers.add(warning);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    /**
     * Scans Registry SOFTWARE keys (HKLM, HKLM-Wow6432, HKCU) and HKCR for remnants.
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
        scanRegistryForLeftovers(WinReg.HKEY_LOCAL_MACHINE, "HKLM", "SYSTEM\\CurrentControlSet\\Services", app.getName(), app.getPublisher(), leftovers);

        // Scan HKCR for file association entries
        scanHkcrForLeftovers(app.getName(), app.getPublisher(), leftovers);

        return leftovers;
    }

    /**
     * Scans HKEY_CLASSES_ROOT for file association entries matching the app name or publisher.
     */
    private void scanHkcrForLeftovers(String appName, String publisher, List<String> leftovers) {
        try {
            if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT, "")) {
                return;
            }
            String[] subkeys = Advapi32Util.registryGetKeys(WinReg.HKEY_CLASSES_ROOT, "");
            if (subkeys == null) return;

            String lowerName = appName != null ? appName.toLowerCase().trim() : "";
            String lowerPub = publisher != null ? publisher.toLowerCase().trim() : "";
            if (lowerName.length() < 4 && lowerPub.length() < 4) return;

            for (String subkey : subkeys) {
                // Skip very long keys (COM CLSIDs, etc.) and generic entries
                if (subkey.length() > 80 || subkey.startsWith("CLSID\\") || subkey.startsWith("Wow6432Node\\")) {
                    continue;
                }
                String lowerKey = subkey.toLowerCase();
                boolean nameMatch = lowerName.length() >= 4 && (lowerKey.equals(lowerName) || lowerKey.contains(lowerName));
                boolean pubMatch = lowerPub.length() >= 4 && (lowerKey.equals(lowerPub) || lowerKey.contains(lowerPub));
                if (nameMatch || pubMatch) {
                    String path = "HKCR\\" + subkey;
                    if (!leftovers.contains(path)) {
                        leftovers.add(path);
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.debug("HKCR scan failed: " + e.getMessage());
        }
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
            AppLogger.error("Skipping registry scan branch: " + rootPath + " - " + e.getMessage());
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
        if (aName.length() >= 4 && fName.contains(aName)) {
            if (!isGenericName(fName)) {
                return true;
            }
        }

        // Substring match for Publisher
        if (pName.length() >= 4 && fName.contains(pName)) {
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

        // Substring match (key name contains app name)
        if (aName.length() >= 4 && kName.contains(aName)) {
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
                "tools", "drivers", "updates", "config", "cache", "logs", "packages",
                "resources", "share", "lib", "bin", "src", "include", "plugins",
                "extensions", "modules", "system", "services", "startup"
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
     * @param failedDeletions Output list to append paths that could not be deleted.
     */
    public void deleteRegistryLeftovers(List<String> registryPaths, List<String> failedDeletions) {
        for (String fullPath : registryPaths) {
            int separatorIdx = fullPath.indexOf('\\');
            if (separatorIdx == -1) continue;

            String hiveStr = fullPath.substring(0, separatorIdx);
            String subKeyPath = fullPath.substring(separatorIdx + 1);

            HKEY hive = "HKLM".equalsIgnoreCase(hiveStr) ? WinReg.HKEY_LOCAL_MACHINE : WinReg.HKEY_CURRENT_USER;
            if (!deleteRegistryKeyRecursively(hive, subKeyPath)) {
                failedDeletions.add(fullPath);
            }
        }
    }

    private boolean deleteRegistryKeyRecursively(HKEY hive, String keyPath) {
        try {
            if (!Advapi32Util.registryKeyExists(hive, keyPath)) {
                return true;
            }
            String[] subkeys = Advapi32Util.registryGetKeys(hive, keyPath);
            if (subkeys != null) {
                for (String subkey : subkeys) {
                    deleteRegistryKeyRecursively(hive, keyPath + "\\" + subkey);
                }
            }
            Advapi32Util.registryDeleteKey(hive, keyPath);
            AppLogger.info("Deleted registry leftover key: " + keyPath);
            return true;
        } catch (Exception e) {
            AppLogger.error("Failed to delete registry key: " + keyPath + " - " + e.getMessage());
            return false;
        }
    }

    public record ForceUninstallResult(List<String> summary, List<String> errors) {}

    /**
     * Forcefully removes an application by killing its processes, deleting install directories,
     * removing registry entries, and cleaning Start Menu shortcuts — without running the standard uninstaller.
     */
    public ForceUninstallResult forceUninstall(InstalledApp app) {
        List<String> summary = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String appName = app.getName();
        String lowerAppName = appName.toLowerCase();

        String installLoc = app.getInstallLocation();

        // Kill processes whose executable path matches the install location
        killProcessesByPath(installLoc, appName, summary, errors);

        // Delete the install directory
        if (installLoc != null && !installLoc.isBlank()) {
            File dir = new File(installLoc);
            if (dir.exists()) {
                if (NativeFileHelper.deleteOrQueue(dir)) {
                    summary.add("Deleted directory: " + installLoc);
                } else {
                    errors.add("Scheduled for reboot deletion: " + installLoc);
                }
            }
        }

        // If installLocation is empty, search common directories
        if (installLoc == null || installLoc.isBlank()) {
            List<String> roots = new ArrayList<>();
            addIfNotNull(roots, System.getenv("ProgramFiles"));
            addIfNotNull(roots, System.getenv("ProgramFiles(x86)"));
            addIfNotNull(roots, System.getenv("CommonProgramFiles"));
            addIfNotNull(roots, System.getenv("CommonProgramFiles(x86)"));
            addIfNotNull(roots, System.getenv("AppData"));
            addIfNotNull(roots, System.getenv("LocalAppData"));
            addIfNotNull(roots, System.getenv("ProgramData"));

            for (String root : roots) {
                File rootDir = new File(root);
                if (!rootDir.exists() || !rootDir.isDirectory()) continue;
                File[] children = rootDir.listFiles(File::isDirectory);
                if (children == null) continue;
                for (File child : children) {
                    String childName = child.getName().toLowerCase();
                    String publisherLower = app.getPublisher() != null ? app.getPublisher().toLowerCase().trim() : "";
                    boolean nameMatch = childName.equals(lowerAppName)
                            || childName.startsWith(lowerAppName)
                            || (lowerAppName.length() >= 4 && childName.contains(lowerAppName));
                    boolean publisherMatch = !publisherLower.isEmpty() && publisherLower.length() >= 4
                            && (childName.equals(publisherLower) || childName.startsWith(publisherLower));
                    if (nameMatch || publisherMatch) {
                        if (NativeFileHelper.deleteOrQueue(child)) {
                            summary.add("Deleted directory: " + child.getAbsolutePath());
                        } else {
                            errors.add("Scheduled for reboot deletion: " + child.getAbsolutePath());
                        }
                    }
                }
            }
        }

        // Delete the registry key at the app's registryKeyPath
        if (app.isWin32() && !app.getRegistryKeyPath().isEmpty()) {
            HKEY hive = "HKLM".equalsIgnoreCase(app.getRegistryHive())
                    ? WinReg.HKEY_LOCAL_MACHINE : WinReg.HKEY_CURRENT_USER;
            try {
                if (Advapi32Util.registryKeyExists(hive, app.getRegistryKeyPath())) {
                    try {
                        Advapi32Util.registryDeleteKey(hive, app.getRegistryKeyPath());
                    } catch (Exception ex) {
                        if (!deleteRegistryKeyRecursively(hive, app.getRegistryKeyPath())) {
                            errors.add("Failed to delete registry key for " + appName + ": " + app.getRegistryKeyPath());
                        }
                    }
                    summary.add("Deleted registry key: " + app.getRegistryHive() + "\\" + app.getRegistryKeyPath());
                }
            } catch (Exception e) {
                errors.add("Failed to delete registry key for " + appName + ": " + e.getMessage());
            }
        }

        // Search and delete registry keys under Uninstall paths that contain the app name
        String[][] uninstallPaths = {
                {"HKLM", "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"},
                {"HKLM", "SOFTWARE\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall"},
                {"HKCU", "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"}
        };

        for (String[] pathInfo : uninstallPaths) {
            String hiveLabel = pathInfo[0];
            String keyPath = pathInfo[1];
            HKEY hive = "HKLM".equals(hiveLabel) ? WinReg.HKEY_LOCAL_MACHINE : WinReg.HKEY_CURRENT_USER;

            try {
                if (!Advapi32Util.registryKeyExists(hive, keyPath)) continue;
                String[] subkeys = Advapi32Util.registryGetKeys(hive, keyPath);
                if (subkeys == null) continue;

                for (String subkey : subkeys) {
                    String fullSubKey = keyPath + "\\" + subkey;
                    boolean shouldDelete = false;

                    // First: exact match against the app's known registry key path
                    if (app.isWin32() && app.getRegistryKeyPath().equals(fullSubKey)) {
                        shouldDelete = true;
                    }

                    // Second: read DisplayName from the subkey and match precisely
                    if (!shouldDelete) {
                        try {
                            if (Advapi32Util.registryValueExists(hive, fullSubKey, "DisplayName")) {
                                String displayName = Advapi32Util.registryGetStringValue(hive, fullSubKey, "DisplayName");
                                if (displayName != null && displayName.equalsIgnoreCase(appName)) {
                                    shouldDelete = true;
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    if (shouldDelete) {
                        String formatted = hiveLabel + "\\" + fullSubKey;
                        try {
                            Advapi32Util.registryDeleteKey(hive, fullSubKey);
                            summary.add("Deleted registry key: " + formatted);
                        } catch (Exception ex) {
                            if (deleteRegistryKeyRecursively(hive, fullSubKey)) {
                                summary.add("Deleted registry key: " + formatted);
                            } else {
                                errors.add("Failed to delete registry key: " + formatted);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                errors.add("Failed to scan registry Uninstall path " + hiveLabel + "\\" + keyPath + ": " + e.getMessage());
            }
        }

        // Delete Start Menu shortcuts
        List<String> startMenuRoots = new ArrayList<>();
        String programData = System.getenv("ProgramData");
        String appData = System.getenv("AppData");
        if (programData != null && !programData.isBlank()) {
            startMenuRoots.add(programData + "\\Microsoft\\Windows\\Start Menu");
        }
        if (appData != null && !appData.isBlank()) {
            startMenuRoots.add(appData + "\\Microsoft\\Windows\\Start Menu");
        }

        for (String root : startMenuRoots) {
            File startMenuDir = new File(root);
            if (startMenuDir.exists() && startMenuDir.isDirectory()) {
                deleteMatchingFiles(startMenuDir, lowerAppName, summary, errors);
            }
        }

        return new ForceUninstallResult(summary, errors);
    }

    private void killProcessesByPath(String installLoc, String appName, List<String> summary, List<String> errors) {
        try {
            String psScript;
            if (installLoc != null && !installLoc.isBlank()) {
                String escapedPath = installLoc.replace("'", "''");
                psScript = "Get-Process | Where-Object { $_.Path -like '" + escapedPath + "*' } | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }; " +
                        "Write-Output 'done'";
            } else {
                String escapedName = appName.replace("'", "''").replace("[", "`[").replace("]", "`]").replace("*", "`*").replace("?", "`?");
                psScript = "Get-Process | Where-Object { $_.ProcessName -like '" + escapedName + "' } | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }; " +
                        "Write-Output 'done'";
            }
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", psScript);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            // Drain the output stream to prevent deadlock if the buffer fills up
            Thread drainThread = new Thread(() -> {
                try { proc.getInputStream().readAllBytes(); } catch (Exception ignored) {}
            }, "ps-drain");
            drainThread.setDaemon(true);
            drainThread.start();
            proc.waitFor(10, TimeUnit.SECONDS);
            proc.destroy();
            summary.add("Killed processes matching " + (installLoc != null && !installLoc.isBlank() ? "install path" : "app name") + " for: " + appName);
        } catch (Exception e) {
            errors.add("Failed to kill processes for " + appName + ": " + e.getMessage());
        }
    }

    private void deleteMatchingFiles(File dir, String lowerName, List<String> summary, List<String> errors) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.getName().toLowerCase().contains(lowerName)) {
                if (NativeFileHelper.deleteOrQueue(file)) {
                    summary.add("Deleted: " + file.getAbsolutePath());
                } else {
                    errors.add("Scheduled for reboot deletion: " + file.getAbsolutePath());
                }
            } else if (file.isDirectory()) {
                deleteMatchingFiles(file, lowerName, summary, errors);
            }
        }
    }
}
