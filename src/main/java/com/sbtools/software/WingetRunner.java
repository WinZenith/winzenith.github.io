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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thread-safe utility for resolving the winget executable path and building
 * winget command lines with automatic fallback (direct → cmd.exe → PowerShell).
 */
public class WingetRunner {

    private static final Pattern VERSION_PATTERN = Pattern.compile("v?([0-9]+\\.[0-9]+\\.[0-9]+)");

    private final ProcessRunner runner;
    // JVM-wide resolution cache: winget availability/path/version are system state, not
    // per-instance. Dashboard + Software tab each own a WingetRunner; without sharing,
    // every scan paid for duplicate where.exe + winget --version processes.
    private static volatile String resolvedPath;
    private static volatile boolean resolved;
    private static volatile boolean available;

    private static volatile String cachedVersion;
    private static volatile Boolean cachedJsonSupported;

    private volatile int workingCandidateIndex = -1;

    public WingetRunner(ProcessRunner runner) {
        this.runner = runner;
    }

    public WingetRunner() {
        this(new ProcessRunner(600));
    }

    /**
     * Returns true if winget is available on this system.
     * A positive result is cached for the JVM lifetime; a negative result is
     * NOT cached forever — it is retried on every call so installing
     * "App Installer" (or fixing PATH / execution aliases) is picked up
     * without restarting the app. Previously a single early false poisoned
     * every later scan.
     */
    public boolean isAvailable() {
        if (resolved && available) return true;
        // Retry negatives: fall through and re-probe every time until success.
        try {
            String p = resolvePath();
            if (p != null) {
                available = true;
                resolved = true;
                return true;
            }
            ProcessResult r = runner.run(buildCommand("winget", "--version"), 10);
            available = r.success();
            // Only latch resolved=true on success; negatives stay retryable.
            if (available) resolved = true;
            return available;
        } catch (Exception e) {
            AppLogger.info("winget not available: " + e.getMessage());
            available = false;
            return false;
        }
    }

    /**
     * Returns the cached winget version string (e.g. "v1.9.2831").
     * Successful lookups are cached per JVM; empty (failure) results are NOT
     * latched — they are retried so a later winget install/upgrade is picked up.
     */
    public String getVersion() {
        if (cachedVersion != null && !cachedVersion.isEmpty()) return cachedVersion;
        synchronized (WingetRunner.class) {
            if (cachedVersion != null && !cachedVersion.isEmpty()) return cachedVersion;
            try {
                ProcessResult r = runner.run(buildCommand("winget", "--version"), 10);
                if (r.success() && r.stdout() != null && !r.stdout().trim().isEmpty()) {
                    cachedVersion = r.stdout().trim();
                } else if (cachedVersion == null) {
                    cachedVersion = "";
                }
            } catch (Exception e) {
                AppLogger.info("Failed to get winget version: " + e.getMessage());
                if (cachedVersion == null) cachedVersion = "";
            }
            return cachedVersion;
        }
    }

    /**
     * Returns true if the installed winget version supports {@code --output json}.
     * JSON output was introduced in winget v1.4.x. This is determined by parsing the
     * version string cached by {@link #getVersion()}. Unknown/empty versions are
     * treated as "no JSON" but are NOT latched forever — the next call re-probes
     * once winget becomes available.
     */
    public boolean supportsJsonOutput() {
        if (cachedJsonSupported != null && cachedJsonSupported) return true;
        synchronized (WingetRunner.class) {
            if (cachedJsonSupported != null && cachedJsonSupported) return cachedJsonSupported;
            String ver = getVersion();
            boolean supported = parseMajorMinorVersion(ver) >= 1.4;
            // Latch positives forever; negatives only when we actually know the version.
            // Empty version (winget missing) stays retryable.
            if (supported || (ver != null && !ver.isBlank())) {
                cachedJsonSupported = supported;
            }
            return supported;
        }
    }

