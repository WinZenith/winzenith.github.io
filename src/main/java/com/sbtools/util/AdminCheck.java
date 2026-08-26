package com.sbtools.util;

import com.sun.jna.platform.win32.Shell32;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-Command",
                    "([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)"
            );
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            return "True".equalsIgnoreCase(out);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
        // 1) Packaged .exe — just re-launch it elevated
        String exePath = getExePath();
        if (exePath != null && new java.io.File(exePath).exists()) {
            String lower = exePath.toLowerCase();
            boolean isJavaBinary = lower.endsWith("java.exe") || lower.endsWith("javaw.exe");
            if (!isJavaBinary) {
                String cmd = String.format(
                        "Start-Process -FilePath '%s' -Verb RunAs",
                        exePath.replace("'", "''")
                );
                new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", cmd).start();
                return true;
            }
        }

        // 2) IntelliJ / IDE — use JNA ShellExecute "runas" with the current command line
        String currentCmd = ProcessHandle.current().info().commandLine().orElse(null);
        if (currentCmd != null && !currentCmd.isBlank()) {
            String[] parsed = parseExeAndArgs(currentCmd);
            if (parsed != null) {
                String file = parsed[0];
                String args = parsed[1];
                long rc = Shell32.INSTANCE.ShellExecute(
                        null, "runas", file,
                        args != null ? args : "",
                        null, 1 /*SW_SHOWNORMAL*/).longValue();
                if (rc > 32) return true;
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

        long rc = Shell32.INSTANCE.ShellExecute(
                null, "runas", javaBin,
                argsBuilder.toString(),
                null, 1).longValue();
        return rc > 32;
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
        try {
            String classPath = AdminCheck.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            if (classPath.contains(".exe") && classPath.toLowerCase().endsWith(".exe")) {
                java.io.File exeFile = new java.io.File(classPath);
                if (exeFile.exists()) {
                    return exeFile.getAbsolutePath();
                }
            }
        } catch (Exception e) {
        }
        try {
            String command = ProcessHandle.current().info().command().orElse(null);
            if (command != null) {
                java.io.File exeFile = new java.io.File(command);
                if (exeFile.exists() && exeFile.getName().toLowerCase().endsWith(".exe")) {
                    return exeFile.getAbsolutePath();
                }
            }
        } catch (Exception e) {
        }
        return null;
    }
}
