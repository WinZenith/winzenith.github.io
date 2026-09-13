package com.sbtools.uninstaller;

public final class AppCompatUtils {

    private AppCompatUtils() {
    }

    public static boolean isMicrosoftOrWindows(String publisher, String displayName) {
        String lowerPub = publisher != null ? publisher.toLowerCase().trim() : "";
        String lowerName = displayName != null ? displayName.toLowerCase().trim() : "";
        // Publisher check: match "microsoft" as a leading word so display-name
        // variants ("Microsoft Windows", "Microsoft Corp.", "Microsoft Studios")
        // are caught, not just "Microsoft Corporation". The contains() fallbacks
        // cover certificate DNs ("CN=Microsoft Windows, O=Microsoft ...").
        // A bare substring test is NOT used so "notmicrosoft"-style third-party
        // publishers are never hidden.
        boolean isMicrosoftPublisher = lowerPub.equals("microsoft")
                || lowerPub.startsWith("microsoft ")
                || lowerPub.startsWith("microsoft.")
                || lowerPub.contains("microsoft corporation")
                || lowerPub.contains("microsoft windows");
        // Dotted package-style names ("Microsoft.Windows.ShellExperienceHost")
        // never match the space-prefixed test, so check the dot form too.
        // Windows-named components with empty publisher are system too.
        boolean isMicrosoftOrWindowsName = lowerName.startsWith("microsoft ")
                || lowerName.startsWith("microsoft.")
                || lowerName.equals("microsoft windows")
                || lowerName.matches("(?i)microsoft windows[ .].*")
                || lowerName.startsWith("windows ")
                || lowerName.startsWith("windows.")
                || lowerName.equals("windows");
        return isMicrosoftPublisher || isMicrosoftOrWindowsName;
    }

    public static boolean isMicrosoftOrWindows(InstalledApp app) {
        if (isMicrosoftOrWindows(app.getPublisher(), app.getName())) {
            return true;
        }
        // Package identity is registry/display independent: Microsoft reserves
        // these prefixes, so any match is Microsoft-signed regardless of what
        // friendly Publisher/DisplayName strings the manifest carries.
        // (e.g. Publisher "Microsoft Windows" + Name "DesktopPackageMetadata"
        // for Client.CBS / FileExp / OOBE would otherwise slip through.)
        String pkg = app.getAppxPackageName();
        if (pkg != null) {
            String lp = pkg.toLowerCase().trim();
            if (lp.startsWith("microsoft.")
                    || lp.startsWith("microsoftwindows.")
                    || lp.equals("microsoftwindows")
                    || lp.startsWith("windows.")) {
                return true;
            }
        }
        return false;
    }
}
