package com.sbtools.software;

import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Thread-safe utility for resolving the winget executable path and building
 * winget command lines with automatic fallback (direct → cmd.exe → PowerShell).
 */
public class WingetRunner {

    private final ProcessRunner runner;
    private volatile String resolvedPath;
    private volatile boolean resolved;
    private volatile boolean available;

    public WingetRunner(ProcessRunner runner) {
        this.runner = runner;
    }

    public WingetRunner() {
        this(new ProcessRunner(600));
    }

    /**
     * Returns true if winget is available on this system.
     * Caches the result for the lifetime of this instance.
     */
    public boolean isAvailable() {
        if (resolved) return available;
        try {
            String p = resolvePath();
            if (p != null) {
                available = true;
                resolved = true;
                return true;
            }
            ProcessResult r = runner.run(buildCommand("winget", "--version"), 10);
            available = r.success();
            resolved = true;
            return available;
        } catch (Exception e) {
            AppLogger.info("winget not available: " + e.getMessage());
            available = false;
            resolved = true;
            return false;
        }
    }

    /**
     * Resolves the winget executable path using multiple strategies.
     * Thread-safe: uses double-checked locking.
     */
    public String resolvePath() {
        if (resolvedPath != null) return resolvedPath;
        synchronized (this) {
            if (resolvedPath != null) return resolvedPath;
            resolvedPath = doResolvePath();
            return resolvedPath;
        }
    }

