package com.sbtools.util;

public final class AppInfo {

    public static final String DISPLAY_NAME = "WinZenith";
    public static final String VERSION = "1.0.7";
    public static final String GITHUB_REPO = "WinZenith/winzenith.github.io";

    public static String getVersion() {
        String v = AppInfo.class.getPackage().getImplementationVersion();
        return (v != null && !v.isBlank()) ? v : VERSION;
    }

    public static boolean isPackaged() {
        String v = AppInfo.class.getPackage().getImplementationVersion();
        return v != null && !v.isBlank();
    }

    private AppInfo() {
    }
}
