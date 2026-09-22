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

    public record RegistryBackupResult(
            java.util.Map<String, Path> exportedByKey,
            java.util.List<String> failedKeys) {}

    public record FilesystemCleanupResult(
            int deleted,
            int recycled,
            int alreadyAbsent,
            int queuedForReboot,
            int failed,
            java.util.List<String> failedDetails) {}

    private final Win32AppDiscoverer win32Discoverer = new Win32AppDiscoverer();
    private final ProcessRunner processRunner = new ProcessRunner(1800); // 30-minute timeout for uninstallers

    public List<InstalledApp> listWin32Apps() {
        return listWin32Apps(null);
    }

    public List<InstalledApp> listWin32Apps(java.util.concurrent.atomic.AtomicBoolean cancelled) {
        return win32Discoverer.discoverApps(cancelled);
    }

    public List<InstalledApp> listAppxApps() {
        return listAppxApps("appx-list.ps1", null);
    }

    /**
     * Fast AppX listing without per-package recursive size computation.
     * Used for instant table display; sizes are enriched lazily via
     * {@link #computeAppxSizeKB(InstalledApp)}.
     */
    public List<InstalledApp> listAppxAppsFast() {
        return listAppxAppsFast(null);
    }

    /**
     * BLOCKER FIX: cancellable AppX listing. The Uninstaller tab Cancel button
     * previously only flipped a token that was checked AFTER the PowerShell
     * child finished, so Cancel during Get-AppxPackage did nothing and the tab
     * stayed busy until the 30-minute ProcessRunner timeout. The token is now
     * forwarded to ProcessRunner (which taskkills the tree on cancel) with a
     * tight 120s listing timeout, and CancellationException propagates instead
     * of being swallowed + retried as a full scan.
     */
    public List<InstalledApp> listAppxAppsFast(java.util.concurrent.atomic.AtomicBoolean cancelled) {
        try {
            return listAppxApps("appx-list-fast.ps1", cancelled);
        } catch (java.util.concurrent.CancellationException ce) {
            throw ce;
        } catch (Exception e) {
            if (cancelled != null && cancelled.get()) {
                throw new java.util.concurrent.CancellationException("AppX scan cancelled");
            }
            // Script missing / parse failure only — a successful empty inventory
            // (typical after filtering Microsoft packages) must not run the slow scan.
            AppLogger.debug("Fast AppX list unavailable, falling back: " + e.getMessage());
            return listAppxApps("appx-list.ps1", cancelled);
        }
    }

    private List<InstalledApp> listAppxApps(String scriptName) {
        return listAppxApps(scriptName, null);
    }

    private List<InstalledApp> listAppxApps(String scriptName, java.util.concurrent.atomic.AtomicBoolean cancelled) {
        List<InstalledApp> apps = new ArrayList<>();
        try {
            Path script = PowerShellScripts.resolve(scriptName);
            // Tight listing timeout (120s): Get-AppxPackage must never hold the
            // tab busy for the 30-minute uninstaller budget. Cancel is honoured
            // via taskkill in ProcessRunner.
            ProcessResult result = processRunner.run(
                    ProcessRunner.powershellScript(script.toString()), 120, cancelled);
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
                String out = result.combinedOutput();
                AppLogger.warning("Appx package scan failed: " + out);
                throw new AppDiscoveryException("AppX scan failed: " + truncate(out, 400));
            }
        } catch (java.util.concurrent.CancellationException ce) {
            // Cancel must propagate so the tab exits quietly without an error
            // dialog and without falling back to the slow full scan.
            throw ce;
        } catch (AppDiscoveryException e) {
            throw e;
        } catch (Exception e) {
            AppLogger.error("Failed to list Appx packages", e);
            throw new AppDiscoveryException("AppX scan failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }

        // Sort alphabetically
        apps.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return apps;
    }

    /**
     * Best-effort on-disk size for a Store app (KB). Returns 0 when unknown.
     * Runs off the FX thread; capped traversal to avoid long stalls.
     */
    public int computeAppxSizeKB(InstalledApp app) {
        return computeAppxSizeKB(app, null);
    }

    public int computeAppxSizeKB(InstalledApp app, AtomicBoolean cancelled) {
        if (cancelled != null && cancelled.get()) return 0;
        if (app == null || app.getInstallLocation() == null
                || app.getInstallLocation().isBlank()) return 0;
        try {
            File dir = new File(app.getInstallLocation());
            if (!dir.exists() || !dir.isDirectory()) return 0;
            long bytes = sizeTreeNoFollow(dir.toPath(), cancelled, 20000);
            if (bytes < 0) return 0;
            long kb = bytes / 1024;
            return kb > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) kb;
        } catch (Exception e) {
            return 0;
        }
    }

    private InstalledApp parseAppxNode(JsonNode node) {
        // Name is the friendly manifest DisplayName when available (script falls back to package name)
        String name = node.path("Name").asText("");
        String packageName = node.path("PackageName").asText("");
        String packageFullName = node.path("PackageFullName").asText("");
        if (packageName.isBlank() && !packageFullName.isBlank()) {
            int u = packageFullName.indexOf('_');
            packageName = u > 0 ? packageFullName.substring(0, u) : "";
        }
        String version = node.path("Version").asText("");
        String publisher = node.path("Publisher").asText("");
        String installLocation = node.path("InstallLocation").asText("");
        String installDate = node.path("InstallDate").asText("");
        int installedSize = node.path("InstalledSize").asInt(0);

        return new InstalledApp(
                name, publisher, version, installLocation,
                "", "", "", false, packageFullName, packageName, "",
                installDate, installedSize, "Store"
        );
    }

    private boolean isMicrosoftOrWindows(InstalledApp app) {
        return AppCompatUtils.isMicrosoftOrWindows(app);
    }

    /**
     * Triggers the uninstaller and monitors it until it completes.
     * Uses the interactive UninstallString by default; quiet only on explicit request.
     */
    public ProcessResult runUninstaller(InstalledApp app) throws IOException, InterruptedException {
        return runUninstaller(app, false);
    }

    public ProcessResult runUninstaller(InstalledApp app, boolean preferQuiet) throws IOException, InterruptedException {
        if (!app.isWin32()) {
            if (app.getAppxPackageFullName() == null || app.getAppxPackageFullName().isBlank()) {
                throw new IOException("No package identity available for " + app.getName());
            }
            Path script = PowerShellScripts.resolve("appx-uninstall.ps1");
            return processRunner.run(ProcessRunner.powershellScript(script.toString(), "-PackageFullName", app.getAppxPackageFullName()));
        } else {
            String uninstallCmd = app.getEffectiveUninstallString(preferQuiet);
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
        return runUninstallerAndWait(app, timeoutSeconds, false);
    }

    public ProcessResult runUninstallerAndWait(InstalledApp app, long timeoutSeconds, boolean preferQuiet) throws IOException, InterruptedException {
        return runUninstallerAndWait(app, timeoutSeconds, preferQuiet, null);
    }

    public ProcessResult runUninstallerAndWait(InstalledApp app, long timeoutSeconds, boolean preferQuiet,
                                               AtomicBoolean cancelled) throws IOException, InterruptedException {
        if (!app.isWin32()) {
            if (app.getAppxPackageFullName() == null || app.getAppxPackageFullName().isBlank()) {
                throw new IOException("No package identity available for " + app.getName());
            }
            Path script = PowerShellScripts.resolve("appx-uninstall.ps1");
            return processRunner.run(ProcessRunner.powershellScript(script.toString(), "-PackageFullName", app.getAppxPackageFullName()), timeoutSeconds, cancelled);
        } else {
            String uninstallCmd = app.getEffectiveUninstallString(preferQuiet);
            if (uninstallCmd == null || uninstallCmd.isBlank()) {
                throw new IOException("No uninstall command available for " + app.getName());
            }
            List<String> command = parseUninstallCommand(uninstallCmd);

            // Snapshot existing PIDs before launching uninstaller to detect spawned children reliably
            java.util.Set<Long> prePids = new java.util.HashSet<>();
            try {
                ProcessHandle.allProcesses().forEach(ph -> prePids.add(ph.pid()));
            } catch (Exception ignored) {}

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
            // Run from the app's install dir when known so relative-path uninstallers resolve resources.
            // Never use a protected OS path or a junction (cwd would be the TARGET).
            try {
                String loc = app.getInstallLocation();
                if (loc != null && !loc.isBlank() && !isProtectedPath(loc)) {
                    File dir = new File(loc);
                    File workDir = dir.isDirectory() ? dir : dir.getParentFile();
                    if (workDir != null && workDir.isDirectory()
                            && !isProtectedPath(workDir.getAbsolutePath())
                            && !isLinkOrReparse(workDir)) {
                        pb.directory(workDir);
                    }
                }
            } catch (Exception ignored) {}
            AppLogger.info("Running uninstaller: " + String.join(" ", command));
            Process process = pb.start();
            try {
                ProcessManager.register(process);
            } catch (Throwable ignored) {}

            // Capture stdout/stderr via buffers instead of discarding — needed for error display.
            java.io.ByteArrayOutputStream stdoutBuf = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream stderrBuf = new java.io.ByteArrayOutputStream();
            Thread drainStdout = new Thread(() -> {
                try { process.getInputStream().transferTo(stdoutBuf); } catch (Exception ignored) {}
            }, "uninstaller-stdout-drain");
            drainStdout.setDaemon(true);
            drainStdout.start();
            Thread drainStderr = new Thread(() -> {
                try { process.getErrorStream().transferTo(stderrBuf); } catch (Exception ignored) {}
            }, "uninstaller-stderr-drain");
            drainStderr.setDaemon(true);
            drainStderr.start();

            // Don't snapshot descendants immediately after start — children are often spawned
            // 1-2s later (wrapper -> msiexec). Instead poll during wait with remaining budget.
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            boolean finished = false;
            while (System.nanoTime() < deadlineNanos) {
                if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    finished = true;
                    break;
                }
            }
            if (!finished) {
                // BLOCKER FIX: kill the whole tree (wrapper -> msiexec/setup), not just
                // the wrapper. destroyForcibly() alone orphaned msiexec children that
                // kept file locks and showed as still-installed after timeout.
                killProcessTreeBestEffort(process);
                process.destroyForcibly();
                try { drainStdout.join(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                try { drainStderr.join(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IOException("Uninstaller timed out after " + timeoutSeconds + "s");
            }

            int exitCode = process.exitValue();

            // Join drain threads so streams are fully consumed
            try { drainStdout.join(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { drainStderr.join(10_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            String stdout = stdoutBuf.toString(java.nio.charset.StandardCharsets.UTF_8);
            String stderr = stderrBuf.toString(java.nio.charset.StandardCharsets.UTF_8);

            // Wait for descendant processes with remaining timeout budget.
            // Use full remaining budget (not capped to 30s) so interactive uninstall wizards
            // that require user interaction (>30s) do not cause leftover scan to appear early.
            long remainingSeconds = Math.max(5, TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
            // Poll for newly-spawned descendants that may have appeared after main exit
            // First try PID-snapshot based wait for any new installer processes (handles msiexec, setup, unins)
            waitForNewUninstallerProcesses(prePids, app, (int) Math.min(120, remainingSeconds));
            // Fallback stricter name/path matching for cases where snapshot missed due to reuse
            if (remainingSeconds > 10) {
                long afterSnapshotRemaining = Math.max(5, TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
                waitForChildProcesses(app, (int) Math.min(60, afterSnapshotRemaining));
            }
            // Wait for the install directory to be removed (short cap: leftovers
            // review handles remaining files, so never burn the full budget here).
            // BLOCKER FIX: skip when the uninstaller reported success + reboot required
            // (3010/1641). Files pending reboot never disappear until restart, so the
            // old code always burned the full 120s wait as a pure hang after success.
            boolean rebootRequired = exitCode == ProcessResult.MSI_SUCCESS_REBOOT_REQUIRED
                    || exitCode == ProcessResult.MSI_SUCCESS_REBOOT_INITIATED;
            if (rebootRequired) {
                AppLogger.info("Skipping install-dir wait (reboot required, exit=" + exitCode + ")");
            } else {
                long afterChildRemaining = Math.max(5, TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
                waitForInstallDirRemoval(app, (int) Math.min(15, afterChildRemaining));
            }

            return new ProcessResult(exitCode, stdout, stderr);
        }
    }

    /**
     * BLOCKER FIX: best-effort tree kill for timed-out uninstallers.
     * Mirrors ProcessRunner's taskkill /T /F so wrapper children (msiexec,
     * setup, unins) do not survive as orphans holding file locks.
     */
    private static void killProcessTreeBestEffort(Process process) {
        if (process == null) return;
        try {
            long pid = process.pid();
            if (pid > 0 && com.sbtools.util.AppPaths.isWindows()) {
                try {
                    new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD)
                            .start().waitFor(5, TimeUnit.SECONDS);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Waits for newly spawned installer processes (wrapper -> msiexec/setup/unins) to exit.
     * Blocks leftover scan until the actual uninstall wizard finishes. Handles:
     * - MSI: waits for any msiexec whose command line contains the product GUID (even if PID existed before as service)
     * - Inno/NSIS: waits for new PIDs whose execPath is inside installLocation or matches app name / unins base
     * Uses a spawn-window (5s) so we don't miss children that appear 1-2s after wrapper exits.
     */
    private void waitForNewUninstallerProcesses(java.util.Set<Long> prePids, InstalledApp app, int maxWaitSeconds) {
        if (maxWaitSeconds <= 0) return;
        String installLoc = app.getInstallLocation();
        String lowerLoc = installLoc != null ? installLoc.toLowerCase().trim() : "";
        String appName = app.getName() != null ? app.getName().toLowerCase().trim() : "";
        String lowerName = appName;
        String uninstallStr = ((app.getUninstallString() != null ? app.getUninstallString() : "") + " "
                + (app.getQuietUninstallString() != null ? app.getQuietUninstallString() : "")).toLowerCase();
        // Extract GUIDs like {12345678-1234-...} from uninstall string for MSI tracking
        java.util.List<String> guids = new java.util.ArrayList<>();
        try {
            java.util.regex.Matcher gm = java.util.regex.Pattern.compile("\\{[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\}").matcher(uninstallStr);
            while (gm.find()) guids.add(gm.group().toLowerCase());
        } catch (Exception ignored) {}
        // Also extract uninstall exe base name (e.g. unins000) for Inno/NSIS tracking when installLocation is blank
        String tmpBase = "";
        try {
            String probeCmd = app.getUninstallString() != null && !app.getUninstallString().isBlank()
                    ? app.getUninstallString() : (app.getQuietUninstallString() != null ? app.getQuietUninstallString() : "");
            List<String> toks = parseUninstallCommand(probeCmd);
            if (!toks.isEmpty()) {
                String exe = toks.get(0);
                String leaf = new File(exe).getName().toLowerCase();
                if (leaf.endsWith(".exe")) leaf = leaf.substring(0, leaf.length() - 4);
                if (!leaf.isEmpty() && !leaf.equals("msiexec") && !isGenericName(leaf)) tmpBase = leaf;
            }
        } catch (Exception ignored) {}
        final String uninstallBase = tmpBase;

        long deadline = System.currentTimeMillis() + (maxWaitSeconds * 1000L);
        long spawnWindowDeadline = System.currentTimeMillis() + 6000; // 6s window for child to appear
        long selfPid = ProcessHandle.current().pid();
        boolean everFound = false;

        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean found = new AtomicBoolean(false);
            try {
                ProcessHandle.allProcesses().forEach(ph -> {
                    if (found.get()) return;
                    if (!ph.isAlive()) return;
                    long pid = ph.pid();
                    if (pid == selfPid) return;
                    ProcessHandle.Info info = ph.info();
                    String cmdLine = info.commandLine().orElse("").toLowerCase();
                    String execPath = info.command().map(String::toLowerCase).orElse("");
                    if (execPath.isEmpty() && cmdLine.isEmpty()) return;

                    // 1) MSI GUID match — check ALL processes (service PID may be pre-existing)
                    if (!guids.isEmpty()) {
                        for (String g : guids) {
                            if (cmdLine.contains(g) || execPath.contains(g)) {
                                found.set(true);
                                return;
                            }
                        }
                        // Also any msiexec that appeared after start and contains /x or /uninstall is likely ours
                        if ((execPath.contains("msiexec") || cmdLine.contains("msiexec")) && !prePids.contains(pid)) {
                            // If GUID not in cmdLine (some wrappers hide it), still wait for new msiexec for a short period
                            if (cmdLine.contains("/x") || cmdLine.contains("/uninstall") || cmdLine.contains("uninstall")) {
                                found.set(true);
                                return;
                            }
                        }
                    }

                    // For non-MSI, only consider newly spawned PIDs
                    if (prePids.contains(pid)) return;

                    boolean isMsiexec = execPath.contains("msiexec") || cmdLine.contains("msiexec");
                    boolean msiexecUninstall = isMsiexec
                            && (cmdLine.contains("/x") || cmdLine.contains("/uninstall") || cmdLine.contains("uninstall"));
                    boolean isSetupUnins = execPath.contains("setup") || execPath.contains("unins")
                            || execPath.contains("uninstall");
                    boolean matchByPath = !lowerLoc.isEmpty() && !isProtectedPath(lowerLoc)
                            && (containsWordBoundary(cmdLine, lowerLoc) || containsWordBoundary(execPath, lowerLoc)
                                || execPath.startsWith(lowerLoc + "\\") || execPath.startsWith(lowerLoc + "/"));
                    boolean matchByName = lowerName.length() >= 3
                            && !isGenericName(lowerName)
                            && (containsWordBoundary(cmdLine, lowerName) || containsWordBoundary(execPath, lowerName)
                                || execPath.endsWith("\\" + lowerName + ".exe") || execPath.endsWith("/" + lowerName + ".exe"));
                    boolean matchByUninstallBase = !uninstallBase.isEmpty()
                            && (execPath.contains(uninstallBase) || cmdLine.contains(uninstallBase));

                    if (msiexecUninstall || matchByPath || matchByName || matchByUninstallBase || isSetupUnins) {
                        if (msiexecUninstall) {
                            found.set(true);
                        } else if (matchByPath || matchByName || matchByUninstallBase) {
                            found.set(true);
                        } else if (isSetupUnins && !lowerLoc.isEmpty() && !isProtectedPath(lowerLoc)) {
                            // Parent-dir fallback for Inno/NSIS wrappers: require a real
                            // parent dir, never empty-string prefix (matches everything).
                            String parentDir = null;
                            try {
                                parentDir = new File(lowerLoc).getParent();
                            } catch (Exception ignored) {}
                            if (parentDir != null && !parentDir.isBlank() && !isProtectedPath(parentDir)
                                    && execPath.startsWith(parentDir.toLowerCase() + "\\")) {
                                found.set(true);
                            }
                        }
                    }
                });
            } catch (Exception ignored) {}
            boolean isFound = found.get();
            if (isFound) everFound = true;
            long now = System.currentTimeMillis();
            if (isFound) {
                // Child still running — keep waiting
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            } else {
                if (!everFound && now < spawnWindowDeadline) {
                    // Child hasn't appeared yet but still within spawn window — keep polling
                    try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                } else if (everFound) {
                    // Child was running and now gone — done
                    break;
                } else {
                    // No child ever appeared after spawn window — assume no child
                    break;
                }
            }
        }
        AppLogger.info("Finished waiting for new uninstaller processes for: " + app.getName() + " everFound=" + everFound);
    }

    /**
     * Waits for any child processes related to the app to exit.
     * Uses stricter matching (>=5 chars, word-boundary aware) to avoid false positives
     * like "Team" matching "steam.exe" or "media player" blocking on unrelated player.
     */
    private void waitForChildProcesses(InstalledApp app, int maxWaitSeconds) {
        String installLoc = app.getInstallLocation();
        String lowerLoc = installLoc != null ? installLoc.toLowerCase().trim() : "";
        String appName = app.getName() != null ? app.getName().toLowerCase().trim() : "";
        String lowerName = appName;
        long deadline = System.currentTimeMillis() + (maxWaitSeconds * 1000L);
        long selfPid = ProcessHandle.current().pid();

        while (System.currentTimeMillis() < deadline) {
            AtomicBoolean found = new AtomicBoolean(false);
            try {
                ProcessHandle.allProcesses().forEach(ph -> {
                    if (found.get()) return;
                    if (!ph.isAlive()) return;
                    if (ph.pid() == selfPid) return;
                    ProcessHandle.Info info = ph.info();
                    String cmdLine = info.commandLine().orElse("").toLowerCase();
                    String execPath = info.command().map(String::toLowerCase).orElse("");

                    boolean matchByPath = !lowerLoc.isEmpty() && !isProtectedPath(lowerLoc)
                            && (containsWordBoundary(cmdLine, lowerLoc) || containsWordBoundary(execPath, lowerLoc));
                    boolean matchByName = lowerName.length() >= 5
                            && !isGenericName(lowerName)
                            && (containsWordBoundary(cmdLine, lowerName) || containsWordBoundary(execPath, lowerName)
                                || execPath.endsWith("\\" + lowerName + ".exe") || execPath.endsWith("/" + lowerName + ".exe"));

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

    static boolean containsWordBoundary(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) return false;
        boolean isPath = needle.contains("\\") || needle.contains("/") || needle.contains(":");
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            if (isPath) {
                boolean leftBound = idx == 0 || isBoundaryChar(haystack.charAt(idx - 1)) || haystack.charAt(idx - 1) == ':';
                int end = idx + needle.length();
                boolean rightBound = end >= haystack.length() || isBoundaryChar(haystack.charAt(end)) || haystack.charAt(end) == '\\' || haystack.charAt(end) == '/';
                if (leftBound && rightBound) return true;
            } else {
                boolean leftBound = idx == 0 || isBoundaryChar(haystack.charAt(idx - 1));
                int end = idx + needle.length();
                boolean rightBound = end >= haystack.length() || isBoundaryChar(haystack.charAt(end));
                if (leftBound && rightBound) return true;
            }
            idx += 1; // continue searching next occurrence
        }
        return false;
    }

    private static boolean isBoundaryChar(char c) {
        return c == '\\' || c == '/' || c == '.' || c == ' ' || c == '"' || c == '\''
                || c == '_' || c == '-' || c == ':' || c == ';';
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
        return normalizeMsiUninstallArgs(wrapNonPeHost(parseUninstallCommandRaw(uninstallCmd)), uninstallCmd);
    }

    /**
     * CreateProcess only starts a PE image. Batch, script, and bare MSI
     * uninstall strings need a host, with the path as its own argument
     * (no {@code cmd /c} string that re-parses {@code &} {@code |}).
     */
    public static List<String> wrapNonPeHost(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return tokens;
        String ext = fileExtension(tokens.get(0));
        if (ext.equals("bat") || ext.equals("cmd")) {
            List<String> out = new ArrayList<>();
            out.add("cmd.exe");
            out.add("/d");
            out.add("/c");
            // cmd.exe re-parses the command line. Java quotes tokens that contain
            // spaces; escape & | ^ in tokens it leaves bare so they stay literals.
            for (String token : tokens) out.add(cmdLiteral(token));
            return out;
        }
        if (ext.equals("msi")) {
            List<String> out = new ArrayList<>();
            out.add("msiexec.exe");
            out.add("/x");
            out.addAll(tokens);
            return out;
        }
        if (ext.equals("ps1")) {
            List<String> out = new ArrayList<>();
            out.add("powershell.exe");
            out.add("-NoProfile");
            out.add("-ExecutionPolicy");
            out.add("Bypass");
            out.add("-File");
            out.addAll(tokens);
            return out;
        }
        return tokens;
    }

    private static String fileExtension(String path) {
        String leaf = new File(path).getName();
        int dot = leaf.lastIndexOf('.');
        if (dot < 0 || dot == leaf.length() - 1) return "";
        return leaf.substring(dot + 1).toLowerCase();
    }

    /** Escape cmd metacharacters in tokens Java will not quote (no space, tab, or &lt;&gt;). */
    static String cmdLiteral(String arg) {
        if (arg == null) return "";
        boolean quotedByJava = false;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == ' ' || c == '\t' || c == '<' || c == '>') {
                quotedByJava = true;
                break;
            }
        }
        if (quotedByJava) return arg;
        StringBuilder sb = new StringBuilder(arg.length());
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '&' || c == '|' || c == '^') sb.append('^');
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Some MSI-based entries store {@code MsiExec.exe /I{GUID}} (modify/repair entry point)
     * instead of {@code /X{GUID}} (uninstall). Running {@code /I} from an "Uninstall" action
     * opens a maintenance dialog instead of removing the product, confusing users and
     * surfacing a false failure when they cancel. Rewrite to {@code /X} only when the flag
     * carries a product GUID (attached {@code /I{GUID}} or bare {@code /I} followed by a GUID
     * token) — never invent flags otherwise.
     */
    private static List<String> normalizeMsiUninstallArgs(List<String> tokens, String original) {
        if (tokens.size() < 2) return tokens;
        String leaf = new File(tokens.get(0)).getName();
        int dot = leaf.lastIndexOf('.');
        String base = (dot > 0 ? leaf.substring(0, dot) : leaf).toLowerCase();
        if (!base.equals("msiexec")) return tokens;
        List<String> out = new ArrayList<>(tokens);
        boolean changed = false;
        for (int i = 1; i < out.size(); i++) {
            String t = out.get(i);
            if (t.length() >= 2 && (t.charAt(0) == '/' || t.charAt(0) == '-')
                    && (t.charAt(1) == 'I' || t.charAt(1) == 'i')) {
                String rest = t.substring(2);
                if (rest.isEmpty()) {
                    if (i + 1 < out.size() && out.get(i + 1).matches("(?i)\\{[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\}")) {
                        out.set(i, t.charAt(0) + "X");
                        changed = true;
                    }
                } else if (rest.matches("(?i)\\{[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}\\}")) {
                    out.set(i, t.charAt(0) + "X" + rest);
                    changed = true;
                }
            }
        }
        if (changed) {
            AppLogger.info("Rewrote msiexec /I to /X for uninstall (from: " + original + ")");
        }
        return out;
    }

    private List<String> parseUninstallCommandRaw(String uninstallCmd) {
        String trimmed = uninstallCmd.trim();
        // Expand %VAR% environment variables before tokenizing (many uninstallers store raw env paths)
        String expanded = expandEnvironmentVariables(trimmed);
        if (expanded.isBlank()) {
            throw new IllegalArgumentException("Empty uninstall command: " + uninstallCmd);
        }
        // If command starts with a quote, splitCommandLine already handles it correctly
        if (expanded.startsWith("\"")) {
            List<String> tokens = splitCommandLine(expanded);
            if (tokens.isEmpty()) {
                throw new IllegalArgumentException("Empty uninstall command: " + uninstallCmd);
            }
            logExeResolution(tokens.get(0), uninstallCmd);
            return tokens;
        }
        // Unquoted path handling: many registry UninstallString values store
        //  C:/Program Files (x86)/Vendor/App/uninstall.exe /S  without quotes.
        //  Naive split on spaces would break the exe path. Probe for the longest
        //  prefix that is an existing .exe file (or looks like an exe path) and
        //  split there; remainder is tokenized separately.
        List<String> probed = probeUnquotedExe(expanded);
        if (probed != null && !probed.isEmpty()) {
            logExeResolution(probed.get(0), uninstallCmd);
            return probed;
        }
        // Fallback: Never use cmd.exe /c — it interprets shell metacharacters (&, |, >) from registry.
        List<String> tokens = splitCommandLine(expanded);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("Empty uninstall command: " + uninstallCmd);
        }
        logExeResolution(tokens.get(0), uninstallCmd);
        return tokens;
    }

    private void logExeResolution(String exe, String original) {
        if (!new File(exe).isAbsolute() && !exe.contains("\\") && !exe.contains("/")) {
            AppLogger.debug("Uninstall exe via PATH lookup: " + exe);
        } else {
            File f = new File(exe);
            if (!f.exists()) {
                AppLogger.warning("Uninstall exe not found after env expansion: " + exe + " (from: " + original + ")");
            }
        }
    }

    /**
     * Probes an unquoted uninstall command for an exe boundary that contains spaces.
     * Returns tokens [exe, ...args] if a plausible exe is found, otherwise null to
     * fallback to normal tokenization.
     */
    private static List<String> probeUnquotedExe(String expanded) {
        String lower = expanded.toLowerCase();
        int bestEnd = -1;
        String bestCandidate = null;
        int idx = 0;
        // Pass 1: prefer an existing file — check .exe/.bat/.cmd/.msi/.ps1
        String[] exts = {".exe", ".bat", ".cmd", ".msi", ".ps1"};
        for (String ext : exts) {
            idx = 0;
            while ((idx = lower.indexOf(ext, idx)) >= 0) {
                int end = idx + ext.length();
                String candidate = expanded.substring(0, end).trim();
                // Strip stray surrounding quotes if present (defensive)
                if (candidate.length() > 1 && candidate.startsWith("\"") && candidate.endsWith("\"")) {
                    candidate = candidate.substring(1, candidate.length() - 1);
                }
                // Remove leading quote if unmatched (e.g. "C:\... truncated)
                if (candidate.startsWith("\"")) {
                    int q = candidate.indexOf('"', 1);
                    if (q > 0) candidate = candidate.substring(1, q);
                    else candidate = candidate.substring(1);
                }
                candidate = candidate.trim();
                if (candidate.isEmpty()) { idx = end; continue; }
                boolean isBare = !candidate.contains("\\") && !candidate.contains("/") && !candidate.contains(":");
                File f = new File(candidate);
                if (isBare) {
                    // Bare exe like MsiExec.exe — accept immediately (PATH resolved)
                    if (candidate.toLowerCase().endsWith(ext)) {
                        bestCandidate = candidate;
                        bestEnd = end;
                        break;
                    }
                } else if (f.exists()) {
                    // Prefer longest existing prefix (handles nested folder names)
                    bestCandidate = candidate;
                    bestEnd = end;
                }
                idx = end;
            }
            if (bestCandidate != null) break;
        }
        if (bestCandidate != null) {
            return buildTokensFromSplit(expanded, bestEnd, bestCandidate);
        }
        // Pass 2: no existing file — heuristic: longest prefix that *looks* like an exe/bat path (contains :\ and ends with known ext)
        bestEnd = -1;
        bestCandidate = null;
        for (String ext : exts) {
            idx = 0;
            while ((idx = lower.indexOf(ext, idx)) >= 0) {
                int end = idx + ext.length();
                String candidate = expanded.substring(0, end).trim();
                if (candidate.startsWith("\"")) {
                    int q = candidate.indexOf('"', 1);
                    if (q > 0) candidate = candidate.substring(1, q);
                    else candidate = candidate.substring(1);
                    candidate = candidate.trim();
                }
                if (candidate.contains(":\\") && candidate.toLowerCase().endsWith(ext)) {
                    bestCandidate = candidate;
                    bestEnd = end;
                }
                idx = end;
            }
        }
        if (bestCandidate != null) {
            // Validate that remainder after bestEnd does not start inside the exe name
            // (e.g. `C:\Foo.exeBar` is not a valid split — but such strings are rare)
            return buildTokensFromSplit(expanded, bestEnd, bestCandidate);
        }
        return null;
    }

    private static List<String> buildTokensFromSplit(String expanded, int exeEnd, String exe) {
        String remainder = expanded.substring(exeEnd).trim();
        List<String> tokens = new ArrayList<>();
        tokens.add(exe);
        if (!remainder.isEmpty()) {
            tokens.addAll(splitCommandLine(remainder));
        }
        return tokens;
    }

    static String expandEnvironmentVariables(String cmd) {
        if (cmd == null || !cmd.contains("%")) return cmd;
        // Build case-insensitive env map
        java.util.Map<String, String> envLower = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, String> e : System.getenv().entrySet()) {
            envLower.put(e.getKey().toLowerCase(), e.getValue());
        }
        // Also add common aliases that may not be in env on some setups
        if (!envLower.containsKey("systemroot")) {
            String sysRoot = System.getenv("SystemRoot");
            if (sysRoot == null) sysRoot = System.getenv("WINDIR");
            if (sysRoot != null) envLower.put("systemroot", sysRoot);
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < cmd.length()) {
            char c = cmd.charAt(i);
            if (c == '%') {
                int end = cmd.indexOf('%', i + 1);
                if (end > i + 1) {
                    String var = cmd.substring(i + 1, end);
                    // var name must be word chars only (avoid matching stray %)
                    if (var.matches("[A-Za-z0-9_()]+")) {
                        String val = envLower.get(var.toLowerCase());
                        if (val != null) {
                            sb.append(val);
                            i = end + 1;
                            continue;
                        }
                    }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
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
     * Public\Documents, Desktop, Quick Launch.
     * PATH entries are NOT included here — use {@link #scanPathWarnings(InstalledApp)} for those.
     */
    public List<String> scanFilesystemLeftovers(InstalledApp app) {
        return scanFilesystemLeftovers(app, true);
    }

    /**
     * Cancellable variant — checks {@code cancelled} between roots so the UI
     * Cancel button can abort long scans. Null means not cancellable.
     */
    public List<String> scanFilesystemLeftovers(InstalledApp app, boolean includePrimaryLocation,
                                                java.util.concurrent.atomic.AtomicBoolean cancelled) {
        List<String> leftovers = new ArrayList<>();
        // When uninstall failed, never re-offer the live install dir via the heuristic
        // root walk (name/publisher match under Program Files / AppData).
        java.util.Set<String> excludedPrimaries = includePrimaryLocation
                ? java.util.Set.of()
                : excludedPrimaryLeftoverPaths(app);

        if (includePrimaryLocation && app.getInstallLocation() != null && !app.getInstallLocation().isBlank()
                && !isProtectedPath(app.getInstallLocation())) {
            String canon = canonicalizeForSafety(app.getInstallLocation());
            File installDir = new File(canon != null ? canon : app.getInstallLocation());
            if (installDir.exists()) {
                String locForVendor = canon != null ? canon : app.getInstallLocation();
                if (isSharedVendorInstallLocation(locForVendor, app.getName(), app.getPublisher())) {
                    AppLogger.info("Skipping shared vendor install location from deletable leftovers: "
                            + locForVendor);
                    if (!isLinkOrReparse(installDir)) {
                        String lowerApp = app.getName() != null ? app.getName().toLowerCase() : "";
                        for (File inner : matchingInnerAppDirs(installDir, lowerApp)) {
                            String absPath = inner.getAbsolutePath();
                            if (offerFilesystemLeftover(absPath, leftovers, excludedPrimaries)) {
                                leftovers.add(absPath);
                            }
                        }
                    }
                } else {
                    leftovers.add(installDir.getAbsolutePath());
                }
            }
        } else if (app.getInstallLocation() != null && !app.getInstallLocation().isBlank()
                && isProtectedPath(app.getInstallLocation())) {
            AppLogger.info("Skipping protected install location from deletable leftovers: " + app.getInstallLocation());
        }

        List<String> roots = new ArrayList<>();
        addIfNotNull(roots, System.getenv("ProgramFiles"));
        addIfNotNull(roots, System.getenv("ProgramFiles(x86)"));
        addIfNotNull(roots, System.getenv("CommonProgramFiles"));
        addIfNotNull(roots, System.getenv("CommonProgramFiles(x86)"));
        addIfNotNull(roots, System.getenv("AppData"));
        addIfNotNull(roots, System.getenv("LocalAppData"));
        addIfNotNull(roots, System.getenv("ProgramData"));

        String localAppData = System.getenv("LocalAppData");
        String appData = System.getenv("AppData");
        String userProfile = System.getenv("USERPROFILE");
        String publicDir = System.getenv("PUBLIC");
        if (localAppData != null) addIfNotNull(roots, localAppData + "\\Programs");
        if (appData != null) addIfNotNull(roots, appData + "\\LocalLow");
        if (publicDir != null) addIfNotNull(roots, publicDir + "\\Documents");
        if (userProfile != null) addIfNotNull(roots, userProfile + "\\Desktop");
        if (appData != null) addIfNotNull(roots, appData + "\\Microsoft\\Internet Explorer\\Quick Launch");

        roots = new ArrayList<>(new java.util.LinkedHashSet<>(roots));

        for (String root : roots) {
            if (cancelled != null && cancelled.get()) break;
            File rootDir = new File(root);
            if (!rootDir.exists() || !rootDir.isDirectory()) continue;
            File[] children = rootDir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (cancelled != null && cancelled.get()) break;
                if (child.isDirectory()) {
                    if (isFolderMatch(child.getName(), app.getName(), app.getPublisher())) {
                        if (looksLikeSharedVendorDir(child.getAbsolutePath(), app.getName())) {
                            if (!isLinkOrReparse(child)) {
                                String lowerApp = app.getName() != null ? app.getName().toLowerCase() : "";
                                for (File inner : matchingInnerAppDirs(child, lowerApp)) {
                                    String absPath = inner.getAbsolutePath();
                                    if (offerFilesystemLeftover(absPath, leftovers, excludedPrimaries)) {
                                        leftovers.add(absPath);
                                    }
                                }
                            }
                        } else {
                            String absPath = child.getAbsolutePath();
                            if (offerFilesystemLeftover(absPath, leftovers, excludedPrimaries)) {
                                leftovers.add(absPath);
                            }
                        }
                    } else if (isPublisherMatch(child.getName(), app.getPublisher())) {
                        // B5 FIX: never descend into a link/junction target.
                        if (isLinkOrReparse(child)) continue;
                        File[] vendorChildren = child.listFiles(File::isDirectory);
                        if (vendorChildren != null) {
                            for (File vendorChild : vendorChildren) {
                                if (cancelled != null && cancelled.get()) break;
                                if (isFolderMatch(vendorChild.getName(), app.getName(), null)) {
                                    String absPath = vendorChild.getAbsolutePath();
                                    if (offerFilesystemLeftover(absPath, leftovers, excludedPrimaries)) {
                                        leftovers.add(absPath);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return leftovers;
    }

    /**
     * InstallLocation (and shared-vendor app inners) that must stay off the leftover
     * list when {@code includePrimaryLocation} is false (failed vendor uninstall).
     */
    static java.util.Set<String> excludedPrimaryLeftoverPaths(InstalledApp app) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (app == null) return out;
        String loc = app.getInstallLocation();
        if (loc == null || loc.isBlank()) return out;
        addNormalizedLeftoverPath(out, loc);
        String canon = canonicalizeForSafety(loc);
        String base = canon != null ? canon : loc.trim();
        if (isSharedVendorInstallLocation(base, app.getName(), app.getPublisher())) {
            File installDir = new File(base);
            if (installDir.isDirectory() && !isLinkOrReparse(installDir)) {
                String lowerApp = app.getName() != null ? app.getName().toLowerCase() : "";
                for (File inner : matchingInnerAppDirs(installDir, lowerApp)) {
                    addNormalizedLeftoverPath(out, inner.getAbsolutePath());
                }
            }
        }
        return out;
    }

    static boolean isExcludedPrimaryLeftover(String absPath, java.util.Set<String> excludedPrimaries) {
        if (absPath == null || absPath.isBlank() || excludedPrimaries == null || excludedPrimaries.isEmpty()) {
            return false;
        }
        String key = normalizeLeftoverPathKey(absPath);
        return key != null && excludedPrimaries.contains(key);
    }

    private static boolean offerFilesystemLeftover(String absPath, List<String> leftovers,
                                                   java.util.Set<String> excludedPrimaries) {
        if (absPath == null || absPath.isBlank()) return false;
        if (leftovers.contains(absPath)) return false;
        if (isProtectedPath(absPath)) return false;
        return !isExcludedPrimaryLeftover(absPath, excludedPrimaries);
    }

    private static void addNormalizedLeftoverPath(java.util.Set<String> out, String path) {
        String key = normalizeLeftoverPathKey(path);
        if (key != null) out.add(key);
    }

    static String normalizeLeftoverPathKey(String path) {
        if (path == null || path.isBlank()) return null;
        String c = canonicalizeForSafety(path);
        if (c == null) {
            c = path.trim().replace('/', '\\');
            while (c.endsWith("\\") && c.length() > 3) c = c.substring(0, c.length() - 1);
        }
        return c.isBlank() ? null : c.toLowerCase();
    }

    /**
     * @param includePrimaryLocation when false, the app's own installLocation is excluded
     *        from deletable results. Use this when the standard uninstaller FAILED —
     *        the live install dir must not be offered for force-deletion as that would
     *        bypass the vendor uninstaller and corrupt the install. Protected OS paths
     *        (WindowsApps, Windows, System32) are always excluded regardless.
     */
    public List<String> scanFilesystemLeftovers(InstalledApp app, boolean includePrimaryLocation) {
        return scanFilesystemLeftovers(app, includePrimaryLocation, null);
    }

    /**
     * Returns PATH warnings (non-deletable) — entries that reference the app's install location.
     * Kept separate from filesystem leftovers so they are never offered for deletion as files.
     */
    public List<String> scanPathWarnings(InstalledApp app) {
        List<String> warnings = new ArrayList<>();
        if (app.getInstallLocation() == null || app.getInstallLocation().isBlank()) return warnings;
        try {
            String lowerLoc = app.getInstallLocation().toLowerCase().trim().replaceAll("[/\\\\]+$", "");
            // Strip surrounding quotes from stored location as well
            if ((lowerLoc.startsWith("\"") && lowerLoc.endsWith("\"")) || (lowerLoc.startsWith("'") && lowerLoc.endsWith("'"))) {
                lowerLoc = lowerLoc.substring(1, lowerLoc.length() - 1);
            }
            String pathEnv = System.getenv("PATH");
            if (pathEnv != null) {
                for (String entry : pathEnv.split(";")) {
                    String trimmed = entry.trim();
                    if (trimmed.isEmpty()) continue;
                    // Strip surrounding quotes (PATH entries are sometimes quoted)
                    if ((trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() > 1)
                            || (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() > 1)) {
                        trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
                    }
                    String lowerEntry = trimmed.toLowerCase().replaceAll("[/\\\\]+$", "");
                    boolean matches = lowerEntry.equals(lowerLoc)
                            || lowerEntry.startsWith(lowerLoc + "\\")
                            || lowerEntry.startsWith(lowerLoc + "/");
                    if (matches) {
                        String warning = "PATH entry references app: " + trimmed;
                        if (!warnings.contains(warning)) {
                            warnings.add(warning);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return warnings;
    }

    /**
     * Scans Registry SOFTWARE keys (HKLM, HKLM-Wow6432, HKCU) and HKCR for remnants.
     */
    public List<String> scanRegistryLeftovers(InstalledApp app) {
        return scanRegistryLeftovers(app, null);
    }

    /**
     * Cancellable registry scan. Checks {@code cancelled} between hives/branches.
     */
    public List<String> scanRegistryLeftovers(InstalledApp app,
                                              java.util.concurrent.atomic.AtomicBoolean cancelled) {
        List<String> leftovers = new ArrayList<>();

        // Add the primary uninstaller registry key itself if it exists (for Win32 apps)
        if (app.isWin32() && !app.getRegistryKeyPath().isEmpty()) {
            HKEY hive = "HKLM".equalsIgnoreCase(app.getRegistryHive()) ? WinReg.HKEY_LOCAL_MACHINE : WinReg.HKEY_CURRENT_USER;
            try {
                if (Advapi32Util.registryKeyExists(hive, app.getRegistryKeyPath())) {
                    if (!isProtectedRegistryPath(app.getRegistryHive(), app.getRegistryKeyPath())) {
                        leftovers.add(app.getRegistryHive() + "\\" + app.getRegistryKeyPath());
                    }
                }
            } catch (Exception ignored) {}
        }
        if (cancelled != null && cancelled.get()) return leftovers;

        // Search in Software paths only — do NOT scan SYSTEM\CurrentControlSet\Services
        // because substring matching on service names can flag legitimate Windows services
        scanRegistryForLeftovers(WinReg.HKEY_LOCAL_MACHINE, "HKLM", "SOFTWARE", app.getName(), app.getPublisher(), leftovers, cancelled);
        if (cancelled != null && cancelled.get()) return leftovers;
        scanRegistryForLeftovers(WinReg.HKEY_LOCAL_MACHINE, "HKLM", "SOFTWARE\\Wow6432Node", app.getName(), app.getPublisher(), leftovers, cancelled);
        if (cancelled != null && cancelled.get()) return leftovers;
        scanRegistryForLeftovers(WinReg.HKEY_CURRENT_USER, "HKCU", "SOFTWARE", app.getName(), app.getPublisher(), leftovers, cancelled);

        if (cancelled != null && cancelled.get()) return leftovers;
        // Scan HKCR for file association entries
        scanHkcrForLeftovers(app.getName(), app.getPublisher(), leftovers, cancelled);

        return leftovers;
    }

    /**
     * Scans HKEY_CLASSES_ROOT for file association entries matching the app name or publisher.
     * Uses stricter thresholds (>=5 chars, word-boundary) and correctly filters top-level CLSID.
     */
    private void scanHkcrForLeftovers(String appName, String publisher, List<String> leftovers) {
        scanHkcrForLeftovers(appName, publisher, leftovers, null);
    }

    private void scanHkcrForLeftovers(String appName, String publisher, List<String> leftovers,
                                      java.util.concurrent.atomic.AtomicBoolean cancelled) {
        try {
            if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT, "")) {
                return;
            }
            String[] subkeys = Advapi32Util.registryGetKeys(WinReg.HKEY_CLASSES_ROOT, "");
            if (subkeys == null) return;

            String lowerName = appName != null ? appName.toLowerCase().trim() : "";
            if (lowerName.length() < 5) return;

            for (String subkey : subkeys) {
                if (cancelled != null && cancelled.get()) return;
                // Skip very long keys (COM CLSIDs, etc.) — top-level keys are flat, e.g. "CLSID" not "CLSID\..."
                if (subkey.length() > 80) continue;
                String lowerSub = subkey.toLowerCase();
                if (lowerSub.equals("clsid") || lowerSub.equals("wow6432node") || lowerSub.startsWith("clsid\\") || lowerSub.startsWith("wow6432node\\")) {
                    continue;
                }
                // Skip generic HKCR entries like file extensions and type libs
                if (isGenericName(lowerSub)) continue;
                String lowerKey = lowerSub;
                boolean nameMatch = lowerName.length() >= 5 && isAppSpecificInnerName(lowerKey, lowerName);
                if (nameMatch) {
                    String path = "HKCR\\" + subkey;
                    if (!leftovers.contains(path) && !isProtectedRegistryPath("HKCR", subkey)) {
                        leftovers.add(path);
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.debug("HKCR scan failed: " + e.getMessage());
        }
    }

    private void scanRegistryForLeftovers(HKEY hive, String hiveLabel, String rootPath, String appName, String publisher, List<String> leftovers) {
        scanRegistryForLeftovers(hive, hiveLabel, rootPath, appName, publisher, leftovers, null);
    }

    private void scanRegistryForLeftovers(HKEY hive, String hiveLabel, String rootPath, String appName,
                                          String publisher, List<String> leftovers,
                                          java.util.concurrent.atomic.AtomicBoolean cancelled) {
        try {
            if (!Advapi32Util.registryKeyExists(hive, rootPath)) {
                return;
            }
            String[] subkeys = Advapi32Util.registryGetKeys(hive, rootPath);
            if (subkeys == null) {
                return;
            }

            for (String subkey : subkeys) {
                if (cancelled != null && cancelled.get()) return;
                String fullPath = rootPath + "\\" + subkey;

                // Check if the subkey matches publisher name or app name
                if (isRegistryKeyMatch(subkey, appName, publisher)) {
                    String formattedPath = hiveLabel + "\\" + fullPath;
                    if (!leftovers.contains(formattedPath)
                            && !isProtectedRegistryPath(hiveLabel, fullPath)) {
                        leftovers.add(formattedPath);
                    }
                } else {
                    // Check if publisher folder (e.g. SOFTWARE\PublisherName) and check inside it
                    if (isPublisherMatch(subkey, publisher)) {
                        try {
                            String[] innerKeys = Advapi32Util.registryGetKeys(hive, fullPath);
                            if (innerKeys != null) {
                                for (String innerKey : innerKeys) {
                                    if (cancelled != null && cancelled.get()) return;
                                    if (isRegistryKeyMatch(innerKey, appName, null)) {
                                        String formattedPath = hiveLabel + "\\" + fullPath + "\\" + innerKey;
                                        if (!leftovers.contains(formattedPath)
                                                && !isProtectedRegistryPath(hiveLabel, fullPath + "\\" + innerKey)) {
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

    /**
     * Resolves {@code ..}, strips {@code \\?\} / {@code \\.\} prefixes, and returns
     * an absolute normalized path. Null/blank/unresolvable → null (callers must refuse).
     */
    public static String canonicalizeForSafety(String path) {
        if (path == null || path.isBlank()) return null;
        String p = path.trim().replace('/', '\\');
        if (p.length() > 1 && ((p.startsWith("\"") && p.endsWith("\""))
                || (p.startsWith("'") && p.endsWith("'")))) {
            p = p.substring(1, p.length() - 1).trim();
        }
        if (p.regionMatches(true, 0, "\\\\?\\UNC\\", 0, 8)) {
            p = "\\\\" + p.substring(8);
        } else if (p.startsWith("\\\\?\\") || p.startsWith("\\\\.\\")) {
            p = p.substring(4);
        }
        // Admin shares (\\localhost\C$\Windows) resolve to the real drive. Map
        // them before normalize so isProtectedPath sees C:\Windows, not a UNC
        // string that skips the SystemRoot prefix check.
        if (p.startsWith("\\\\")) {
            java.util.regex.Matcher share = java.util.regex.Pattern
                    .compile("(?i)^\\\\\\\\[^\\\\]+\\\\([a-z])\\$(?:\\\\(.*))?$")
                    .matcher(p);
            if (share.matches()) {
                String rest = share.group(2);
                p = share.group(1) + ":\\" + (rest == null ? "" : rest);
            }
        }
        if (p.isBlank()) return null;
        try {
            java.nio.file.Path nio = java.nio.file.Path.of(p).normalize();
            java.nio.file.Path abs = nio.isAbsolute() ? nio : nio.toAbsolutePath().normalize();
            try {
                if (java.nio.file.Files.exists(abs, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    // toRealPath follows Windows junctions (MOUNT_POINT), which are
                    // not Java symbolic links. Resolving a Program Files junction
                    // into a user profile would offer the TARGET for deletion.
                    Boolean link = NativeFileHelper.isLinkOrReparse(abs);
                    if (Boolean.FALSE.equals(link)) {
                        abs = abs.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    }
                }
            } catch (Exception ignored) {
                // keep normalized absolute
            }
            String out = abs.toString().replace('/', '\\');
            while (out.endsWith("\\") && out.length() > 3) out = out.substring(0, out.length() - 1);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Last path segment after canonicalization (empty if the path is unusable). */
    public static String pathLeaf(String path) {
        String p = canonicalizeForSafety(path);
        if (p == null || p.isBlank()) {
            if (path == null) return "";
            p = path.trim().replace('/', '\\');
            while (p.endsWith("\\") && p.length() > 3) p = p.substring(0, p.length() - 1);
        }
        int slash = Math.max(p.lastIndexOf('\\'), p.lastIndexOf('/'));
        return slash >= 0 && slash + 1 < p.length() ? p.substring(slash + 1) : p;
    }

    /**
     * True when {@code InstallLocation} is a shared vendor root (e.g. {@code ...\Adobe}
     * for "Adobe Acrobat") rather than an app-specific folder. Force-uninstall must
     * not recursively delete these.
     */
    public static boolean isSharedVendorInstallLocation(String path, String appName, String publisher) {
        if (path == null || path.isBlank()) return false;
        String leaf = pathLeaf(path);
        if (leaf.isBlank()) return false;
        if (isExactAppNameMatch(leaf, appName)) {
            // "Opera" at ...\Opera can still host Opera GX as a sibling subfolder.
            return looksLikeSharedVendorDir(path, appName);
        }
        String a = appName == null ? "" : appName.trim();
        String p = publisher == null ? "" : publisher.trim();
        String ll = leaf.toLowerCase();
        String al = a.toLowerCase();
        String pl = p.toLowerCase();
        if (!al.isEmpty() && (al.startsWith(ll + " ") || al.startsWith(ll + "-"))) {
            // Vendor root ("...\Adobe" for "Adobe Acrobat") only when a product
            // subfolder exists. "...\VLC" for "VLC media player" is the app
            // directory itself and must stay deletable.
            return hasAppSpecificInnerDir(path, a);
        }
        if (pl.isEmpty()) return false;
        return ll.equals(pl)
                || (pl.length() >= 4 && containsWordBoundary(pl, ll))
                || (ll.length() >= 4 && containsWordBoundary(ll, pl));
    }

    /**
     * True when a directory hosts another product SKU beside this app
     * ({@code ...\Opera\Opera GX} while removing Opera). Content folders
     * ({@code Lang}, {@code locale}) are not sibling products — an install
     * directory named exactly like the app must still be removable.
     */
    public static boolean looksLikeSharedVendorDir(String path, String appName) {
        if (path == null || path.isBlank()) return false;
        try {
            File dir = new File(path);
            if (!Boolean.FALSE.equals(NativeFileHelper.isLinkOrReparse(dir.toPath()))) return true;
            if (!dir.isDirectory()) return false;
            File[] innerDirs = dir.listFiles(File::isDirectory);
            if (innerDirs == null) return true;
            String lowerApp = appName == null ? "" : appName.toLowerCase().trim();
            for (File inner : innerDirs) {
                if (isLinkOrReparse(inner)) continue;
                String n = inner.getName().toLowerCase().trim();
                if (isSiblingProductDir(n, lowerApp)) return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Vendor-prefix folder ({@code ...\Adobe} for "Adobe Acrobat") that contains
     * an app-specific product directory. No such inner means the prefix folder
     * is the app itself ({@code ...\VLC} for "VLC media player").
     */
    static boolean hasAppSpecificInnerDir(String path, String appName) {
        try {
            File dir = new File(path);
            if (!dir.isDirectory()) return false;
            if (!Boolean.FALSE.equals(NativeFileHelper.isLinkOrReparse(dir.toPath()))) return true;
            if (dir.listFiles(File::isDirectory) == null) return true;
            String lowerApp = appName == null ? "" : appName.toLowerCase().trim();
            return !matchingInnerAppDirs(dir, lowerApp).isEmpty();
        } catch (Exception e) {
            return true;
        }
    }

    /** "Opera GX" inside "...\Opera" for app "Opera". Version suffixes are this app. */
    public static boolean isSiblingProductDir(String innerName, String appName) {
        if (innerName == null || appName == null) return false;
        String inner = innerName.toLowerCase().trim();
        String app = appName.toLowerCase().trim();
        if (inner.isEmpty() || app.isEmpty() || isGenericName(inner)) return false;
        if (!(inner.startsWith(app + " ") || inner.startsWith(app + "-"))) return false;
        return !isAppSpecificInnerName(inner, app);
    }

    /**
     * True for OS-protected locations that must never be deleted directly:
     * WindowsApps (Store packages — remove via Remove-AppxPackage only),
     * Windows / System32 / SysWOW64, and bare drive roots. Also guards
     * against offering a scan root itself (e.g. Program Files) for deletion.
     * BLOCKER HARDENING: user-data roots (profile, Documents, Downloads,
     * Desktop, ...) and any C:\Users profile root are protected too — a sloppy
     * InstallLocation pointing at them must never be offered, because
     * "Select All" in the leftover review would otherwise wipe user data.
     * Paths are canonicalized first so {@code ..} / {@code \\?\} cannot skip the
     * Windows/System32 prefix checks.
     */
    public static boolean isProtectedPath(String path) {
        if (path == null || path.isBlank()) return true;
        String canon = canonicalizeForSafety(path);
        if (canon == null || canon.isBlank()) return true;
        String p = canon;
        String lower = p.toLowerCase();
        // Bare drive root (C:\, C:) — never deletable
        if (lower.matches("^[a-z]:\\\\?$")) return true;
        // Any user profile root (C:\Users\<name>) hosts a whole account — never deletable.
        // C:\Users itself is also covered by the generic-leaf rule ("users"), but keep
        // the explicit pattern so it stays protected even if leaf lists change.
        if (lower.matches("^[a-z]:\\\\users\\\\[^\\\\]+\\\\?$")) return true;
        if (lower.matches("^[a-z]:\\\\users\\\\?$")) return true;
        if (lower.contains("\\windowsapps\\") || lower.endsWith("\\windowsapps")) return true;
        String windir = System.getenv("SystemRoot");
        if (windir == null) windir = System.getenv("WINDIR");
        if (windir == null) windir = "C:\\Windows";
        String canonWin = canonicalizeForSafety(windir);
        String lowerWin = (canonWin != null ? canonWin : windir).toLowerCase().replace('/', '\\');
        while (lowerWin.endsWith("\\")) lowerWin = lowerWin.substring(0, lowerWin.length() - 1);
        if (lower.equals(lowerWin) || lower.startsWith(lowerWin + "\\")) return true;
        // Never offer a known scan root itself (exact match). Covers all roots
        // used by scanFilesystemLeftovers / forceUninstall so a sloppy
        // InstallLocation pointing at a shared root is never wiped.
        java.util.List<String> roots = new java.util.ArrayList<>();
        roots.add(System.getenv("ProgramFiles"));
        roots.add(System.getenv("ProgramFiles(x86)"));
        roots.add(System.getenv("CommonProgramFiles"));
        roots.add(System.getenv("CommonProgramFiles(x86)"));
        roots.add(System.getenv("ProgramData"));
        roots.add(System.getenv("AppData"));
        roots.add(System.getenv("LocalAppData"));
        roots.add(System.getenv("USERPROFILE"));
        roots.add(System.getenv("PUBLIC"));
        String localAppData = System.getenv("LocalAppData");
        String appData = System.getenv("AppData");
        String userProfile = System.getenv("USERPROFILE");
        String publicDir = System.getenv("PUBLIC");
        if (localAppData != null) roots.add(localAppData + "\\Programs");
        if (appData != null) roots.add(appData + "\\LocalLow");
        if (publicDir != null) roots.add(publicDir + "\\Documents");
        if (userProfile != null) roots.add(userProfile + "\\Desktop");
        if (appData != null) roots.add(appData + "\\Microsoft\\Internet Explorer\\Quick Launch");
        // User shell folders must never be offered even when InstallLocation points at them.
        if (userProfile != null) {
            roots.add(userProfile + "\\Documents");
            roots.add(userProfile + "\\Downloads");
            roots.add(userProfile + "\\Pictures");
            roots.add(userProfile + "\\Music");
            roots.add(userProfile + "\\Videos");
            roots.add(userProfile + "\\Favorites");
            roots.add(userProfile + "\\Contacts");
            roots.add(userProfile + "\\Searches");
            roots.add(userProfile + "\\Links");
            roots.add(userProfile + "\\Saved Games");
            roots.add(userProfile + "\\OneDrive");
        }
        if (publicDir != null) {
            publicDir = publicDir.trim();
            roots.add(publicDir + "\\Desktop");
            roots.add(publicDir + "\\Downloads");
            roots.add(publicDir + "\\Pictures");
            roots.add(publicDir + "\\Music");
            roots.add(publicDir + "\\Videos");
            roots.add(publicDir + "\\Favorites");
        }
        for (String r : roots) {
            if (r == null || r.isBlank()) continue;
            String lr = r.trim().replace('/', '\\').toLowerCase();
            while (lr.endsWith("\\")) lr = lr.substring(0, lr.length() - 1);
            if (lower.equals(lr)) return true;
        }
        // Shared/generic leaf (e.g. "Common Files", "Common", "Program Files")
        // must never be deleted directly — it hosts many vendors. Only
        // app-specific subfolders may be offered (via vendor-inner scan).
        String leaf = lower;
        int slash = Math.max(leaf.lastIndexOf('\\'), leaf.lastIndexOf('/'));
        if (slash >= 0 && slash + 1 < leaf.length()) leaf = leaf.substring(slash + 1).trim();
        if (isGenericName(leaf)) return true;
        return false;
    }

    /**
     * Refuse leftover-delete of hive roots and first-level vendor keys
     * ({@code HKLM\SOFTWARE}, {@code HKLM\SOFTWARE\Adobe}). App-specific
     * inner keys remain eligible. HKCR ProgIds are single-segment and allowed
     * unless the name is a well-known COM/OS bucket.
     */
    public static boolean isProtectedRegistryPath(String hiveStr, String keyPath) {
        if (keyPath == null || keyPath.isBlank()) return true;
        String k = keyPath.replace('/', '\\').trim();
        while (k.endsWith("\\") && k.length() > 1) k = k.substring(0, k.length() - 1);
        if (k.isEmpty()) return true;
        String lower = k.toLowerCase();
        if (lower.equals("clsid") || lower.equals("wow6432node") || lower.equals("typelib")
                || lower.equals("interface") || lower.equals("appid")
                || lower.equals("software") || lower.equals("software\\wow6432node")) {
            return true;
        }
        String hive = hiveStr == null ? "" : hiveStr.trim().toUpperCase();
        if ("HKCR".equals(hive)) {
            return isGenericName(lower);
        }
        // HKLM/HKCU: SOFTWARE\<vendor> is a shared tree — require a deeper app key.
        if (lower.matches("software\\\\wow6432node\\\\[^\\\\]+")
                || lower.matches("software\\\\[^\\\\]+")) {
            return true;
        }
        String leaf = lower;
        int slash = leaf.lastIndexOf('\\');
        if (slash >= 0 && slash + 1 < leaf.length()) leaf = leaf.substring(slash + 1);
        return isGenericName(leaf);
    }

    private boolean isFolderMatch(String folderName, String appName, String publisher) {
        if (folderName == null || folderName.isBlank()) return false;
        String fName = folderName.toLowerCase().trim();
        String aName = appName != null ? appName.toLowerCase().trim() : "";

        if (aName.isEmpty()) return false;
        if (isGenericName(fName)) return false;
        return isAppSpecificInnerName(fName, aName);
    }

    private boolean isRegistryKeyMatch(String keyName, String appName, String publisher) {
        if (keyName == null || keyName.isBlank()) return false;
        String kName = keyName.toLowerCase().trim();
        String aName = appName != null ? appName.toLowerCase().trim() : "";

        if (aName.isEmpty()) return false;
        if (isGenericName(kName)) return false;
        return isAppSpecificInnerName(kName, aName);
    }

    private boolean isPublisherMatch(String keyName, String publisher) {
        if (keyName == null || keyName.isBlank() || publisher == null || publisher.isBlank()) return false;
        String kName = keyName.toLowerCase().trim();
        String pName = publisher.toLowerCase().trim();

        if (pName.length() < 4) return false;
        if (isGenericName(kName)) return false;
        return kName.equals(pName) || containsWordBoundary(kName, pName) || containsWordBoundary(pName, kName);
    }

    private static boolean isGenericName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase().trim();
        String[] generic = {
                "software", "program", "app", "application", "microsoft", "windows",
                "common", "common files", "common files (x86)", "temp", "local", "roaming", "data", "uninstall", "uninstaller", "utilities",
                "tool", "tools", "driver", "drivers", "update", "updates", "config", "cache", "logs", "log", "packages", "package",
                "resources", "resource", "share", "lib", "bin", "src", "include", "plugin", "plugins",
                "extension", "extensions", "module", "modules", "system", "services", "service", "startup",
                "utility", "utilities", "installer", "setup", "helper", "program files", "program files (x86)", "programdata", "common file",
                "library", "libraries", "driverstore",
                // BLOCKER HARDENING: user-data + system leaves that must never be
                // offered for deletion (Select All would wipe them) nor matched
                // as leftover folders/keys. App-specific SUBfolders remain eligible.
                "users", "user", "documents", "my documents", "downloads", "desktop",
                "pictures", "my pictures", "music", "my music", "videos", "my videos",
                "favorites", "contacts", "searches", "links", "saved games", "onedrive",
                "dropbox", "appdata", "application data", "local settings",
                "nethood", "printhood", "sendto", "start menu", "programs", "templates",
                "recent", "cookies", "history", "temporary internet files",
                "perflogs", "recovery", "config.msi", "$recycle.bin",
                "system volume information", "msocache", "intel", "amd", "dell", "hp"
        };
        for (String gen : generic) {
            if (n.equals(gen)) return true;
        }
        return false;
    }

    /**
     * Names that must never be used for name-based Stop-Process. Path-based
     * kills still target only processes whose executable lives under the
     * app's install dir (and skip our own PID).
     */
    static boolean isProtectedProcessName(String name) {
        if (name == null || name.isBlank()) return true;
        String n = name.toLowerCase().trim();
        if (n.endsWith(".exe")) n = n.substring(0, n.length() - 4);
        return switch (n) {
            case "explorer", "svchost", "csrss", "lsass", "winlogon", "wininit",
                 "services", "smss", "dwm", "system", "registry", "taskmgr",
                 "conhost", "dllhost", "rundll32", "msiexec", "java", "javaw",
                 "javaws", "winzenith", "runtimebroker", "searchhost",
                 "shellexperiencehost", "sihost", "taskhostw", "spoolsv",
                 "fontdrvhost", "applicationframehost", "startmenuexperiencehost",
                 "textinputhost", "ctfmon", "securityhealthservice", "msmpeng",
                 "smartscreen", "lsaiso", "userinit", "logonui", "searchapp",
                 "systemsettings", "winstore.app" -> true;
            default -> false;
        };
    }

    /**
     * Rescan FIX (B5): true for symlinks/junctions without following them.
     * Used to avoid descending into link targets during scans and recursion —
     * a matching link itself may be offered (deleted as a link by
     * NativeFileHelper), but we never enumerate a link's target.
     */
    private static boolean isLinkOrReparse(java.io.File f) {
        if (f == null) return true;
        try {
            Boolean v = NativeFileHelper.isLinkOrReparse(f.toPath());
            // Unknown (null) must not be descended — same as a link.
            return !Boolean.FALSE.equals(v);
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Returns true if the given folder/registry key name is an exact (case-insensitive)
     * match for the app name — high confidence. Publisher-only matches are NOT
     * high-confidence (shared vendor roots host sibling apps) and must default to
     * unselected. Word-boundary substring matches are heuristic (lower confidence).
     */
    public static boolean isExactMatch(String leafName, InstalledApp app) {
        if (leafName == null || app == null) return false;
        String leaf = leafName.toLowerCase().trim();
        String aName = app.getName() != null ? app.getName().toLowerCase().trim() : "";
        // BLOCKER FIX (B1): app-name only. Publisher-exact (e.g. leaf "Adobe" vs
        // publisher "Adobe") previously pre-selected shared vendor roots for deletion.
        return !aName.isEmpty() && leaf.equals(aName);
    }

    /**
     * App-name exact match helper used where publisher must never count
     * (primary-location confidence, vendor-root guards).
     */
    public static boolean isExactAppNameMatch(String leafName, String appName) {
        if (leafName == null || appName == null) return false;
        String leaf = leafName.toLowerCase().trim();
        String aName = appName.toLowerCase().trim();
        return !aName.isEmpty() && leaf.equals(aName);
    }

    /**
     * True when a folder/key/shortcut leaf belongs to this app rather than a
     * sibling product. Exact match, reverse word match (folder "Acrobat" for
     * "Adobe Acrobat"), or app-name prefix plus a version/channel suffix
     * ("IntelliJ IDEA 2024.1"). Forward substring is refused so "Opera GX"
     * is never treated as "Opera".
     */
    public static boolean isAppSpecificInnerName(String innerName, String appName) {
        if (innerName == null || appName == null) return false;
        String inner = innerName.toLowerCase().trim();
        String app = appName.toLowerCase().trim();
        if (inner.isEmpty() || app.isEmpty() || isGenericName(inner)) return false;
        if (inner.equals(app)) return true;
        // Reverse match only the trailing product token ("Acrobat" for "Adobe Acrobat",
        // "Chrome" for "Google Chrome"). A middle word ("Studio" in "Visual Studio Code")
        // would offer unrelated folders as leftovers / force-delete targets.
        if (inner.length() >= 5
                && (app.endsWith(" " + inner) || app.endsWith("-" + inner))) {
            return true;
        }
        if (inner.startsWith(app + " ")) return isVersionOrChannelSuffix(inner.substring(app.length() + 1));
        if (inner.startsWith(app + "-")) return isVersionOrChannelSuffix(inner.substring(app.length() + 1));
        return false;
    }

    /**
     * Start Menu leaf match: exact name, or the same name plus a version/arch
     * suffix. The reverse last-word rule ("Player" for "VLC media player")
     * belongs on vendor subfolders, not shortcuts — it deletes unrelated folders.
     */
    public static boolean isStartMenuLeafMatch(String leafName, String appName) {
        if (leafName == null || appName == null) return false;
        String inner = leafName.toLowerCase().trim();
        String app = appName.toLowerCase().trim();
        if (inner.isEmpty() || app.isEmpty() || isGenericName(inner)) return false;
        if (inner.equals(app)) return true;
        if (inner.startsWith(app + " ")) return isVersionOrChannelSuffix(inner.substring(app.length() + 1));
        if (inner.startsWith(app + "-")) return isVersionOrChannelSuffix(inner.substring(app.length() + 1));
        return false;
    }

    /**
     * Same product with a version/arch suffix ("IntelliJ IDEA 2024.1", "App x64").
     * Channel/SKU names (Beta, Nightly, Pro, …) are separate installs and must not match.
     * {@code DC} is Acrobat's in-product edition, not a sibling brand.
     */
    static boolean isVersionOrChannelSuffix(String remainder) {
        if (remainder == null) return false;
        String r = remainder.trim().toLowerCase();
        if (r.isEmpty()) return true;
        return r.matches("^(v|ver|version)?[\\d._].*")
                || r.matches("^(x64|x86|win32|win64|64.?bit|32.?bit)\\b.*")
                || r.matches("^dc\\b.*");
    }

    /**
     * Deletes a list of files or folders and logs failures (which are scheduled for reboot deletion).
     *
     * @param paths List of absolute folder or file paths.
     * @param failedDeletions Output list to append paths that could not be deleted immediately (e.g. locked).
     */
    public void deleteFilesystemLeftovers(List<String> paths, List<String> failedDeletions) {
        deleteFilesystemLeftovers(paths, failedDeletions, null, false);
    }

    /**
     * Recycle-aware variant. When {@code preferRecycle} is true, items are moved to
     * the Recycle Bin first (recoverable); locked items fall back to reboot queue.
     * {@code recycled} (nullable) collects paths that were recycled for summary UI.
     */
    public FilesystemCleanupResult deleteFilesystemLeftovers(List<String> paths, List<String> failedDeletions,
                                          List<String> recycled, boolean preferRecycle) {
        return deleteFilesystemLeftovers(paths, failedDeletions, recycled, preferRecycle, null);
    }

    public FilesystemCleanupResult deleteFilesystemLeftovers(List<String> paths, List<String> failedDeletions,
                                          List<String> recycled, boolean preferRecycle,
                                          AtomicBoolean cancelled) {
        int deleted = 0, recycledCount = 0, absent = 0, queued = 0, failed = 0;
        if (paths == null) {
            return new FilesystemCleanupResult(0, 0, 0, 0, 0, List.of());
        }
        for (String pathStr : paths) {
            if (pathStr == null || pathStr.isBlank()) continue;
            if (cancelled(cancelled)) {
                if (failedDeletions != null) failedDeletions.add(pathStr + " (cancelled — not deleted)");
                failed++;
                continue;
            }
            String canon = canonicalizeForSafety(pathStr);
            if (canon == null || isProtectedPath(pathStr)) {
                AppLogger.warning("Refused to delete protected path: " + pathStr);
                String msg = pathStr + " (protected — skipped)";
                if (failedDeletions != null) failedDeletions.add(msg);
                failed++;
                continue;
            }
            File file = new File(canon);
            if (!file.exists()) {
                absent++;
                continue;
            }
            if (preferRecycle) {
                NativeFileHelper.DeleteOutcome outcome =
                        NativeFileHelper.deleteWithOutcome(file, true, cancelled);
                if (outcome == NativeFileHelper.DeleteOutcome.RECYCLED) {
                    recycledCount++;
                    if (recycled != null) recycled.add(pathStr);
                } else if (outcome == NativeFileHelper.DeleteOutcome.DELETED) {
                    deleted++;
                } else if (outcome == NativeFileHelper.DeleteOutcome.QUEUED_FOR_REBOOT) {
                    queued++;
                    if (failedDeletions != null) failedDeletions.add(pathStr + " (scheduled for reboot)");
                } else {
                    failed++;
                    if (failedDeletions != null) failedDeletions.add(pathStr);
                }
            } else {
                NativeFileHelper.DeleteOutcome outcome = NativeFileHelper.deleteOrQueueWithOutcome(file, cancelled);
                if (outcome == NativeFileHelper.DeleteOutcome.DELETED) {
                    deleted++;
                } else if (outcome == NativeFileHelper.DeleteOutcome.QUEUED_FOR_REBOOT) {
                    queued++;
                    if (failedDeletions != null) failedDeletions.add(pathStr + " (scheduled for reboot)");
                } else {
                    failed++;
                    if (failedDeletions != null) failedDeletions.add(pathStr);
                }
            }
        }
        return new FilesystemCleanupResult(deleted, recycledCount, absent, queued, failed, failedDeletions == null ? List.of() : List.copyOf(failedDeletions));
    }

    /**
     * Best-effort safety backup: exports each selected registry key via
     * {@code reg export <key> <file> /y} into {@code backupDir}. Mirrors the
     * Backup tab pattern. Never throws; returns files that were written.
     */
    public RegistryBackupResult exportRegistryKeysForBackup(List<String> registryPaths, Path backupDir) {
        java.util.Map<String, Path> exportedByKey = new java.util.LinkedHashMap<>();
        java.util.List<String> failedKeys = new ArrayList<>();
        if (registryPaths == null || registryPaths.isEmpty()) {
            return new RegistryBackupResult(exportedByKey, failedKeys);
        }
        try {
            java.nio.file.Files.createDirectories(backupDir);
        } catch (Exception e) {
            AppLogger.warning("Could not create registry backup dir: " + e.getMessage());
            for (String fullPath : registryPaths) {
                if (fullPath != null && !fullPath.isBlank()) failedKeys.add(fullPath);
            }
            return new RegistryBackupResult(exportedByKey, failedKeys);
        }
        int idx = 0;
        for (String fullPath : registryPaths) {
            if (fullPath == null || fullPath.isBlank() || !fullPath.contains("\\")) {
                if (fullPath != null && !fullPath.isBlank()) failedKeys.add(fullPath);
                continue;
            }
            try {
                int sep = fullPath.indexOf('\\');
                if (sep > 0 && isProtectedRegistryPath(fullPath.substring(0, sep), fullPath.substring(sep + 1))) {
                    AppLogger.warning("Refused to export protected registry key: " + fullPath);
                    failedKeys.add(fullPath);
                    continue;
                }
                String safe = fullPath.replace('\\', '_').replace('/', '_')
                        .replace(':', '_').replaceAll("[^A-Za-z0-9_\\-\\.]+", "_");
                if (safe.length() > 80) safe = safe.substring(0, 80);
                Path out = backupDir.resolve(String.format("%03d_%s.reg", idx++, safe));
                ProcessBuilder pb = new ProcessBuilder(
                        "reg", "export", fullPath, out.toString(), "/y");
                pb.redirectErrorStream(true);
                Process p = com.sbtools.util.ProcessManager.start(pb);
                boolean done = p.waitFor(60, TimeUnit.SECONDS);
                if (!done) {
                    p.destroyForcibly();
                    AppLogger.warning("reg export timed out for " + fullPath);
                    failedKeys.add(fullPath);
                } else if (p.exitValue() == 0 && java.nio.file.Files.exists(out)) {
                    exportedByKey.put(fullPath, out);
                } else {
                    AppLogger.warning("reg export failed for " + fullPath
                            + " (exit=" + p.exitValue() + ")");
                    failedKeys.add(fullPath);
                }
            } catch (Exception e) {
                AppLogger.warning("reg export error for " + fullPath + ": " + e.getMessage());
                failedKeys.add(fullPath);
            }
        }
        AppLogger.info("Registry pre-delete backup: " + exportedByKey.size()
                + "/" + registryPaths.size() + " exported to " + backupDir);
        return new RegistryBackupResult(exportedByKey, failedKeys);
    }

    /**
     * Computes the on-disk size of a file/folder (best effort, capped traversal).
     * Returns -1 when the size cannot be determined.
     */
    public static long computePathSizeBytes(String absPath) {
        if (absPath == null || absPath.isBlank()) return -1;
        try {
            File f = new File(absPath);
            if (!f.exists()) return -1;
            if (f.isFile()) return f.length();
            return sizeTreeNoFollow(f.toPath(), null, 20000);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Directory size that never descends into junctions/symlinks. {@code Files.walk}
     * follows Windows mount points, so leftover sizing of a folder with an AppData
     * junction would otherwise traverse the user profile (and hold those handles).
     */
    private static long sizeTreeNoFollow(java.nio.file.Path start, AtomicBoolean cancelled, int maxFiles) {
        if (start == null) return -1;
        if (!Boolean.FALSE.equals(NativeFileHelper.isLinkOrReparse(start))) return 0;
        final long[] total = {0};
        final int[] count = {0};
        try {
            java.nio.file.Files.walkFileTree(start,
                    java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 32,
                    new java.nio.file.SimpleFileVisitor<>() {
                        @Override
                        public java.nio.file.FileVisitResult preVisitDirectory(java.nio.file.Path dir,
                                java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (cancelled != null && cancelled.get()) {
                                return java.nio.file.FileVisitResult.TERMINATE;
                            }
                            if (!dir.equals(start)
                                    && !Boolean.FALSE.equals(NativeFileHelper.isLinkOrReparse(dir))) {
                                return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                                java.nio.file.attribute.BasicFileAttributes attrs) {
                            if (cancelled != null && cancelled.get()) {
                                return java.nio.file.FileVisitResult.TERMINATE;
                            }
                            if (count[0] >= maxFiles) return java.nio.file.FileVisitResult.TERMINATE;
                            try {
                                if (!Boolean.FALSE.equals(NativeFileHelper.isLinkOrReparse(file))) {
                                    return java.nio.file.FileVisitResult.CONTINUE;
                                }
                                if (attrs.isRegularFile()) {
                                    total[0] += attrs.size();
                                    count[0]++;
                                }
                            } catch (Exception ignored) {}
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }

                        @Override
                        public java.nio.file.FileVisitResult visitFileFailed(java.nio.file.Path file,
                                java.io.IOException exc) {
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
        } catch (Exception e) {
            return total[0] > 0 ? total[0] : -1;
        }
        return total[0];
    }

    /**
     * Winget fallback for Win32 entries without an uninstall command.
     * Never auto-runs: caller must have explicit user consent.
     */
    public ProcessResult tryWingetUninstall(InstalledApp app, long timeoutSeconds)
            throws IOException, InterruptedException {
        return tryWingetUninstall(app, timeoutSeconds, false);
    }

    /**
     * Winget fallback for Win32 entries without an uninstall command.
     * Never auto-runs: caller must have explicit user consent.
     * {@code preferQuiet} is the only path that adds {@code --silent}.
     */
    public ProcessResult tryWingetUninstall(InstalledApp app, long timeoutSeconds, boolean preferQuiet)
            throws IOException, InterruptedException {
        return tryWingetUninstall(app, timeoutSeconds, preferQuiet, null);
    }

    public ProcessResult tryWingetUninstall(InstalledApp app, long timeoutSeconds, boolean preferQuiet,
                                            AtomicBoolean cancelled)
            throws IOException, InterruptedException {
        com.sbtools.software.WingetRunner winget = new com.sbtools.software.WingetRunner();
        if (!winget.isAvailable()) {
            throw new IOException("winget is not available on this system.");
        }
        String query = app.getName() != null ? app.getName().trim() : "";
        if (query.isEmpty()) throw new IOException("No app name for winget lookup.");
        // B3 FIX: app name is registry-controlled. cmd.exe /c and powershell -Command
        // fallbacks re-parse metachars (& | > < ^ ; ` $ ") even when passed as separate
        // argv elements. Reject them so a crafted DisplayName cannot inject commands
        // when the direct winget path is unavailable and a shell fallback runs.
        if (query.length() > 128 || query.matches(".*[\\r\\n&|><\\^;`$\"].*")) {
            throw new IOException("Unsafe app name for winget fallback (shell metacharacters rejected).");
        }
        java.util.List<String> args = new ArrayList<>();
        args.add("uninstall");
        args.add("--exact");
        if (preferQuiet) args.add("--silent");
        args.add("--accept-source-agreements");
        args.add("--accept-package-agreements");
        args.add("--name");
        args.add(query);
        ProcessResult r = winget.runWithFallback(timeoutSeconds, cancelled, args.toArray(String[]::new));
        if (r == null) throw new IOException("winget uninstall produced no result.");
        return r;
    }

    private static boolean cancelled(AtomicBoolean cancelled) {
        return cancelled != null && cancelled.get();
    }

    /**
     * B3 FIX: strips CR/LF (line-injection) and caps length for values interpolated
     * into PowerShell single-quoted strings. Single-quote doubling at call sites
     * handles quote-breakout; this handles the line-break vector defense-in-depth.
     */
    private static String sanitizePsInput(String s, int maxLen) {
        if (s == null) return "";
        String t = s.replace('\r', ' ').replace('\n', ' ').trim();
        // Collapse control chars that have no business in a path/process name
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x20 && c != ' ' && c != '\t') continue;
            sb.append(c);
        }
        String out = sb.toString().trim();
        if (out.length() > maxLen) out = out.substring(0, maxLen).trim();
        return out;
    }

    /**
     * Deletes a list of registry keys recursively.
     * Paths must be formatted as "HKLM\..." or "HKCU\..." or "HKCR\...".
     *
     * @param registryPaths List of full registry paths.
     * @param failedDeletions Output list to append paths that could not be deleted.
     */
    public void deleteRegistryLeftovers(List<String> registryPaths, List<String> failedDeletions) {
        deleteRegistryLeftovers(registryPaths, failedDeletions, null);
    }

    public void deleteRegistryLeftovers(List<String> registryPaths, List<String> failedDeletions,
                                        AtomicBoolean cancelled) {
        if (registryPaths == null) return;
        for (String fullPath : registryPaths) {
            // Rescan FIX: null/blank entries must not NPE the cleanup thread
            // (which would leave busy=true and hang the tab until restart).
            if (fullPath == null || fullPath.isBlank()) continue;
            if (cancelled(cancelled)) {
                if (failedDeletions != null) failedDeletions.add(fullPath + " (cancelled — not deleted)");
                continue;
            }
            int separatorIdx = fullPath.indexOf('\\');
            if (separatorIdx == -1) continue;

            String hiveStr = fullPath.substring(0, separatorIdx);
            String subKeyPath = fullPath.substring(separatorIdx + 1);

            HKEY hive;
            if ("HKLM".equalsIgnoreCase(hiveStr)) {
                hive = WinReg.HKEY_LOCAL_MACHINE;
            } else if ("HKCU".equalsIgnoreCase(hiveStr)) {
                hive = WinReg.HKEY_CURRENT_USER;
            } else if ("HKCR".equalsIgnoreCase(hiveStr)) {
                hive = WinReg.HKEY_CLASSES_ROOT;
            } else {
                // HKU / unknown — never delete user-hive roots or unexpected hives.
                if (failedDeletions != null) failedDeletions.add(fullPath + " (unknown hive: " + hiveStr + ")");
                continue;
            }
            if (isProtectedRegistryPath(hiveStr, subKeyPath)) {
                AppLogger.warning("Refused to delete protected registry key: " + fullPath);
                if (failedDeletions != null) failedDeletions.add(fullPath + " (protected — skipped)");
                continue;
            }
            if (!deleteRegistryKeyRecursively(hive, subKeyPath)) {
                if (failedDeletions != null) failedDeletions.add(fullPath);
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
        return forceUninstall(app, null);
    }

    /**
     * Forcefully removes an application by killing its processes, deleting install directories,
     * removing registry entries, and cleaning Start Menu shortcuts — without running the standard uninstaller.
     */
    public ForceUninstallResult forceUninstall(InstalledApp app, AtomicBoolean cancelled) {
        List<String> summary = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String appName = app.getName();
        String lowerAppName = appName.toLowerCase();

        // Store (AppX) apps must be removed via Remove-AppxPackage — never by
        // deleting the protected WindowsApps folder directly.
        if (!app.isWin32()) {
            return forceUninstallAppx(app, summary, errors, cancelled);
        }
        if (cancelled(cancelled)) {
            errors.add("Force uninstall cancelled.");
            return new ForceUninstallResult(summary, errors);
        }

        String installLoc = app.getInstallLocation();
        if (installLoc != null && installLoc.indexOf('%') >= 0) {
            String expanded = expandEnvironmentVariables(installLoc);
            // Unexpanded %VAR% is not a directory. Fall through to the name search
            // instead of deleting the ARP key and leaving the real files behind.
            installLoc = (expanded == null || expanded.indexOf('%') >= 0) ? "" : expanded;
        }

        // BLOCKER FIX: never kill processes by a protected OS path. The old code
        // killed first and checked protection only before deletion, so a crafted
        // or corrupt InstallLocation like C:\Windows\System32 would mass-kill
        // system processes via path-prefix match before the delete was refused.
        // When the install tree is refused, the ARP key stays so the app remains listed.
        boolean keepUninstallRegistration = false;
        boolean installLocProtected = installLoc != null && !installLoc.isBlank()
                && isProtectedPath(installLoc);
        if (installLocProtected) {
            keepUninstallRegistration = true;
            errors.add("Skipped protected system directory (use standard uninstall): " + installLoc);
            AppLogger.warning("Force uninstall refused protected path (kill + delete skipped): " + installLoc);
        } else if (installLoc == null || installLoc.isBlank()) {
            killProcessesByPath(null, appName, summary, errors);
        } else {
            String resolved = canonicalizeForSafety(installLoc);
            String targetLoc = resolved != null ? resolved : installLoc;
            if (isSharedVendorInstallLocation(targetLoc, appName, app.getPublisher())) {
                File vendorDir = new File(targetLoc);
                if (vendorDir.exists() && vendorDir.isDirectory() && !isLinkOrReparse(vendorDir)) {
                    List<File> matchingInner = matchingInnerAppDirs(vendorDir, lowerAppName);
                    if (matchingInner.isEmpty()) {
                        keepUninstallRegistration = true;
                        errors.add("Skipped shared vendor directory (not app-specific): " + targetLoc);
                        AppLogger.warning("Force uninstall refused shared vendor root: " + targetLoc);
                    } else {
                        for (File inner : matchingInner) {
                            if (cancelled(cancelled)) break;
                            killProcessesByPath(inner.getAbsolutePath(), appName, summary, errors);
                            deleteForceDir(inner, summary, errors, cancelled);
                        }
                    }
                } else if (vendorDir.exists()) {
                    keepUninstallRegistration = true;
                    errors.add("Skipped shared vendor directory (not app-specific): " + targetLoc);
                }
            } else {
                killProcessesByPath(targetLoc, appName, summary, errors);
                File dir = new File(targetLoc);
                if (dir.exists()) {
                    deleteForceDir(dir, summary, errors, cancelled);
                }
            }
        }

        if (cancelled(cancelled)) {
            errors.add("Force uninstall cancelled.");
            return new ForceUninstallResult(summary, errors);
        }

        // If installLocation is empty, search common directories
        // Safety: never delete a vendor/publisher folder unless it contains a SINGLE child that
        // matches the app name. This prevents deleting C:\Program Files\Adobe when force-removing
        // a single Adobe app or C:\Program Files\Opera (which may host Opera + Opera GX).
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
                if (cancelled(cancelled)) break;
                File rootDir = new File(root);
                if (!rootDir.exists() || !rootDir.isDirectory()) continue;
                File[] children = rootDir.listFiles(File::isDirectory);
                if (children == null) continue;
                for (File child : children) {
                    if (cancelled(cancelled)) break;
                    String childName = child.getName().toLowerCase().trim();
                    if (isGenericName(childName)) continue;
                    String publisherLower = app.getPublisher() != null ? app.getPublisher().toLowerCase().trim() : "";
                    // Top-level: exact leaf only — substring would delete Opera GX when removing Opera.
                    boolean nameMatch = childName.equals(lowerAppName);
                    if (nameMatch) {
                        if (looksLikeSharedVendorDir(child.getAbsolutePath(), appName)) {
                            if (!isLinkOrReparse(child)) {
                                List<File> matchingInner = matchingInnerAppDirs(child, lowerAppName);
                                if (matchingInner.isEmpty()) {
                                    keepUninstallRegistration = true;
                                    errors.add("Skipped shared vendor directory (not app-specific): "
                                            + child.getAbsolutePath());
                                } else {
                                    for (File t : matchingInner) {
                                        if (cancelled(cancelled)) break;
                                        deleteForceDir(t, summary, errors, cancelled);
                                    }
                                }
                            }
                        } else {
                            deleteForceDir(child, summary, errors, cancelled);
                        }
                    } else {
                        // Publisher folder: only touch if publisher name is >=5, not generic,
                        // and the folder contains a single child that matches the app.
                        boolean publisherFolderMatch = !publisherLower.isEmpty() && publisherLower.length() >= 5
                                && !isGenericName(publisherLower)
                                && (childName.equals(publisherLower) || containsWordBoundary(childName, publisherLower));
                        if (publisherFolderMatch) {
                            // B5 FIX: never enumerate a link/junction target as a vendor folder.
                            if (isLinkOrReparse(child)) continue;
                            File[] allEntries = child.listFiles();
                            File[] innerDirs = child.listFiles(File::isDirectory);
                            if (allEntries == null || innerDirs == null) continue;
                            List<File> matchingInner = matchingInnerAppDirs(child, lowerAppName);
                            if (matchingInner.size() == 1) {
                                File target = matchingInner.get(0);
                                // Only delete whole vendor if it contains exactly one entry total (the app) — avoids wiping vendor that has extra files like uninstall.log
                                if (allEntries.length == 1 && innerDirs.length == 1) {
                                    // Vendor folder has only this app — safe to delete the whole vendor folder
                                    // B2 FIX: still refuse protected/shared roots even in single-app case.
                                    deleteForceDir(child, summary, errors, cancelled);
                                } else {
                                    // Vendor folder hosts multiple entries — delete only the matching subfolder
                                    deleteForceDir(target, summary, errors, cancelled);
                                }
                            } else if (matchingInner.size() > 1) {
                                for (File t : matchingInner) {
                                    if (cancelled(cancelled)) break;
                                    deleteForceDir(t, summary, errors, cancelled);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (cancelled(cancelled)) {
            errors.add("Force uninstall cancelled.");
            return new ForceUninstallResult(summary, errors);
        }

        if (keepUninstallRegistration) {
            errors.add("Kept uninstall registration because the install directory was not removed.");
            return new ForceUninstallResult(summary, errors);
        }

        // Delete the registry key at the app's registryKeyPath
        if (app.isWin32() && !app.getRegistryKeyPath().isEmpty()) {
            if (isProtectedRegistryPath(app.getRegistryHive(), app.getRegistryKeyPath())) {
                errors.add("Skipped protected registry key: "
                        + app.getRegistryHive() + "\\" + app.getRegistryKeyPath());
                AppLogger.warning("Force uninstall refused protected registry key: "
                        + app.getRegistryHive() + "\\" + app.getRegistryKeyPath());
            } else {
                HKEY hive = "HKLM".equalsIgnoreCase(app.getRegistryHive())
                        ? WinReg.HKEY_LOCAL_MACHINE : WinReg.HKEY_CURRENT_USER;
                try {
                    if (Advapi32Util.registryKeyExists(hive, app.getRegistryKeyPath())) {
                        boolean deleted = false;
                        try {
                            Advapi32Util.registryDeleteKey(hive, app.getRegistryKeyPath());
                            deleted = true;
                        } catch (Exception ex) {
                            deleted = deleteRegistryKeyRecursively(hive, app.getRegistryKeyPath());
                            if (!deleted) {
                                errors.add("Failed to delete registry key for " + appName + ": " + app.getRegistryKeyPath());
                            }
                        }
                        if (deleted) {
                            summary.add("Deleted registry key: " + app.getRegistryHive() + "\\" + app.getRegistryKeyPath());
                        }
                    }
                } catch (Exception e) {
                    errors.add("Failed to delete registry key for " + appName + ": " + e.getMessage());
                }
            }
        }

        // Search and delete registry keys under Uninstall paths that contain the app name
        String[][] uninstallPaths = {
                {"HKLM", "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"},
                {"HKLM", "SOFTWARE\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall"},
                {"HKCU", "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"}
        };

        for (String[] pathInfo : uninstallPaths) {
            if (cancelled(cancelled)) break;
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

        if (cancelled(cancelled)) {
            errors.add("Force uninstall cancelled.");
            return new ForceUninstallResult(summary, errors);
        }

        // Delete Start Menu shortcuts
        cleanStartMenuShortcuts(lowerAppName, summary, errors);

        return new ForceUninstallResult(summary, errors);
    }

    private static List<File> matchingInnerAppDirs(File vendorDir, String lowerAppName) {
        List<File> matchingInner = new ArrayList<>();
        File[] innerDirs = vendorDir.listFiles(File::isDirectory);
        if (innerDirs == null) return matchingInner;
        for (File innerDir : innerDirs) {
            String innerName = innerDir.getName().toLowerCase().trim();
            if (isGenericName(innerName)) continue;
            if (isGenericName(lowerAppName)) {
                if (innerName.equals(lowerAppName)) matchingInner.add(innerDir);
            } else if (isAppSpecificInnerName(innerName, lowerAppName)) {
                matchingInner.add(innerDir);
            }
        }
        return matchingInner;
    }

    private static void recordDeleteOutcome(File file, NativeFileHelper.DeleteOutcome outcome,
                                            List<String> summary, List<String> errors, String deletedPrefix) {
        if (file == null || outcome == null) {
            if (errors != null && file != null) errors.add("Failed to delete: " + file.getAbsolutePath());
            return;
        }
        String path = file.getAbsolutePath();
        switch (outcome) {
            case DELETED, RECYCLED -> summary.add(deletedPrefix + path);
            case QUEUED_FOR_REBOOT -> summary.add("Scheduled for reboot deletion: " + path);
            default -> errors.add("Failed to delete: " + path);
        }
    }

    private void deleteForceDir(File target, List<String> summary, List<String> errors, AtomicBoolean cancelled) {
        if (target == null) return;
        if (cancelled(cancelled)) {
            errors.add("Force uninstall cancelled.");
            return;
        }
        if (isProtectedPath(target.getAbsolutePath())) {
            AppLogger.warning("Force uninstall refused protected path: " + target.getAbsolutePath());
            errors.add("Skipped protected system directory: " + target.getAbsolutePath());
            return;
        }
        NativeFileHelper.DeleteOutcome outcome = NativeFileHelper.deleteOrQueueWithOutcome(target, cancelled);
        if (cancelled(cancelled) && outcome != NativeFileHelper.DeleteOutcome.DELETED
                && outcome != NativeFileHelper.DeleteOutcome.QUEUED_FOR_REBOOT) {
            errors.add("Force uninstall cancelled.");
            return;
        }
        recordDeleteOutcome(target, outcome, summary, errors, "Deleted directory: ");
    }

    /**
     * Force-removes a Store (AppX) package via Remove-AppxPackage. Never deletes
     * the WindowsApps folder directly — it is OS-protected and shared.
     */
    private ForceUninstallResult forceUninstallAppx(InstalledApp app, List<String> summary, List<String> errors,
                                                    AtomicBoolean cancelled) {
        String appName = app.getName();
        String pkg = app.getAppxPackageFullName();
        if (pkg == null || pkg.isBlank()) {
            errors.add("Cannot force-remove " + appName + ": missing package identity.");
            return new ForceUninstallResult(summary, errors);
        }
        if (cancelled(cancelled)) {
            errors.add("Force uninstall cancelled.");
            return new ForceUninstallResult(summary, errors);
        }
        // Stop related processes first (name-based, with safety guards)
        killProcessesByPath(null, appName, summary, errors);
        if (cancelled(cancelled)) {
            errors.add("Force uninstall cancelled.");
            return new ForceUninstallResult(summary, errors);
        }
        try {
            Path script = PowerShellScripts.resolve("appx-uninstall.ps1");
            ProcessResult r = processRunner.run(
                    ProcessRunner.powershellScript(script.toString(), "-PackageFullName", pkg), 180, cancelled);
            if (r.succeeded()) {
                summary.add("Removed Store package: " + pkg
                        + (r.isRebootRequired() ? " (reboot required)" : ""));
            } else {
                errors.add("Failed to remove Store package " + pkg + ": " + truncate(r.combinedOutput(), 400));
            }
        } catch (java.util.concurrent.CancellationException e) {
            errors.add("Force uninstall cancelled.");
            return new ForceUninstallResult(summary, errors);
        } catch (Exception e) {
            errors.add("Failed to remove Store package " + pkg + ": " + e.getMessage());
        }
        if (cancelled(cancelled)) {
            errors.add("Force uninstall cancelled.");
            return new ForceUninstallResult(summary, errors);
        }
        // Clean Start Menu shortcuts. Store shortcuts use the friendly display name
        // (e.g. "Spotify.lnk") while the package name is dotted ("SpotifyAB.SpotifyMusic"),
        // so match on significant tokens from both — confined to Start Menu shortcuts only.
        // Keep display-name tokens separate from package tokens so a 2-word
        // display name ("Notion Calendar") cannot match a sibling via one token
        // unioned with a dotted package identity.
        cleanStartMenuShortcutsByTokens(significantNameTokens(appName), summary, errors);
        cleanStartMenuShortcutsByTokens(significantNameTokens(app.getAppxPackageName()), summary, errors);
        return new ForceUninstallResult(summary, errors);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "... (truncated)";
    }

    private void cleanStartMenuShortcuts(String lowerAppName, List<String> summary, List<String> errors) {
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
    }

    /**
     * Splits display/package names into significant lowercase tokens for shortcut matching:
     * alphanumerics only, length >= 5, non-generic, not pure digits. Short/generic fragments
     * (e.g. "app", "pro", version numbers) are dropped to avoid touching unrelated shortcuts.
     */
    static java.util.Set<String> significantNameTokens(String name) {
        java.util.Set<String> tokens = new java.util.LinkedHashSet<>();
        if (name == null || name.isBlank()) return tokens;
        for (String part : name.toLowerCase().split("[^a-z0-9]+")) {
            String t = part.trim();
            if (t.length() < 5) continue;
            if (t.matches("\\d+")) continue;
            if (isGenericName(t)) continue;
            tokens.add(t);
        }
        return tokens;
    }

    /**
     * Single-token names match that token (Spotify.lnk). Multi-token names
     * require every token in the leaf so "Notion Calendar" cannot delete Notion.lnk.
     */
    static boolean startMenuTokensMatchLeaf(String leafNoExt, java.util.Set<String> tokens) {
        if (leafNoExt == null || tokens == null || tokens.isEmpty()) return false;
        if (isGenericName(leafNoExt)) return false;
        if (tokens.size() == 1) {
            String tok = tokens.iterator().next();
            return leafNoExt.equals(tok)
                    || (tok.length() >= 6 && isAppSpecificInnerName(leafNoExt, tok));
        }
        for (String tok : tokens) {
            if (!leafNoExt.equals(tok) && !containsWordBoundary(leafNoExt, tok)) return false;
        }
        return true;
    }

    private void cleanStartMenuShortcutsByTokens(java.util.Set<String> tokens, List<String> summary, List<String> errors) {
        if (tokens == null || tokens.isEmpty()) return;
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
                deleteMatchingFilesByTokens(startMenuDir, tokens, summary, errors);
            }
        }
    }

    /**
     * Deletes Start Menu entries matching any significant token. Exact filename
     * (sans extension) matches always count; substring matches require longer tokens
     * (>= 6 chars) with word boundaries to avoid collateral like a "Music" shortcut
     * when removing an app whose display name merely contains "music".
     */
    private void deleteMatchingFilesByTokens(File dir, java.util.Set<String> tokens, List<String> summary, List<String> errors) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String lowerFile = file.getName().toLowerCase().trim();
            // Generic containers (Start Menu\Programs) are protected-as-leaves.
            // Recurse into them; never delete the container itself.
            if (isProtectedPath(file.getAbsolutePath())) {
                if (isGenericName(lowerFile) && file.isDirectory() && !isLinkOrReparse(file)) {
                    deleteMatchingFilesByTokens(file, tokens, summary, errors);
                }
                continue;
            }
            if (isGenericName(lowerFile)) {
                // B5 FIX: never descend into link targets.
                if (file.isDirectory() && !isLinkOrReparse(file)) deleteMatchingFilesByTokens(file, tokens, summary, errors);
                continue;
            }
            String leafNoExt = lowerFile;
            int dot = leafNoExt.lastIndexOf('.');
            if (dot > 0) leafNoExt = leafNoExt.substring(0, dot);
            boolean matches = startMenuTokensMatchLeaf(leafNoExt, tokens);
            if (matches) {
                recordDeleteOutcome(file, NativeFileHelper.deleteOrQueueWithOutcome(file),
                        summary, errors, "Deleted: ");
            } else if (file.isDirectory()) {
                // B5 FIX: matching links are deleted as links by deleteOrQueue;
                // non-matching links are skipped, never traversed.
                if (!isLinkOrReparse(file)) deleteMatchingFilesByTokens(file, tokens, summary, errors);
            }
        }
    }

    private void killProcessesByPath(String installLoc, String appName, List<String> summary, List<String> errors) {
        try {
            // BLOCKER FIX (defense-in-depth): refuse path-based kills for protected
            // OS locations even if a future caller forgets the pre-check in
            // forceUninstall(). Killing by "C:\Windows" would match every system
            // process via prefix comparison.
            if (installLoc != null && !installLoc.isBlank()) {
                String canon = canonicalizeForSafety(installLoc);
                if (canon == null || isProtectedPath(canon)) {
                    AppLogger.warning("Refused process kill by protected path: " + installLoc);
                    if (errors != null) errors.add("Skipped process kill for protected path: " + installLoc);
                    if (summary != null) summary.add("No running processes stopped (protected path).");
                    return;
                }
                installLoc = canon;
            }
            String psScript;
            // B3 FIX: registry values are untrusted — strip CR/LF + cap length
            // defense-in-depth (single-quote doubling below already neutralizes
            // quote-breakout; this kills line-injection + oversized command lines).
            String safeLoc = sanitizePsInput(installLoc, 512);
            String safeApp = sanitizePsInput(appName, 128);
            long selfPid = ProcessHandle.current().pid();
            boolean byPath = safeLoc != null && !safeLoc.isBlank();
            if (byPath) {
                // Use exact directory boundary comparison to avoid killing processes
                // in sibling directories that share a prefix (e.g. C:\...\Google\ vs C:\...\Google Update\)
                // NOTE: For -eq / StartsWith we must NOT escape wildcard chars with backticks — that's only for -like
                String normalizedPath = safeLoc.replace('\\', '/').replaceAll("/+$", "");
                String escapedPath = normalizedPath.replace("'", "''");
                // Count matches so the summary is accurate instead of claiming success unconditionally
                psScript = "$skip = " + selfPid + "; $target = '" + escapedPath + "'; " +
                        "$cands = Get-Process -ErrorAction SilentlyContinue | Where-Object { " +
                        "  if ($_.Id -eq $skip) { return $false }; " +
                        "  if (-not $_.Path) { return $false }; " +
                        "  $p = $_.Path.Replace('\\','/'); " +
                        "  $p -eq $target -or $p.StartsWith($target + '/', [System.StringComparison]::OrdinalIgnoreCase) " +
                        "}; " +
                        "$n = @($cands).Count; " +
                        "$cands | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }; " +
                        "Write-Output (\"KILLED:\" + $n)";
            } else {
                String raw = safeApp != null ? safeApp.trim() : "";
                if (raw.isBlank()) {
                    if (summary != null) {
                        summary.add("No running processes stopped (no install path or app name).");
                    }
                    return;
                }
                String noSpace = raw.replaceAll("\\s+", "");
                if (isGenericName(raw) || isGenericName(noSpace)
                        || isProtectedProcessName(raw) || isProtectedProcessName(noSpace)) {
                    if (summary != null) {
                        summary.add("No running processes stopped (name not safe for process kill).");
                    }
                    return;
                }
                // CRITICAL FIX: exact ProcessName only — -like '*name*' killed unrelated processes.
                String escapedExact = raw.replace("'", "''");
                String escapedNoSpace = noSpace.replace("'", "''");
                if (noSpace.equalsIgnoreCase(raw)) {
                    psScript = "$skip = " + selfPid + "; $cands = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Id -ne $skip -and $_.ProcessName -eq '" + escapedExact + "' }; " +
                            "$n = @($cands).Count; " +
                            "$cands | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }; " +
                            "Write-Output (\"KILLED:\" + $n)";
                } else {
                    psScript = "$skip = " + selfPid + "; $cands = Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Id -ne $skip -and ($_.ProcessName -eq '" + escapedExact + "' -or $_.ProcessName -eq '" + escapedNoSpace + "') }; " +
                            "$n = @(@($cands) | Select-Object -Unique Id).Count; " +
                            "$cands | Select-Object -Unique Id | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }; " +
                            "Write-Output (\"KILLED:\" + $n)";
                }
            }
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", psScript);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            Thread drainThread = new Thread(() -> {
                try {
                    byte[] b = proc.getInputStream().readAllBytes();
                    out.append(new String(b, java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ignored) {}
            }, "ps-drain");
            drainThread.setDaemon(true);
            drainThread.start();
            boolean exited = proc.waitFor(10, TimeUnit.SECONDS);
            try { drainThread.join(2_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            proc.destroy();
            int killed = parseKilledCount(out.toString());
            if (!exited) {
                errors.add("Timed out while stopping processes for: " + appName);
            } else if (killed > 0) {
                summary.add("Stopped " + killed + " running process(es) for: " + appName);
            } else {
                summary.add("No running processes found for: " + appName);
            }
        } catch (Exception e) {
            errors.add("Failed to kill processes for " + appName + ": " + e.getMessage());
        }
    }

    private static int parseKilledCount(String output) {
        if (output == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("KILLED:(\\d+)").matcher(output);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private void deleteMatchingFiles(File dir, String lowerName, List<String> summary, List<String> errors) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String lowerFile = file.getName().toLowerCase().trim();
            // Generic containers (Start Menu\Programs) are protected-as-leaves.
            // Recurse into them; never delete the container itself.
            if (isProtectedPath(file.getAbsolutePath())) {
                if (isGenericName(lowerFile) && file.isDirectory() && !isLinkOrReparse(file)) {
                    deleteMatchingFiles(file, lowerName, summary, errors);
                }
                continue;
            }
            if (isGenericName(lowerFile)) {
                // B5 FIX: never descend into link targets.
                if (file.isDirectory() && !isLinkOrReparse(file)) deleteMatchingFiles(file, lowerName, summary, errors);
                continue;
            }
            String leafNoExt = lowerFile;
            int dot = lowerFile.lastIndexOf('.');
            if (dot > 0) leafNoExt = lowerFile.substring(0, dot);
            boolean matches = isStartMenuLeafMatch(leafNoExt, lowerName)
                    || isStartMenuLeafMatch(lowerFile, lowerName);
            if (matches) {
                recordDeleteOutcome(file, NativeFileHelper.deleteOrQueueWithOutcome(file),
                        summary, errors, "Deleted: ");
            } else if (file.isDirectory()) {
                if (!isLinkOrReparse(file)) deleteMatchingFiles(file, lowerName, summary, errors);
            }
        }
    }
}
