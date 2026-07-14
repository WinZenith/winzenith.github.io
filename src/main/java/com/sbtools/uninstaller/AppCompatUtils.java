package com.sbtools.uninstaller;

public final class AppCompatUtils {

    private AppCompatUtils() {
    }

    public static boolean isMicrosoftOrWindows(String publisher, String displayName) {
        String lowerPub = publisher != null ? publisher.toLowerCase() : "";
        String lowerName = displayName != null ? displayName.toLowerCase() : "";
        boolean isMicrosoftPublisher = lowerPub.contains("microsoft");
        boolean isMicrosoftOrWindowsName = lowerName.startsWith("microsoft ")
                || lowerName.equals("microsoft windows")
                || lowerName.matches("(?i)microsoft windows .*");
        return isMicrosoftPublisher || isMicrosoftOrWindowsName;
    }

    public static boolean isMicrosoftOrWindows(InstalledApp app) {
        return isMicrosoftOrWindows(app.getPublisher(), app.getName());
    }
}
