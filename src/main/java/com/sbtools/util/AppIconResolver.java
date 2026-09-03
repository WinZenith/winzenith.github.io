package com.sbtools.util;

import com.sbtools.uninstaller.InstalledApp;

import java.io.File;

public class AppIconResolver {

    public static String resolveAppIconPath(InstalledApp app) {
        String loc = app.getInstallLocation();
        if (loc != null && !loc.isBlank()) {
            File file = new File(loc);
            if (file.isDirectory()) {
                String exe = findFirstExe(file);
                if (exe != null) return exe;
            } else if (file.isFile() && loc.toLowerCase().endsWith(".exe")) {
                return loc;
            }
        }
        if (app.isWin32()) {
            // Try the interactive uninstall string first, then the quiet variant
            String exePath = extractExeFromUninstallString(app.getUninstallString());
            if (exePath == null && app.hasQuietUninstallString()) {
                exePath = extractExeFromUninstallString(app.getQuietUninstallString());
            }
            if (exePath != null) return exePath;
        }
        return null;
    }

    private static String findFirstExe(File dir) {
        File[] topExes = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".exe"));
        if (topExes != null && topExes.length > 0) {
            java.util.Arrays.sort(topExes, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            // Prefer an exe whose name matches a token of the directory name; otherwise prefer
            // non-uninstaller helpers over uninstall/update helpers.
            File best = chooseBestExe(topExes);
            if (best != null) return best.getAbsolutePath();
        }
        File[] subDirs = dir.listFiles(File::isDirectory);
        if (subDirs != null) {
            java.util.Arrays.sort(subDirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File subDir : subDirs) {
                File[] subExes = subDir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".exe"));
                if (subExes != null && subExes.length > 0) {
                    java.util.Arrays.sort(subExes, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                    File best = chooseBestExe(subExes);
                    if (best != null) return best.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static File chooseBestExe(File[] exes) {
        if (exes == null || exes.length == 0) return null;
        // Score: prefer non-uninstall/update/helper exes
        File preferred = null;
        for (File f : exes) {
            String n = f.getName().toLowerCase();
            boolean isHelper = n.contains("uninstall") || n.contains("unins") || n.contains("update")
                    || n.contains("helper") || n.contains("setup") || n.contains("installer");
            if (!isHelper) {
                if (preferred == null) preferred = f;
            }
        }
        return preferred != null ? preferred : exes[0];
    }

    private static String extractExeFromUninstallString(String uninstallStr) {
        if (uninstallStr == null || uninstallStr.isBlank()) return null;
        String path = uninstallStr.trim();
        if (path.startsWith("\"")) {
            int end = path.indexOf("\"", 1);
            if (end > 1) {
                path = path.substring(1, end);
            }
        } else {
            // Unquoted commands often contain spaces in the exe path
            // (e.g. C:/Program Files/Vendor/uninstall.exe /S). Look for the
            // .exe boundary instead of naively cutting at the first space.
            String lower = path.toLowerCase();
            int exeIdx = lower.indexOf(".exe");
            if (exeIdx >= 0) {
                path = path.substring(0, exeIdx + 4).trim();
                // Strip stray leading quote if present
                if (path.startsWith("\"")) path = path.substring(1);
            } else {
                int space = path.indexOf(' ');
                if (space > 0) {
                    path = path.substring(0, space);
                }
            }
        }
        if (path.toLowerCase().endsWith(".exe")) {
            File f = new File(path);
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }
}
