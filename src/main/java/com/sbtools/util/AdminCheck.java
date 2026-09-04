package com.sbtools.util;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.ptr.IntByReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class AdminCheck {

    private static volatile Boolean cachedAdmin = null;
    private static volatile long cacheTimestampMs = 0;
    private static final long CACHE_TTL_MS = 15_000; // 15s TTL to allow elevation changes without full restart
    private static final Object CACHE_LOCK = new Object();

    private AdminCheck() {
    }

    public static boolean isRunningAsAdmin() {
        if (!AppPaths.isWindows()) {
            return false;
        }
        Boolean cached = cachedAdmin;
        long now = System.currentTimeMillis();
        if (cached != null && (now - cacheTimestampMs) < CACHE_TTL_MS) {
            return cached;
        }
        synchronized (CACHE_LOCK) {
            cached = cachedAdmin;
            now = System.currentTimeMillis();
            if (cached != null && (now - cacheTimestampMs) < CACHE_TTL_MS) {
                return cached;
            }
            boolean result = computeIsAdmin();
            cachedAdmin = result;
            cacheTimestampMs = now;
            return result;
        }
    }

    /** Force a fresh check bypassing TTL (use before destructive operations). */
    public static boolean isRunningAsAdminFresh() {
        invalidateCache();
        return isRunningAsAdmin();
    }

    private static boolean computeIsAdmin() {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)"
            );
            // Merge stderr so a noisy profile cannot fill the pipe and deadlock the process.
            pb.redirectErrorStream(true);
            p = pb.start();
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return false;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return "True".equalsIgnoreCase(out);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    public static void warmCacheAsync() {
        try {
            com.sbtools.util.AppExecutors.ioPool().submit(AdminCheck::isRunningAsAdmin);
        } catch (Exception ignored) {
            // Fallback thread if pool not ready yet
            Thread t = new Thread(AdminCheck::isRunningAsAdmin, "admin-warmup");
            t.setDaemon(true);
            t.start();
        }
    }

    public static void invalidateCache() {
        synchronized (CACHE_LOCK) {
            cachedAdmin = null;
            cacheTimestampMs = 0;
        }
    }

    public static java.util.concurrent.CompletableFuture<Boolean> isRunningAsAdminAsync() {
        Boolean cached = cachedAdmin;
        if (cached != null) {
            return java.util.concurrent.CompletableFuture.completedFuture(cached);
        }
        return java.util.concurrent.CompletableFuture.supplyAsync(AdminCheck::isRunningAsAdmin,
                com.sbtools.util.AppExecutors.ioPool());
    }

    public static boolean requestElevation() throws IOException {
        ElevationResult result = requestElevation(new String[0]);
        return result == ElevationResult.ELEVATED_CHILD_STARTED;
    }

    /** Outcome of a synchronous elevation attempt (UAC accepted vs cancelled). */
    public enum ElevationResult {
        /** UAC was accepted and an elevated child is starting; parent should exit. */
        ELEVATED_CHILD_STARTED,
        /** User cancelled UAC; caller should continue unelevated. */
        DENIED_BY_USER,
        /** Elevation could not be started; caller should continue unelevated. */
        FAILED,
        /**
         * UAC was accepted but the child died instantly (wrong exe, missing modules).
         * The user already consented — never prompt again for this launch, just
         * continue unelevated.
         */
        CHILD_DIED
    }

    /**
     * Synchronously requests elevation for the current launch, forwarding the given
     * app args plus a loop-guard marker. Uses {@code ShellExecuteEx("runas")} with
     * {@code SEE_MASK_NOCLOSEPROCESS} so UAC Cancel is reported back instead of
     * fire-and-forget. Blocks until the user answers the UAC prompt.
     *
     * <p>Guarantee: at most ONE UAC prompt per call. A consented-but-dead child
     * ({@link ElevationResult#CHILD_DIED}) is terminal — falling through to another
     * strategy would show a second prompt for a single launch.</p>
     */
    public static ElevationResult requestElevation(String[] appArgs) throws IOException {
        List<String> forwardedAppArgs = withMarker(appArgs);

        // 1) Packaged .exe (jpackage app-image launcher only — a bare Launch4j exe
        // without the cfg module-path cannot boot this JavaFX app, so getExePath()
        // no longer returns those) — re-launch it elevated.
        String exePath = getExePath();
        System.err.println("[AdminCheck] Elevation target: " + exePath);
        if (exePath != null && new java.io.File(exePath).exists()) {
            String lower = exePath.toLowerCase();
            boolean isJavaBinary = lower.endsWith("java.exe") || lower.endsWith("javaw.exe");
            if (!isJavaBinary) {
                ElevationResult r = elevateViaShellExecuteEx(exePath, joinQuoted(forwardedAppArgs));
                if (r == ElevationResult.CHILD_DIED) {
                    return ElevationResult.FAILED;
                }
                if (r != ElevationResult.FAILED) {
                    return r;
                }
                // Fall through to java-based strategies on failure.
            }
        }

        // 2) IDE / java launch — re-launch the current java binary with the current
        // arguments (JVM opts + main class + app args) plus the marker.
        // Skipped when the JVM hides arguments(): elevating with args missing would
        // start a broken child, so fall through to 2b/3 instead.
        try {
            ProcessHandle.Info info = ProcessHandle.current().info();
            String command = info.command().orElse(null);
            String[] rawArgs = info.arguments().orElse(null);
            if (command != null && !command.isBlank() && new java.io.File(command).exists()
                    && rawArgs != null && rawArgs.length > 0) {
                List<String> currentArgs = new ArrayList<>(Arrays.asList(rawArgs));
                if (!currentArgs.contains(ElevationGate.ARG_ELEVATED_RELAUNCH)
                        && !currentArgs.contains(ElevationGate.ARG_NO_ELEVATE_PROMPT)) {
                    currentArgs.add(ElevationGate.ARG_ELEVATED_RELAUNCH);
                }
                ElevationResult r = elevateViaShellExecuteEx(command, joinQuoted(currentArgs));
                if (r == ElevationResult.CHILD_DIED) {
                    return ElevationResult.FAILED;
                }
                if (r != ElevationResult.FAILED) {
                    return r;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to reconstruction.
        }

        // 2b) Legacy fallback: parse the raw command line (some JVMs hide arguments()).
        String currentCmd = ProcessHandle.current().info().commandLine().orElse(null);
        if (currentCmd != null && !currentCmd.isBlank()) {
            String[] parsed = parseExeAndArgs(currentCmd);
            if (parsed != null) {
                String file = parsed[0];
                String args = parsed[1] != null ? parsed[1] : "";
                if (!args.contains(ElevationGate.ARG_ELEVATED_RELAUNCH)) {
                    args = args.isBlank() ? ElevationGate.ARG_ELEVATED_RELAUNCH
                            : args + " " + quoteArg(ElevationGate.ARG_ELEVATED_RELAUNCH);
                }
                ElevationResult r = elevateViaShellExecuteEx(file, args);
                if (r == ElevationResult.CHILD_DIED) {
                    return ElevationResult.FAILED;
                }
                if (r != ElevationResult.FAILED) {
                    return r;
                }
            }
        }

        // 3) Last resort: reconstruct from system properties
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + "\\bin\\javaw.exe";
        String modulePath = System.getProperty("jdk.module.path");
        String classPath = System.getProperty("java.class.path");

        StringBuilder argsBuilder = new StringBuilder();
        argsBuilder.append("--enable-native-access=ALL-UNNAMED,javafx.graphics");

        if (isModular()) {
            String mp = modulePath != null && !modulePath.isEmpty() ? modulePath : classPath;
            argsBuilder.append(" --module-path \"").append(mp).append("\"");
            argsBuilder.append(" --module com.winzenith/com.sbtools.App");
        } else {
            if (modulePath != null && !modulePath.isEmpty()) {
                argsBuilder.append(" --module-path \"").append(modulePath).append("\"");
            }
            argsBuilder.append(" --add-modules javafx.controls");
            argsBuilder.append(" -cp \"").append(classPath).append("\"");
            argsBuilder.append(" com.sbtools.App");
        }
        for (String a : forwardedAppArgs) {
            argsBuilder.append(' ').append(quoteArg(a));
        }

        return elevateViaShellExecuteEx(javaBin, argsBuilder.toString());
    }

    // SEE_MASK_NOCLOSEPROCESS (ShellAPI.h): return hProcess so the call blocks
    // until the UAC prompt is answered and reports Cancel via GetLastError()=1223.
    private static final int SEE_MASK_NOCLOSEPROCESS = 0x00000040;
    private static final int SW_SHOWNORMAL = 1;
    private static final int ERROR_CANCELLED = 1223;
    /**
     * How long to watch the elevated child before trusting the handoff.
     * A healthy child is still booting its JVM at this point; an instant exit
     * means a broken relaunch (wrong exe, missing modules) and the parent must
     * stay alive unelevated instead of leaving the user with nothing.
     */
    private static final int CHILD_SURVIVAL_WAIT_MS = 3000;

    private static ElevationResult elevateViaShellExecuteEx(String file, String params) {
        if (file == null || file.isBlank() || !new java.io.File(file).exists()) {
            return ElevationResult.FAILED;
        }
        ShellAPI.SHELLEXECUTEINFO info = new ShellAPI.SHELLEXECUTEINFO();
        try {
            info.cbSize = info.size();
            info.fMask = SEE_MASK_NOCLOSEPROCESS;
            info.hwnd = null;
            info.lpVerb = "runas";
            info.lpFile = file;
            info.lpParameters = params != null ? params : "";
            info.lpDirectory = null;
            info.nShow = SW_SHOWNORMAL;
            boolean ok = Shell32.INSTANCE.ShellExecuteEx(info);
            if (!ok) {
                int err;
                try {
                    err = Kernel32.INSTANCE.GetLastError();
                } catch (Throwable ignored) {
                    return ElevationResult.FAILED;
                }
                return err == ERROR_CANCELLED ? ElevationResult.DENIED_BY_USER : ElevationResult.FAILED;
            }
            // The UAC prompt was accepted and the child started. Never trust a blind
            // handoff: if the elevated child dies instantly (wrong exe, missing modules,
            // bad args) the user would otherwise see "click Yes then nothing happens".
            // Wait briefly; only tell the parent to exit when the child is confirmed alive.
            try {
                int wait = Kernel32.INSTANCE.WaitForSingleObject(info.hProcess, CHILD_SURVIVAL_WAIT_MS);
                if (wait == WinBase.WAIT_OBJECT_0) {
                    int exitCode = -1;
                    try {
                        IntByReference code = new IntByReference();
                        if (Kernel32.INSTANCE.GetExitCodeProcess(info.hProcess, code)) {
                            exitCode = code.getValue();
                        }
                    } catch (Throwable ignored) {
                    }
                    System.err.println("[AdminCheck] Elevated child exited immediately (exit=" + exitCode
                            + ") for: " + file + ". Continuing unelevated.");
                    try {
                        AppLogger.warning("Elevated child died instantly (exit=" + exitCode + ") for: " + file);
                    } catch (Throwable ignored) {
                    }
                    return ElevationResult.CHILD_DIED;
                }
            } catch (Throwable t) {
                // If the wait itself fails, fall through and trust the successful launch.
                System.err.println("[AdminCheck] Child survival check unavailable: " + t.getMessage());
            }
            return ElevationResult.ELEVATED_CHILD_STARTED;
        } catch (Throwable t) {
            return ElevationResult.FAILED;
        } finally {
            try {
                if (info.hProcess != null) {
                    Kernel32.INSTANCE.CloseHandle(info.hProcess);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static List<String> withMarker(String[] appArgs) {
        List<String> out = new ArrayList<>();
        if (appArgs != null) {
            out.addAll(Arrays.asList(appArgs));
        }
        if (!out.contains(ElevationGate.ARG_ELEVATED_RELAUNCH)
                && !out.contains(ElevationGate.ARG_NO_ELEVATE_PROMPT)) {
            out.add(ElevationGate.ARG_ELEVATED_RELAUNCH);
        }
        return out;
    }

    private static String joinQuoted(List<String> args) {
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(quoteArg(a));
        }
        return sb.toString();
    }

    /** Windows C-runtime compatible quoting for a single argument. */
    static String quoteArg(String arg) {
        if (arg == null || arg.isEmpty()) {
            return "\"\"";
        }
        boolean needsQuotes = arg.chars().anyMatch(c -> c == ' ' || c == '\t' || c == '"' || c == '\'');
        if (!needsQuotes) {
            return arg;
        }
        StringBuilder sb = new StringBuilder("\"");
        int backslashes = 0;
        for (int i = 0; i < arg.length(); i++) {
            char c = arg.charAt(i);
            if (c == '\\') {
                backslashes++;
            } else if (c == '"') {
                for (int j = 0; j < backslashes * 2 + 1; j++) {
                    sb.append('\\');
                }
                backslashes = 0;
                sb.append('"');
            } else {
                for (int j = 0; j < backslashes; j++) {
                    sb.append('\\');
                }
                backslashes = 0;
                sb.append(c);
            }
        }
        for (int j = 0; j < backslashes * 2; j++) {
            sb.append('\\');
        }
        sb.append('"');
        return sb.toString();
    }

    private static String[] parseExeAndArgs(String commandLine) {
        String trimmed = commandLine.trim();
        String exe;
        String args = null;

        if (trimmed.startsWith("\"")) {
            int endQuote = trimmed.indexOf('"', 1);
            if (endQuote < 0) return null;
            exe = trimmed.substring(1, endQuote);
            if (endQuote + 1 < trimmed.length()) {
                args = trimmed.substring(endQuote + 1).trim();
            }
        } else {
            int space = trimmed.indexOf(' ');
            if (space < 0) {
                exe = trimmed;
            } else {
                exe = trimmed.substring(0, space);
                args = trimmed.substring(space + 1).trim();
            }
        }

        if (!new java.io.File(exe).exists()) return null;
        return new String[]{exe, args};
    }

    private static boolean isModular() {
        String modulePath = System.getProperty("jdk.module.path");
        if (modulePath != null && !modulePath.isEmpty()) {
            return true;
        }
        return findModuleInfoClass() != null;
    }

    private static java.io.File findModuleInfoClass() {
        java.security.CodeSource cs = AdminCheck.class.getProtectionDomain().getCodeSource();
        if (cs == null || cs.getLocation() == null) return null;
        java.io.File codeDir = new java.io.File(cs.getLocation().getPath());
        java.io.File moduleInfo = new java.io.File(codeDir, "module-info.class");
        return moduleInfo.exists() ? moduleInfo : null;
    }

    private static String getExePath() {
        java.util.List<java.io.File> candidates = new java.util.ArrayList<>();
        // 1) Current process image: for jpackage/Launch4j this IS WinZenith.exe.
        try {
            String command = ProcessHandle.current().info().command().orElse(null);
            if (command != null) {
                java.io.File exeFile = new java.io.File(command);
                if (exeFile.exists()) {
                    String name = exeFile.getName().toLowerCase();
                    if (name.endsWith(".exe") && !name.equals("java.exe") && !name.equals("javaw.exe")) {
                        candidates.add(exeFile.getAbsoluteFile());
                        // Launched from inside app/ (e.g. the Launch4j copy jpackage stages
                        // next to the jars)? The real jpackage launcher usually sits one
                        // level up next to app/ and runtime/.
                        java.io.File above = launcherAbove(exeFile.getParentFile());
                        if (above != null) {
                            candidates.add(above);
                        }
                    } else {
                        // Remember the java binary's directory: a packaged WinZenith.exe
                        // is often a sibling (app-image root) of the runtime binary.
                        java.io.File sibling = findSiblingExe(exeFile.getParentFile());
                        if (sibling != null) {
                            candidates.add(sibling);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 2) CodeSource location (Launch4j exe, or app/*.jar next to the exe).
        try {
            var location = AdminCheck.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                java.io.File codeFile = new java.io.File(location.toURI());
                if (codeFile.isFile() && codeFile.getName().toLowerCase().endsWith(".exe")
                        && codeFile.exists()) {
                    candidates.add(codeFile.getAbsoluteFile());
                }
                java.io.File base = codeFile.isFile() ? codeFile.getParentFile() : codeFile;
                java.io.File sibling = findSiblingExe(base);
                if (sibling != null) {
                    candidates.add(sibling);
                }
                // jpackage layout: code is app/*.jar, exe is one level up.
                if (base != null) {
                    java.io.File above = launcherAbove(base);
                    if (above != null) {
                        candidates.add(above);
                    } else if (base.getParentFile() != null) {
                        sibling = findSiblingExe(base.getParentFile());
                        if (sibling != null) {
                            candidates.add(sibling);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return pickBestExe(candidates);
    }

    /**
     * If {@code dir} looks like a jpackage {@code app/} directory (jars + launcher
     * config inside, real launcher one level up), returns the top-level launcher.
     */
    private static java.io.File launcherAbove(java.io.File dir) {
        try {
            if (dir == null || !dir.isDirectory()) {
                return null;
            }
            java.io.File parent = dir.getParentFile();
            if (parent == null || !parent.isDirectory()) {
                return null;
            }
            java.io.File exact = new java.io.File(parent, "WinZenith.exe");
            if (exact.exists() && isJpackageLauncher(exact)) {
                return exact.getAbsoluteFile();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** A jpackage app-image launcher sits next to {@code app/} and {@code runtime/}. */
    private static boolean isJpackageLauncher(java.io.File exe) {
        try {
            java.io.File dir = exe.getParentFile();
            if (dir == null || !dir.isDirectory()) {
                return false;
            }
            return new java.io.File(dir, "app").isDirectory()
                    && new java.io.File(dir, "runtime").isDirectory();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Picks the exe to elevate: only a jpackage launcher (it carries the JavaFX
     * module-path in its .cfg). A bare {@code WinZenith.exe} from the Launch4j
     * build or the {@code target/} dir cannot boot this modular JavaFX app
     * standalone, so those are deliberately NOT returned — the JVM-argument
     * strategies below handle IDE/dev launches instead. Returns null when no
     * jpackage launcher is among the candidates.
     */
    private static String pickBestExe(java.util.List<java.io.File> candidates) {
        java.util.List<java.io.File> usable = new java.util.ArrayList<>();
        for (java.io.File c : candidates) {
            if (c == null || !c.exists()) {
                continue;
            }
            String n = c.getName().toLowerCase();
            if (!n.endsWith(".exe") || n.equals("java.exe") || n.equals("javaw.exe")) {
                continue;
            }
            boolean dup = false;
            for (java.io.File u : usable) {
                try {
                    if (u.getCanonicalPath().equals(c.getCanonicalPath())) {
                        dup = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!dup) {
                usable.add(c);
            }
        }
        for (java.io.File u : usable) {
            if (isJpackageLauncher(u)) {
                return u.getAbsolutePath();
            }
        }
        return null;
    }

    private static java.io.File findSiblingExe(java.io.File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        java.io.File exact = new java.io.File(dir, "WinZenith.exe");
        if (exact.exists()) {
            return exact;
        }
        try {
            java.io.File[] exes = dir.listFiles(f -> {
                String n = f.getName().toLowerCase();
                return f.isFile() && n.endsWith(".exe")
                        && !n.equals("java.exe") && !n.equals("javaw.exe");
            });
            if (exes != null && exes.length == 1) {
                return exes[0];
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
