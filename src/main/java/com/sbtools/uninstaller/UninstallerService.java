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

            // Snapshot existing PIDs before launching uninstaller to detect spawned children reliably
            java.util.Set<Long> prePids = new java.util.HashSet<>();
            try {
                ProcessHandle.allProcesses().forEach(ph -> prePids.add(ph.pid()));
            } catch (Exception ignored) {}

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);
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
            // Wait for the install directory to be removed (use remaining budget, not capped to 30)
            long afterChildRemaining = Math.max(5, TimeUnit.NANOSECONDS.toSeconds(deadlineNanos - System.nanoTime()));
            waitForInstallDirRemoval(app, (int) Math.min(120, afterChildRemaining));

            return new ProcessResult(exitCode, stdout, stderr);
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
        String uninstallStr = app.getUninstallString() != null ? app.getUninstallString().toLowerCase() : "";
        // Extract GUIDs like {12345678-1234-...} from uninstall string for MSI tracking
        java.util.List<String> guids = new java.util.ArrayList<>();
        try {
            java.util.regex.Matcher gm = java.util.regex.Pattern.compile("\\{[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\}").matcher(uninstallStr);
            while (gm.find()) guids.add(gm.group().toLowerCase());
        } catch (Exception ignored) {}
        // Also extract uninstall exe base name (e.g. unins000) for Inno/NSIS tracking when installLocation is blank
        String tmpBase = "";
        try {
            List<String> toks = parseUninstallCommand(app.getUninstallString() != null ? app.getUninstallString() : "");
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
                    boolean isSetupUnins = execPath.contains("setup") || execPath.contains("unins")
                            || execPath.contains("uninstall");
                    boolean matchByPath = !lowerLoc.isEmpty()
                            && (containsWordBoundary(cmdLine, lowerLoc) || containsWordBoundary(execPath, lowerLoc)
                                || execPath.startsWith(lowerLoc + "\\") || execPath.startsWith(lowerLoc + "/"));
                    boolean matchByName = lowerName.length() >= 3
                            && !isGenericName(lowerName)
                            && (containsWordBoundary(cmdLine, lowerName) || containsWordBoundary(execPath, lowerName)
                                || execPath.endsWith("\\" + lowerName + ".exe") || execPath.endsWith("/" + lowerName + ".exe"));
                    boolean matchByUninstallBase = !uninstallBase.isEmpty()
                            && (execPath.contains(uninstallBase) || cmdLine.contains(uninstallBase));

                    if (isMsiexec || matchByPath || matchByName || matchByUninstallBase || isSetupUnins) {
                        if (isMsiexec) {
                            found.set(true);
                        } else if (matchByPath || matchByName || matchByUninstallBase) {
                            found.set(true);
                        } else if (isSetupUnins && !lowerLoc.isEmpty() && execPath.startsWith(new File(lowerLoc).getParent() != null ? new File(lowerLoc).getParent().toLowerCase() + "\\" : "")) {
                            found.set(true);
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

                    boolean matchByPath = !lowerLoc.isEmpty()
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

    static List<String> parseUninstallCommandForTest(String uninstallCmd) {
        return new UninstallerService().parseUninstallCommand(uninstallCmd);
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

        // Deduplicate roots (e.g., AppData vs Roaming overlap)
        roots = new ArrayList<>(new java.util.LinkedHashSet<>(roots));

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

        return leftovers;
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
     * @deprecated Use {@link #scanPathWarnings(InstalledApp)} — kept for backward compat, do not mix with file paths.
     */
    @Deprecated
    private void checkPathEntriesForLeftover(String installLocation, List<String> leftovers) {
        // No-op: intentionally not mixing PATH warnings into deletable paths
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

        // Search in Software paths only — do NOT scan SYSTEM\CurrentControlSet\Services
        // because substring matching on service names can flag legitimate Windows services
        scanRegistryForLeftovers(WinReg.HKEY_LOCAL_MACHINE, "HKLM", "SOFTWARE", app.getName(), app.getPublisher(), leftovers);
        scanRegistryForLeftovers(WinReg.HKEY_LOCAL_MACHINE, "HKLM", "SOFTWARE\\Wow6432Node", app.getName(), app.getPublisher(), leftovers);
        scanRegistryForLeftovers(WinReg.HKEY_CURRENT_USER, "HKCU", "SOFTWARE", app.getName(), app.getPublisher(), leftovers);

        // Scan HKCR for file association entries
        scanHkcrForLeftovers(app.getName(), app.getPublisher(), leftovers);

        return leftovers;
    }

    /**
     * Scans HKEY_CLASSES_ROOT for file association entries matching the app name or publisher.
     * Uses stricter thresholds (>=5 chars, word-boundary) and correctly filters top-level CLSID.
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
            if (lowerName.length() < 5 && lowerPub.length() < 5) return;

            for (String subkey : subkeys) {
                // Skip very long keys (COM CLSIDs, etc.) — top-level keys are flat, e.g. "CLSID" not "CLSID\..."
                if (subkey.length() > 80) continue;
                String lowerSub = subkey.toLowerCase();
                if (lowerSub.equals("clsid") || lowerSub.equals("wow6432node") || lowerSub.startsWith("clsid\\") || lowerSub.startsWith("wow6432node\\")) {
                    continue;
                }
                // Skip generic HKCR entries like file extensions and type libs
                if (isGenericName(lowerSub)) continue;
                String lowerKey = lowerSub;
                boolean nameMatch = lowerName.length() >= 5 && (lowerKey.equals(lowerName) || containsWordBoundary(lowerKey, lowerName));
                boolean pubMatch = lowerPub.length() >= 5 && (lowerKey.equals(lowerPub) || containsWordBoundary(lowerKey, lowerPub));
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
        String fName = folderName.toLowerCase().trim();
        String aName = appName != null ? appName.toLowerCase().trim() : "";
        String pName = publisher != null ? publisher.toLowerCase().trim() : "";

        if (aName.isEmpty()) return false;
        if (isGenericName(fName)) return false;

        // Exact match
        if (fName.equals(aName) || (!pName.isEmpty() && fName.equals(pName))) {
            return true;
        }

        // Substring match for App Name — require folder contains app (not reverse) to avoid vendor wipe (e.g., "Adobe" vs "Adobe Acrobat")
        if (aName.length() >= 5 && containsWordBoundary(fName, aName)) {
            return true;
        }

        // Substring match for Publisher — bidirectional, require >=5 and word boundary
        if (pName.length() >= 5 && (containsWordBoundary(fName, pName) || containsWordBoundary(pName, fName))) {
            return true;
        }

        return false;
    }

    private boolean isRegistryKeyMatch(String keyName, String appName, String publisher) {
        if (keyName == null || keyName.isBlank()) return false;
        String kName = keyName.toLowerCase().trim();
        String aName = appName != null ? appName.toLowerCase().trim() : "";
        String pName = publisher != null ? publisher.toLowerCase().trim() : "";

        if (aName.isEmpty()) return false;
        if (isGenericName(kName)) return false;

        // Exact matches
        if (kName.equals(aName) || (!pName.isEmpty() && kName.equals(pName))) {
            return true;
        }

        // Substring match — only key contains app (not reverse) to prevent vendor root false positives
        if (aName.length() >= 5 && containsWordBoundary(kName, aName)) {
            return true;
        }

        return false;
    }

    private boolean isPublisherMatch(String keyName, String publisher) {
        if (keyName == null || keyName.isBlank() || publisher == null || publisher.isBlank()) return false;
        String kName = keyName.toLowerCase().trim();
        String pName = publisher.toLowerCase().trim();

        if (pName.length() < 4) return false;
        if (isGenericName(kName)) return false;
        return kName.equals(pName) || containsWordBoundary(kName, pName) || containsWordBoundary(pName, kName);
    }

    private boolean isGenericName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase().trim();
        String[] generic = {
                "software", "program", "app", "application", "microsoft", "windows",
                "common", "common files", "common files (x86)", "temp", "local", "roaming", "data", "uninstall", "uninstaller", "utilities",
                "tool", "tools", "driver", "drivers", "update", "updates", "config", "cache", "logs", "log", "packages", "package",
                "resources", "resource", "share", "lib", "bin", "src", "include", "plugin", "plugins",
                "extension", "extensions", "module", "modules", "system", "services", "service", "startup",
                "utility", "utilities", "installer", "setup", "helper", "program files", "program files (x86)", "programdata", "common file",
                "library", "libraries", "driverstore"
        };
        for (String gen : generic) {
            if (n.equals(gen)) return true;
        }
        return false;
    }

    /**
     * Returns true if the given folder/registry key name is an exact (case-insensitive)
     * match for the app name or publisher — high confidence. Word-boundary substring
     * matches are considered heuristic (lower confidence) and should default to
     * unselected in the UI to avoid accidental deletion.
     */
    public static boolean isExactMatch(String leafName, InstalledApp app) {
        if (leafName == null || app == null) return false;
        String leaf = leafName.toLowerCase().trim();
        String aName = app.getName() != null ? app.getName().toLowerCase().trim() : "";
        String pName = app.getPublisher() != null ? app.getPublisher().toLowerCase().trim() : "";
        return leaf.equals(aName) || (!pName.isEmpty() && leaf.equals(pName));
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
     * Paths must be formatted as "HKLM\..." or "HKCU\..." or "HKCR\...".
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

            HKEY hive;
            if ("HKLM".equalsIgnoreCase(hiveStr)) {
                hive = WinReg.HKEY_LOCAL_MACHINE;
            } else if ("HKCU".equalsIgnoreCase(hiveStr)) {
                hive = WinReg.HKEY_CURRENT_USER;
            } else if ("HKCR".equalsIgnoreCase(hiveStr)) {
                hive = WinReg.HKEY_CLASSES_ROOT;
            } else if ("HKU".equalsIgnoreCase(hiveStr)) {
                hive = WinReg.HKEY_USERS;
            } else {
                // Unknown hive — still record failure for visibility
                failedDeletions.add(fullPath + " (unknown hive: " + hiveStr + ")");
                continue;
            }
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
                File rootDir = new File(root);
                if (!rootDir.exists() || !rootDir.isDirectory()) continue;
                File[] children = rootDir.listFiles(File::isDirectory);
                if (children == null) continue;
                for (File child : children) {
                    String childName = child.getName().toLowerCase().trim();
                    if (isGenericName(childName)) continue;
                    String publisherLower = app.getPublisher() != null ? app.getPublisher().toLowerCase().trim() : "";
                    boolean nameMatch;
                    if (isGenericName(lowerAppName)) {
                        nameMatch = childName.equals(lowerAppName);
                    } else {
                        // Only folder contains app (not reverse) to avoid deleting vendor root like "Adobe" for "Adobe Acrobat"
                        nameMatch = childName.equals(lowerAppName)
                                || (lowerAppName.length() >= 5 && containsWordBoundary(childName, lowerAppName));
                    }
                    if (nameMatch) {
                        if (NativeFileHelper.deleteOrQueue(child)) {
                            summary.add("Deleted directory: " + child.getAbsolutePath());
                        } else {
                            errors.add("Scheduled for reboot deletion: " + child.getAbsolutePath());
                        }
                    } else {
                        // Publisher folder: only touch if publisher name is >=5, not generic,
                        // and the folder contains a single child that matches the app.
                        boolean publisherFolderMatch = !publisherLower.isEmpty() && publisherLower.length() >= 5
                                && !isGenericName(publisherLower)
                                && (childName.equals(publisherLower) || containsWordBoundary(childName, publisherLower));
                        if (publisherFolderMatch) {
                            File[] allEntries = child.listFiles();
                            File[] innerDirs = child.listFiles(File::isDirectory);
                            if (allEntries == null || innerDirs == null) continue;
                            List<File> matchingInner = new ArrayList<>();
                            for (File innerDir : innerDirs) {
                                String innerName = innerDir.getName().toLowerCase().trim();
                                // Prevent generic app names from matching broadly; inner match only requires folder contains app
                                if (isGenericName(lowerAppName)) {
                                    if (innerName.equals(lowerAppName)) matchingInner.add(innerDir);
                                } else {
                                    boolean innerMatch = innerName.equals(lowerAppName)
                                            || (lowerAppName.length() >= 5 && containsWordBoundary(innerName, lowerAppName));
                                    if (innerMatch) matchingInner.add(innerDir);
                                }
                            }
                            if (matchingInner.size() == 1) {
                                File target = matchingInner.get(0);
                                // Only delete whole vendor if it contains exactly one entry total (the app) — avoids wiping vendor that has extra files like uninstall.log
                                if (allEntries.length == 1 && innerDirs.length == 1) {
                                    // Vendor folder has only this app — safe to delete the whole vendor folder
                                    if (NativeFileHelper.deleteOrQueue(child)) {
                                        summary.add("Deleted vendor directory (single-app): " + child.getAbsolutePath());
                                    } else {
                                        errors.add("Scheduled for reboot deletion: " + child.getAbsolutePath());
                                    }
                                } else {
                                    // Vendor folder hosts multiple entries — delete only the matching subfolder
                                    if (NativeFileHelper.deleteOrQueue(target)) {
                                        summary.add("Deleted directory: " + target.getAbsolutePath());
                                    } else {
                                        errors.add("Scheduled for reboot deletion: " + target.getAbsolutePath());
                                    }
                                }
                            } else if (matchingInner.size() > 1) {
                                for (File t : matchingInner) {
                                    if (NativeFileHelper.deleteOrQueue(t)) {
                                        summary.add("Deleted directory: " + t.getAbsolutePath());
                                    } else {
                                        errors.add("Scheduled for reboot deletion: " + t.getAbsolutePath());
                                    }
                                }
                            }
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
                // Use exact directory boundary comparison to avoid killing processes
                // in sibling directories that share a prefix (e.g. C:\...\Google\ vs C:\...\Google Update\)
                // NOTE: For -eq / StartsWith we must NOT escape wildcard chars with backticks — that's only for -like
                String normalizedPath = installLoc.replace('\\', '/').replaceAll("/+$", "");
                String escapedPath = normalizedPath.replace("'", "''");
                psScript = "$target = '" + escapedPath + "'; " +
                        "Get-Process | Where-Object { " +
                        "  if (-not $_.Path) { return $false }; " +
                        "  $p = $_.Path.Replace('\\','/'); " +
                        "  $p -eq $target -or $p.StartsWith($target + '/', [System.StringComparison]::OrdinalIgnoreCase) " +
                        "} | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }; " +
                        "Write-Output 'done'";
            } else {
                // Match by process name: -like needs wildcards and escaped wildcard chars; also handle space-less variant (e.g. "Google Chrome" -> chrome)
                String raw = appName != null ? appName.trim() : "";
                String escapedExact = raw.replace("'", "''");
                String likeEscaped = raw.replace("`", "``").replace("[", "`[").replace("]", "`]").replace("*", "`*").replace("?", "`?").replace("$", "`$").replace("'", "''");
                String noSpace = raw.replaceAll("\\s+", "");
                String likeNoSpace = noSpace.replace("`", "``").replace("[", "`[").replace("]", "`]").replace("*", "`*").replace("?", "`?").replace("$", "`$").replace("'", "''");
                // Use wildcards around the name; PowerShell -like is case-insensitive
                psScript = "Get-Process | Where-Object { $_.ProcessName -like '*" + likeEscaped + "*' -or $_.ProcessName -like '*" + likeNoSpace + "*' -or $_.ProcessName -eq '" + escapedExact + "' } | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }; " +
                        "Write-Output 'done'";
            }
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", psScript);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
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
            String lowerFile = file.getName().toLowerCase().trim();
            if (isGenericName(lowerFile)) {
                if (file.isDirectory()) deleteMatchingFiles(file, lowerName, summary, errors);
                continue;
            }
            boolean matches = lowerFile.equals(lowerName)
                    || (lowerName.length() >= 5 && containsWordBoundary(lowerFile, lowerName));
            if (matches) {
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
