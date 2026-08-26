package com.sbtools.uninstaller;

public final class AppCompatUtils {

    private AppCompatUtils() {
    }

    public static boolean isMicrosoftOrWindows(String publisher, String displayName) {
        String lowerPub = publisher != null ? publisher.toLowerCase().trim() : "";
        String lowerName = displayName != null ? displayName.toLowerCase().trim() : "";
        // Publisher check: only filter true Microsoft corporation variants, not any publisher containing substring "microsoft"
        boolean isMicrosoftPublisher = lowerPub.equals("microsoft")
                || lowerPub.equals("microsoft corporation")
                || lowerPub.startsWith("microsoft corporation")
                || lowerPub.contains("microsoft corporation");
        boolean isMicrosoftOrWindowsName = lowerName.startsWith("microsoft ")
                || lowerName.equals("microsoft windows")
                || lowerName.matches("(?i)microsoft windows .*");
        return isMicrosoftPublisher || isMicrosoftOrWindowsName;
    }

    public static boolean isMicrosoftOrWindows(InstalledApp app) {
        return isMicrosoftOrWindows(app.getPublisher(), app.getName());
    }
}