    private String doResolvePath() {
        // 1) where.exe
        try {
            ProcessResult where = runner.run(Arrays.asList("where.exe", "winget"), 5);
            if (where.success() && where.stdout() != null && !where.stdout().isBlank()) {
                String first = where.stdout().split("\\r?\\n")[0].trim();
                if (!first.isBlank()) return first;
            }
        } catch (Exception ignored) {
        }

        // 2) search PATH entries
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String part : pathEnv.split(File.pathSeparator)) {
                try {
                    if (part == null || part.isBlank()) continue;
                    Path p = Paths.get(part, "winget.exe");
                    if (Files.exists(p)) return p.toString();
                } catch (Exception ignored) {
                }
            }
        }

        // 3) LOCALAPPDATA\Microsoft\WindowsApps\winget.exe
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            try {
                Path p = Paths.get(local, "Microsoft", "WindowsApps", "winget.exe");
                if (Files.exists(p)) return p.toString();
            } catch (Exception ignored) {
            }
        }

        // 4) Appx package install location
        try {
            ProcessResult pkg = runner.run(Arrays.asList(
                    "powershell", "-NoProfile", "-Command",
                    "Get-AppxPackage -Name 'Microsoft.DesktopAppInstaller' | Select-Object -ExpandProperty InstallLocation"), 5);
            String out = pkg.stdout();
            if (out != null && !out.isBlank()) {
                String locLine = null;
                for (String ln : out.split("\\r?\\n")) {
                    if (ln != null && !ln.isBlank()) {
                        locLine = ln.trim();
                        break;
                    }
                }
                if (locLine != null) {
                    Path installPath = Paths.get(locLine);
                    if (Files.isDirectory(installPath)) {
                        try (var stream = Files.walk(installPath)) {
                            var found = stream.filter(p -> p.getFileName().toString().equalsIgnoreCase("winget.exe")).findFirst();
                            if (found.isPresent()) return found.get().toString();
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    /**
     * Builds a winget command with automatic fallback.
     * Tries: direct path → cmd.exe → PowerShell.
     * Returns the first candidate list. Caller should iterate through all
     * candidates if the first fails.
     */
    public List<List<String>> buildCandidates(String... args) {
        List<List<String>> candidates = new ArrayList<>();
        String resolved = resolvePath();
        if (resolved != null) {
            List<String> direct = new ArrayList<>();
            direct.add(resolved);
            for (String a : args) direct.add(a);
            candidates.add(direct);
        }

        List<String> cmdFallback = new ArrayList<>();
        cmdFallback.add("cmd.exe");
        cmdFallback.add("/c");
        cmdFallback.add("winget");
        for (String a : args) cmdFallback.add(a);
        candidates.add(cmdFallback);

        String[] allArgs = new String[args.length + 1];
        allArgs[0] = "winget";
        System.arraycopy(args, 0, allArgs, 1, args.length);
        String psCmd = buildPowerShellCommandString(allArgs);
        candidates.add(Arrays.asList("powershell", "-NoProfile", "-Command", "& { " + psCmd + " }"));

        return candidates;
    }

    /**
     * Runs a winget command with automatic fallback across all candidates.
     * Returns the first successful result, or the last failed result.
     */
    public ProcessResult runWithFallback(long timeoutSeconds, String... args) {
        List<List<String>> candidates = buildCandidates(args);
        ProcessResult lastResult = null;
        Exception lastEx = null;
        for (List<String> candidate : candidates) {
            try {
                ProcessResult r = runner.run(candidate, timeoutSeconds);
                if (r.success() || (r.stdout() != null && !r.stdout().isBlank())) {
                    if (!r.success()) {
                        AppLogger.warning("winget returned non-zero: " + r.exitCode() + " output: " + r.combinedOutput());
                    }
                    return r;
                }
                lastResult = r;
            } catch (Exception ex) {
                lastEx = ex;
            }
        }
        return lastResult;
    }

    /**
     * Runs a winget command in streaming mode with automatic fallback across candidates.
     * Calls lineCallback for each output line and progressCallback for progress updates.
     * Returns the first successful result, or the last failed result.
     */
    public ProcessResult runWithFallbackStreaming(Consumer<String> lineCallback,
                                                  Consumer<Double> progressCallback,
                                                  AtomicBoolean cancelled,
                                                  String... args) throws java.io.IOException, java.util.concurrent.CancellationException {
        List<List<String>> candidates = buildCandidates(args);
        ProcessResult lastResult = null;
        Exception lastEx = null;
        for (List<String> candidate : candidates) {
            try {
                ProcessResult r = runner.runStreaming(candidate, lineCallback, progressCallback, cancelled);
                if (r.success() || (r.stdout() != null && !r.stdout().isBlank())) {
                    if (!r.success()) {
                        AppLogger.warning("winget returned non-zero: " + r.exitCode() + " output: " + r.combinedOutput());
                    }
                    return r;
                }
                lastResult = r;
            } catch (java.util.concurrent.CancellationException cex) {
                throw cex;
            } catch (Exception ex) {
                lastEx = ex;
            }
        }
        if (lastResult != null) return lastResult;
        if (lastEx != null) throw new java.io.IOException("Streaming failed", lastEx);
        return new ProcessResult(-1, "", "No candidates");
    }

    /**
     * Simple command builder for single invocation (e.g. --version check).
     */
    public List<String> buildCommand(String... args) {
        String exe = resolvePath();
        List<String> cmd = new ArrayList<>();
        if (exe != null) {
            cmd.add(exe);
            for (String a : args) cmd.add(a);
        } else {
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add("winget");
            for (String a : args) cmd.add(a);
        }
        return cmd;
    }

    /**
     * Returns diagnostic information about winget availability and path resolution.
     */
    public String getDiagnostics() {
        StringBuilder sb = new StringBuilder();
        try {
            ProcessResult where = runner.run(Arrays.asList("where.exe", "winget"), 5);
            sb.append("where.exe output:\n").append(where.combinedOutput()).append("\n");
        } catch (Exception e) {
            sb.append("where.exe error: ").append(e.getMessage()).append("\n");
        }
        try {
            String p = resolvePath();
            if (p != null) {
                ProcessResult v = runner.run(Arrays.asList(p, "--version"), 5);
                sb.append("winget --version:\n").append(v.combinedOutput()).append("\n");
            } else {
                try {
                    ProcessResult v = runner.run(Arrays.asList("cmd.exe", "/c", "winget --version"), 5);
                    sb.append("winget --version (via cmd.exe):\n").append(v.combinedOutput()).append("\n");
                } catch (Exception ex) {
                    sb.append("winget --version (via cmd.exe) error: ").append(ex.getMessage()).append("\n");
                }
                try {
                    String cmd = buildPowerShellCommandString("winget", "--version");
                    ProcessResult v2 = runner.run(Arrays.asList("powershell", "-NoProfile", "-Command", "& { " + cmd + " }"), 5);
                    sb.append("winget --version (via PowerShell):\n").append(v2.combinedOutput()).append("\n");
                } catch (Exception ex2) {
                    sb.append("winget --version (via PowerShell) error: ").append(ex2.getMessage()).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("winget --version error: ").append(e.getMessage()).append("\n");
        }
        sb.append("resolvedWingetPath=\n").append(resolvePath()).append("\n");
        sb.append("PATH=\n").append(System.getenv("PATH")).append("\n");
        sb.append("UserLocalAppData=\n").append(System.getenv("LOCALAPPDATA")).append("\\Microsoft\\WindowsApps\n");
        return sb.toString();
    }

    private static String buildPowerShellCommandString(String... args) {
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (a == null) continue;
            if (sb.length() > 0) sb.append(' ');
            String safe = a.replace("\"", "\\\"");
            if (safe.contains(" ") || safe.contains("\"")) {
                sb.append('"').append(safe).append('"');
            } else {
                sb.append(safe);
            }
        }
        return sb.toString();
    }
}
