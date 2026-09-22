package com.sbtools.uninstaller;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/** Plain assertions for the uninstall safety guards. Run as a main, no test library. */
public final class UninstallerSafetyChecks {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("wz-uninst").toFile();
        try {
            File seven = new File(root, "7-Zip");
            new File(seven, "Lang").mkdirs();
            check(!UninstallerService.looksLikeSharedVendorDir(seven.getAbsolutePath(), "7-Zip"),
                    "7-Zip/Lang is the app directory");
            check(!UninstallerService.isSharedVendorInstallLocation(seven.getAbsolutePath(), "7-Zip", "Igor Pavlov"),
                    "exact 7-Zip folder stays deletable");

            File vlc = new File(root, "VLC");
            new File(vlc, "locale").mkdirs();
            check(!UninstallerService.isSharedVendorInstallLocation(vlc.getAbsolutePath(), "VLC media player", "VideoLAN"),
                    "VLC prefix folder is the app, not a vendor root");

            File adobe = new File(root, "Adobe");
            new File(adobe, "Acrobat").mkdirs();
            new File(adobe, "Photoshop").mkdirs();
            check(UninstallerService.isSharedVendorInstallLocation(adobe.getAbsolutePath(), "Adobe Acrobat", "Adobe"),
                    "Adobe with Acrobat inner is a vendor root");

            File opera = new File(root, "Opera");
            new File(opera, "Opera GX").mkdirs();
            check(UninstallerService.looksLikeSharedVendorDir(opera.getAbsolutePath(), "Opera"),
                    "Opera GX is a sibling SKU");
            check(!UninstallerService.isSiblingProductDir("lang", "7-zip"), "Lang is not a sibling product");

            check(!UninstallerService.isStartMenuLeafMatch("Player", "VLC media player"),
                    "last word must not match a shortcut");
            check(UninstallerService.isStartMenuLeafMatch("VLC media player", "VLC media player"),
                    "exact shortcut name");
            check(UninstallerService.isStartMenuLeafMatch("VLC media player 3.0", "VLC media player"),
                    "version suffix shortcut");

            List<String> bat = UninstallerService.wrapNonPeHost(
                    List.of("C:\\App\\uninstall.bat", "a&b", "hello world"));
            check(List.of("cmd.exe", "/d", "/c", "C:\\App\\uninstall.bat", "a^&b", "hello world").equals(bat),
                    "bat host: " + bat);
            List<String> msi = UninstallerService.wrapNonPeHost(List.of("C:\\App\\product.msi", "/quiet"));
            check(List.of("msiexec.exe", "/x", "C:\\App\\product.msi", "/quiet").equals(msi), "msi host: " + msi);
            List<String> ps1 = UninstallerService.wrapNonPeHost(List.of("C:\\App\\remove.ps1"));
            check(ps1.get(0).equals("powershell.exe") && ps1.contains("-File")
                    && ps1.get(ps1.size() - 1).equals("C:\\App\\remove.ps1"), "ps1 host: " + ps1);
            List<String> exe = UninstallerService.wrapNonPeHost(List.of("C:\\App\\unins000.exe", "/S"));
            check(List.of("C:\\App\\unins000.exe", "/S").equals(exe), "exe unchanged");

            InstalledApp app = new InstalledApp("X", "", "", "%SystemRoot%\\Temp",
                    "", "", "", true, "", "", "", "", 0, "");
            String windir = System.getenv("SystemRoot");
            if (windir == null) windir = System.getenv("WINDIR");
            check(app.getInstallLocation().equalsIgnoreCase(windir + "\\Temp"),
                    "expanded InstallLocation: " + app.getInstallLocation());
            System.out.println("ok");
        } finally {
            delete(root);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void delete(File file) {
        File[] kids = file.listFiles();
        if (kids != null) {
            for (File kid : kids) delete(kid);
        }
        file.delete();
    }
}
