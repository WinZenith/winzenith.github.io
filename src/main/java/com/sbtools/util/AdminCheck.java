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

    public static void requestElevation() throws IOException {
        String exePath = getExePath();
        if (exePath != null && new java.io.File(exePath).exists()) {
            String cmd = String.format(
                    "Start-Process -FilePath '%s' -Verb RunAs",
                    exePath.replace("'", "''")
            );
            new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", cmd).start();
        } else {
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + "\\bin\\javaw.exe";
            String modulePath = System.getProperty("jdk.module.path");
            String classPath = System.getProperty("java.class.path");

            boolean hasModuleInfo = isModular();

            StringBuilder args = new StringBuilder();
            args.append("--enable-native-access=ALL-UNNAMED,javafx.graphics");

            if (hasModuleInfo && modulePath != null && !modulePath.isEmpty()) {
                args.append(" --module-path \"").append(modulePath).append("\"");
                args.append(" --module com.winzenith/com.sbtools.App");
            } else if (hasModuleInfo) {
                args.append(" --module-path \"").append(classPath).append("\"");
                args.append(" --module com.winzenith/com.sbtools.App");
            } else {
                if (modulePath != null && !modulePath.isEmpty()) {
                    args.append(" --module-path \"").append(modulePath).append("\"");
                }
                args.append(" --add-modules javafx.controls");
                args.append(" -cp \"").append(classPath).append("\"");
                args.append(" com.sbtools.App");
            }

            String psArgs = args.toString().replace("'", "''");
            String psJavaBin = javaBin.replace("'", "''");

            String cmd = String.format(
                    "Start-Process -FilePath '%s' -ArgumentList '%s' -Verb RunAs",
                    psJavaBin, psArgs
            );
            new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", cmd).start();
        }
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
