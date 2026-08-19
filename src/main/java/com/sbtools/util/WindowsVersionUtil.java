package com.sbtools.util;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

public final class WindowsVersionUtil {

    private static final String VERSION_KEY = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion";
    private static final int KNOWN_SAFE_BUILD_THRESHOLD = 22631;

    private static volatile Integer cachedBuildNumber;
    private static volatile String cachedDisplayVersion;

    private WindowsVersionUtil() {
    }

    public static int getBuildNumber() {
        if (cachedBuildNumber != null) return cachedBuildNumber;
        try {
            String val = Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_LOCAL_MACHINE, VERSION_KEY, "CurrentBuildNumber");
            if (val != null && !val.isBlank()) {
                cachedBuildNumber = Integer.parseInt(val.trim());
                return cachedBuildNumber;
            }
        } catch (Exception ignored) {}
        cachedBuildNumber = 0;
        return cachedBuildNumber;
    }

    public static String getDisplayVersion() {
        if (cachedDisplayVersion != null) return cachedDisplayVersion;
        try {
            String val = Advapi32Util.registryGetStringValue(
                    WinReg.HKEY_LOCAL_MACHINE, VERSION_KEY, "DisplayVersion");
            if (val != null && !val.isBlank()) {
                cachedDisplayVersion = val.trim();
                return cachedDisplayVersion;
            }
        } catch (Exception ignored) {}
        cachedDisplayVersion = "";
        return cachedDisplayVersion;
    }

    public static boolean isNewerThanKnownSafeBuild() {
        return getBuildNumber() > KNOWN_SAFE_BUILD_THRESHOLD;
    }

    public static boolean isPreviewBuild() {
        String dv = getDisplayVersion().toLowerCase();
        return dv.contains("dev") || dv.contains("canary")
                || dv.contains("beta") || dv.contains("release preview")
                || isNewerThanKnownSafeBuild();
    }

    public static String getWindowsVersionString() {
        int build = getBuildNumber();
        String dv = getDisplayVersion();
        if (build <= 0) return "Unknown Windows version";
        StringBuilder sb = new StringBuilder("Windows (Build ").append(build).append(")");
        if (!dv.isEmpty()) sb.append(" [").append(dv).append("]");
        return sb.toString();
    }
}
