package com.basicsdriverupdate.util;

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
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + "\\bin\\java.exe";
        String classPath = System.getProperty("java.class.path");
        String mainClass = "com.basicsdriverupdate.App";
        String cmd = String.format(
                "Start-Process -FilePath '%s' -ArgumentList '-cp','%s','%s' -Verb RunAs",
                javaBin.replace("'", "''"),
                classPath.replace("'", "''"),
                mainClass
        );
        new ProcessBuilder("powershell", "-NoProfile", "-Command", cmd).start();
    }
}