    private static double parseMajorMinorVersion(String version) {
        if (version == null || version.isEmpty()) return 0;
        Matcher m = VERSION_PATTERN.matcher(version);
        if (m.find()) {
            try {
                String[] parts = m.group(1).split("\\.");
                int major = Integer.parseInt(parts[0]);
                int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                return major + minor / 10.0;
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * Resolves the winget executable path using multiple strategies.
     * Thread-safe JVM-wide: uses double-checked locking on the class.
     */
    public String resolvePath() {
        if (resolvedPath != null) return resolvedPath;
        synchronized (WingetRunner.class) {
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
     * Fallback applies ONLY when the launcher itself could not start winget
     * (missing exe, "not recognized", exit 9009, IOException). Once winget
     * actually runs, its result is returned immediately -- even on failure --
     * so a failed install/scan is never re-executed via the next shell
     * (previously a failed upgrade ran up to 3x: direct, cmd, powershell).
     * Caches which candidate form launched winget so the next call tries it first.
     */
    public ProcessResult runWithFallback(long timeoutSeconds, String... args) {
        List<List<String>> candidates = buildCandidates(args);
        ProcessResult lastResult = null;
        Exception lastEx = null;
        int startIdx = workingCandidateIndex >= 0 && workingCandidateIndex < candidates.size()
                ? workingCandidateIndex : 0;
        for (int attempt = 0; attempt < candidates.size(); attempt++) {
            int idx = (startIdx + attempt) % candidates.size();
            List<String> candidate = candidates.get(idx);
            try {
                ProcessResult r = runner.run(candidate, timeoutSeconds);
                if (r.success()) {
                    workingCandidateIndex = idx;
                    return r;
                }
                if (!isLauncherFailure(r)) {
                    // winget ran and reported its own failure: remember the
                    // working launcher but do NOT re-run via another shell.
                    workingCandidateIndex = idx;
                    return r;
                }
                lastResult = r;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during winget fallback", e);
            } catch (Exception ex) {
                lastEx = ex;
            }
        }
        if (lastResult != null) return lastResult;
        if (lastEx instanceof RuntimeException re) throw re;
        if (lastEx != null) throw new RuntimeException("winget fallback failed", lastEx);
        return null;
    }

    public ProcessResult runWithFallback(long timeoutSeconds, java.util.concurrent.atomic.AtomicBoolean cancelled, String... args) throws java.io.IOException, InterruptedException {
        if (cancelled != null && cancelled.get()) throw new java.util.concurrent.CancellationException("Cancelled");
        List<List<String>> candidates = buildCandidates(args);
        ProcessResult lastResult = null;
        Exception lastEx = null;
        int startIdx = workingCandidateIndex >= 0 && workingCandidateIndex < candidates.size() ? workingCandidateIndex : 0;
        for (int attempt = 0; attempt < candidates.size(); attempt++) {
            if (cancelled != null && cancelled.get()) throw new java.util.concurrent.CancellationException("Cancelled");
            int idx = (startIdx + attempt) % candidates.size();
            List<String> candidate = candidates.get(idx);
            try {
                ProcessResult r = runner.run(candidate, timeoutSeconds, cancelled);
                if (r.success()) {
                    workingCandidateIndex = idx;
                    return r;
                }
                if (!isLauncherFailure(r)) {
                    // winget ran: cache launcher, return installer/scan failure as-is.
                    workingCandidateIndex = idx;
                    return r;
                }
                lastResult = r;
            } catch (java.util.concurrent.CancellationException ce) {
                throw ce;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception ex) {
                lastEx = ex;
                if (cancelled != null && cancelled.get()) throw new java.util.concurrent.CancellationException("Cancelled");
            }
        }
        if (lastResult != null) return lastResult;
        if (lastEx instanceof java.io.IOException) throw (java.io.IOException) lastEx;
        if (lastEx instanceof InterruptedException) throw (InterruptedException) lastEx;
        if (lastEx instanceof RuntimeException re) throw re;
        if (lastEx != null) throw new java.io.IOException("winget fallback failed", lastEx);
        return null;
    }

    public ProcessResult runWithFallback(long timeoutSeconds, java.util.function.BooleanSupplier cancelledSupplier, String... args) {
        if (cancelledSupplier == null) {
            try { return runWithFallback(timeoutSeconds, (java.util.concurrent.atomic.AtomicBoolean) null, args); } catch (Exception e) { throw new RuntimeException(e); }
        }
        if (cancelledSupplier instanceof java.util.concurrent.atomic.AtomicBoolean ab) {
            try { return runWithFallback(timeoutSeconds, ab, args); } catch (Exception e) { throw new RuntimeException(e); }
        }
        try (com.sbtools.util.CancelBridge bridge =
                     com.sbtools.util.CancelBridge.bridge(cancelledSupplier, "winget-cancel-monitor")) {
            try {
                return runWithFallback(timeoutSeconds, bridge.flag(), args);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Runs a winget command in streaming mode with automatic fallback across candidates.
     * Fallback applies ONLY when the launcher could not start winget. Once winget
     * runs (including reboot-required and installer-failure results), the result
     * is returned immediately without re-executing via the next shell.
     * Calls lineCallback for each output line and progressCallback for progress updates.
     * Caches which candidate form launched winget so the next call tries it first.
     */
    public ProcessResult runWithFallbackStreaming(Consumer<String> lineCallback,
                                                   Consumer<Double> progressCallback,
                                                   AtomicBoolean cancelled,
                                                   long timeoutSeconds,
                                                   String... args) throws java.io.IOException, java.util.concurrent.CancellationException {
        List<List<String>> candidates = buildCandidates(args);
        ProcessResult lastResult = null;
        Exception lastEx = null;
        int startIdx = workingCandidateIndex >= 0 && workingCandidateIndex < candidates.size()
                ? workingCandidateIndex : 0;
        for (int attempt = 0; attempt < candidates.size(); attempt++) {
            int idx = (startIdx + attempt) % candidates.size();
            List<String> candidate = candidates.get(idx);
            try {
                ProcessResult r = runner.runStreaming(candidate, lineCallback, progressCallback, cancelled, timeoutSeconds);
                if (r.success()) {
                    workingCandidateIndex = idx;
                    return r;
                }
                // A reboot-required result (MSI 3010/1641 or reboot phrasing) means the installer
                // already ran: return immediately instead of re-executing the same upgrade via the
                // next launcher candidate, which would reinstall or report a false failure.
                // (Mirrors SoftwareUpdateService.isRebootRequired; inlined to avoid a class cycle.)
                if (isRebootRequiredResult(r)) {
                    workingCandidateIndex = idx;
                    return r;
                }
                if (!isLauncherFailure(r)) {
                    // winget ran and failed on its own terms (package failure,
                    // hash mismatch, no update): do NOT retry via another shell.
                    workingCandidateIndex = idx;
                    return r;
                }
                lastResult = r;
            } catch (java.util.concurrent.CancellationException cex) {
                throw cex;
            } catch (Exception ex) {
                // Timeouts mean the installer hung and was killed: do not
                // re-run it via another shell (would triple a 1200s hang).
                // Only IOExceptions from process startup ("file not found")
                // justify trying the next launcher.
                if (isTimeoutException(ex)) {
                    if (ex instanceof java.io.IOException ioe) throw ioe;
                    throw new java.io.IOException("winget streaming timed out", ex);
                }
                lastEx = ex;
            }
        }
        if (lastResult != null) return lastResult;
        if (lastEx != null) throw new java.io.IOException("Streaming failed", lastEx);
        return new ProcessResult(-1, "", "No candidates");
    }

    /**
     * True only when the launcher itself could not start winget (missing exe,
     * PATH miss, cmd/PowerShell "not recognized"). Any output proving winget
     * ran returns false so callers never re-execute a failed install/scan.
     */
    static boolean isLauncherFailure(ProcessResult r) {
        if (r == null) return true;
        if (r.exitCode() == 9009) return true;
        String out = r.combinedOutput();
        if (out == null || out.isBlank()) {
            // winget almost always prints something when it runs; blank with
            // non-zero is treated as launcher failure to preserve fallback for
            // truly missing binaries, at most trying the next shell once.
            return true;
        }
        String lower = out.toLowerCase();
        if (lower.contains("not recognized")
                || lower.contains("not recognised")
                || lower.contains("command not found")
                || lower.contains("is not recognized as an internal")
                || lower.contains("the term 'winget' is not recognized")
                || lower.contains("cannot find") && lower.contains("winget")
                || lower.contains("file not found") && lower.contains("winget")) {
            return true;
        }
        return false;
    }

    private static boolean isTimeoutException(Throwable ex) {
        if (ex == null) return false;
        String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        return msg.contains("timed out");
    }

    /**
     * Minimal reboot-required check for fallback control (see SoftwareUpdateService.isRebootRequired
     * for the canonical, JSON-aware version). Kept local to avoid a WingetRunner ↔
     * SoftwareUpdateService class cycle. Negation-aware: "No reboot required",
     * "reboot not required" and "rebootRequired":false do NOT count.
     */
    private static boolean isRebootRequiredResult(ProcessResult r) {
        if (r == null) return false;
        if (r.exitCode() == ProcessResult.MSI_SUCCESS_REBOOT_REQUIRED
                || r.exitCode() == ProcessResult.MSI_SUCCESS_REBOOT_INITIATED) return true;
        String out = r.combinedOutput();
        if (out == null || out.isBlank()) return false;
        return containsAffirmativeReboot(out);
    }

    /**
     * Shared negation-aware reboot phrasing check. Each line carrying a reboot
     * token is ignored when the same line carries an explicit negation
     * ({@code no / not / n't / without / false / :false}).
     */
    static boolean containsAffirmativeReboot(String output) {
        if (output == null || output.isBlank()) return false;
        String lower = output.toLowerCase();
        // Fast path for compact JSON tokens: must rule out explicit false first,
        // otherwise '{"rebootRequired":false}' would false-positive.
        for (String line : lower.split("\\r?\\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            boolean hasCompact = l.contains("rebootrequired") || l.contains("restartrequired")
                    || l.contains("error_success_reboot_required");
            boolean hasPhrase = l.contains("reboot required") || l.contains("restart required")
                    || l.contains("restart is required") || l.contains("a reboot is required")
                    || l.contains("please reboot") || l.contains("please restart");
            // "a restart" alone is too broad ("a restart from scratch"); only
            // count it with a requirement verb nearby.
            boolean hasBareRestart = !hasCompact && !hasPhrase
                    && l.contains("a restart")
                    && (l.contains("requir") || l.contains("needed") || l.contains("necessary"));
            if (!hasCompact && !hasPhrase && !hasBareRestart) continue;
            if (isNegatedRebootLine(l)) continue;
            if (hasCompact) {
                // Compact token without negation on the same line is affirmative
                // (covers wu-install JSON compressed form).
                return true;
            }
            return true;
        }
        return false;
    }

    private static boolean isNegatedRebootLine(String lowerLine) {
        String l = lowerLine;
        // Explicit JSON false: "rebootRequired":false, 'rebootRequired'=false, = 0
        if (l.matches(".*rebootrequired\"?\\s*[:=]\\s*false.*")) return true;
        if (l.matches(".*restartrequired\"?\\s*[:=]\\s*false.*")) return true;
        if (l.matches(".*rebootrequired\"?\\s*[:=]\\s*0\\b.*")) return true;
        if (l.matches(".*restartrequired\"?\\s*[:=]\\s*0\\b.*")) return true;
        if (l.contains("rebootrequired") || l.contains("restartrequired")) {
            // Compact token with a nearby negation word on the same line.
            if (l.contains("no ") || l.contains("not ") || l.contains("n't")
                    || l.contains("without") || l.contains("never") || l.contains("false")
                    || l.contains("none")) return true;
            return false;
        }
        // Human-readable phrasing negations.
        if (l.matches(".*\\bno\\s+(reboot|restart)\\b.*")) return true;
        if (l.matches(".*\\b(reboot|restart)\\b[^\\n]*?\\bnot\\s+(required|needed|necessary)\\b.*")) return true;
        if (l.matches(".*\\bnot\\s+requir[^\\n]*?\\b(reboot|restart)\\b.*")) return true;
        if (l.matches(".*n['’]t\\s+requir[^\\n]*?\\b(reboot|restart)\\b.*")) return true;
        if (l.matches(".*without\\s+[^\\n]*?\\b(reboot|restart)\\b.*")) return true;
        if (l.matches(".*\\b(reboot|restart)\\b[^\\n]*?\\bno\\s+(reboot|restart)?\\s*(required|needed|necessary)\\b.*")
                && l.contains("no ")) return true;
        return false;
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
            // B3 FIX: use PowerShell single-quote escaping ('' for ') — the old
            // double-quote + backslash scheme ("\"") is NOT a PowerShell escape and
            // allowed a registry-controlled app name containing '"' to break out of
            // the "& { ... }" wrapper and inject commands. Strip CR/LF as well.
            String noLines = a.replace('\r', ' ').replace('\n', ' ');
            if (noLines.isEmpty() || noLines.matches("[A-Za-z0-9_\\-\\./:=]+")) {
                sb.append(noLines);
            } else {
                sb.append('\'').append(noLines.replace("'", "''")).append('\'');
            }
        }
        return sb.toString();
    }
}
