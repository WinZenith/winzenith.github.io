package com.sbtools.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class AdminCheck {

    private AdminCheck() {
    }

    public static boolean isRunningAsAdmin() {
        if (!AppPaths.isWindows()) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-Command",
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

    public static void requestElevation() throws IOException {
        // Only use EXE for elevation if actually running from EXE (for custom UAC icon)
        String exePath = getExePath();
        
        if (exePath != null && new java.io.File(exePath).exists()) {
            // Running from EXE, use it for elevation (shows custom icon)
            String cmd = String.format(
                    "Start-Process -FilePath '%s' -Verb RunAs",
                    exePath.replace("'", "''")
            );
            new ProcessBuilder("powershell", "-NoProfile", "-Command", cmd).start();
        } else {
            // Running from JAR/IntelliJ, use javaw.exe with proper JVM arguments (shows Java icon)
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + "\\bin\\javaw.exe";
            String classPath = System.getProperty("java.class.path");
            String modulePath = System.getProperty("jdk.module.path");
            String mainClass = "com.sbtools.App";
            
            StringBuilder args = new StringBuilder();
            args.append("'--enable-native-access=javafx.graphics'");
            if (modulePath != null && !modulePath.isEmpty()) {
                args.append(",'--module-path','").append(modulePath.replace("'", "''")).append("'");
            }
            args.append(",'--add-modules','javafx.controls'");
            args.append(",'-cp','").append(classPath.replace("'", "''")).append("'");
            args.append(",'").append(mainClass).append("'");
            
            String cmd = String.format(
                    "Start-Process -FilePath '%s' -ArgumentList %s -Verb RunAs",
                    javaBin.replace("'", "''"),
                    args.toString()
            );
            new ProcessBuilder("powershell", "-NoProfile", "-Command", cmd).start();
        }
    }
    
    private static String getExePath() {
        try {
            String classPath = AdminCheck.class.getProtectionDomain().getCodeSource().getLocation().getPath();
            // Only return EXE path if actually running from EXE (not just if it exists in target)
            if (classPath.contains(".exe") && classPath.toLowerCase().endsWith(".exe")) {
                java.io.File exeFile = new java.io.File(classPath);
                if (exeFile.exists()) {
                    return exeFile.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            // Ignore errors, fall back to javaw.exe
        }
        return null;
    }
}
