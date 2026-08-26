package com.sbtools.duplicates;

import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Safety gate for duplicate handling.
 * <p>
 * Guarantees that system-critical locations are never scanned or deleted.
 * User requested scope: any non-system folder on any drive is allowed.
 * Everything under {@code <drive>:\Windows}, {@code WindowsApps},
 * {@code System Volume Information}, {@code $Recycle.Bin}, {@code Recovery},
 * {@code EFI}, {@code Boot} is treated as protected.
 */
public final class DuplicateSafety {

    private static final String SYSTEM_DRIVE = initSystemDrive();

    private DuplicateSafety() {}

    private static String initSystemDrive() {
        String sd = System.getenv("SystemDrive");
        if (sd != null && !sd.isBlank()) {
            String s = sd.trim().toLowerCase(Locale.ROOT).replace('/', '\\');
            if (!s.endsWith("\\")) s = s + "\\";
            if (s.length() >= 2 && s.charAt(1) == ':') return s.substring(0, 2);
            return s;
        }
        String windir = System.getenv("WINDIR");
        if (windir != null && windir.length() >= 2 && windir.charAt(1) == ':') {
            return windir.substring(0, 2).toLowerCase(Locale.ROOT);
        }
        return "c:";
    }

    /**
     * Returns true if the path must never be scanned or deleted.
     */
    private static String stripLongPrefixStr(String s) {
        if (s == null) return null;
        if (s.startsWith("\\\\?\\UNC\\")) return "\\\\" + s.substring(8);
        if (s.startsWith("\\\\?\\")) return s.substring(4);
        return s;
    }

    private static Path stripLongPrefixPath(Path p) {
        try {
            String s = p.toString();
            String stripped = stripLongPrefixStr(s);
            if (!stripped.equals(s)) return java.nio.file.Paths.get(stripped);
        } catch (Exception ignored) {}
        return p;
    }

    public static boolean isProtected(Path path) {
        if (path == null) return false;
        try {
            Path strippedForCleaner = stripLongPrefixPath(path);
            if (CleanerUtils.isProtectedPath(strippedForCleaner)) return true;
            if (strippedForCleaner != path && CleanerUtils.isProtectedPath(path)) return true;
        } catch (Exception ignored) {}

        try {
            Path abs = stripLongPrefixPath(path.toAbsolutePath().normalize());
            String raw = abs.toString().toLowerCase(Locale.ROOT).replace('/', '\\');
            String s = stripLongPrefixStr(raw);
            // normalize drive prefix without trailing slash for comparison flexibility
            // e.g. c:\windows\system32 -> c:\windows
            for (File root : File.listRoots()) {
                String rootPath = root.getPath().toLowerCase(Locale.ROOT).replace('/', '\\');
                // rootPath is like "c:\"
                if (!rootPath.endsWith("\\")) rootPath = rootPath + "\\";
                String driveRootNoSlash = rootPath.substring(0, rootPath.length() - 1); // c:

                // <drive>:\Windows and any descendant
                String winRoot = rootPath + "windows";
                if (s.equals(winRoot) || s.startsWith(winRoot + "\\")) return true;
                // <drive>:\Windows.old and descendants (previous installation)
                String winOld = rootPath + "windows.old";
                if (s.equals(winOld) || s.startsWith(winOld + "\\")) return true;

                // <drive>:\Program Files\WindowsApps (UWP store) — any drive may host it
                String wApps = rootPath + "program files\\windowsapps";
                if (s.equals(wApps) || s.startsWith(wApps + "\\")) return true;
                String wAppsX86 = rootPath + "program files (x86)\\windowsapps";
                if (s.equals(wAppsX86) || s.startsWith(wAppsX86 + "\\")) return true;

                // System Volume Information
                String svi = rootPath + "system volume information";
                if (s.equals(svi) || s.startsWith(svi + "\\")) return true;

                // $Recycle.Bin
                String rb = rootPath + "$recycle.bin";
                if (s.equals(rb) || s.startsWith(rb + "\\")) return true;

                // Recovery
                String rec = rootPath + "recovery";
                if (s.equals(rec) || s.startsWith(rec + "\\")) return true;

                // EFI (on EFI system partition, but also may appear as folder)
                String efi = rootPath + "efi";
                if (s.equals(efi) || s.startsWith(efi + "\\")) return true;

                // Boot folder at root
                String boot = rootPath + "boot";
                if (s.equals(boot) || s.startsWith(boot + "\\")) return true;

                // $Windows.~BT / $Windows.~WS / $SysReset at root (CleanerUtils handles c: but we handle any drive)
                // name-only check for drive-root children with 1 component
                if (abs.getParent() != null && abs.getParent().equals(root.toPath())) {
                    String name = abs.getFileName() != null ? abs.getFileName().toString().toLowerCase(Locale.ROOT) : "";
                    if (name.equals("$windows.~bt") || name.equals("$windows.~ws") || name.equals("$sysreset")
                            || name.equals("recovery") || name.equals("system volume information")
                            || name.equals("$recycle.bin") || name.equals("efi") || name.equals("boot")
                            || name.equals("windows.old")) {
                        return true;
                    }
                }
            }

            // WINDIR outside File.listRoots enumeration edge (e.g., ramdisk)
            String windir = System.getenv("WINDIR");
            if (windir != null && !windir.isBlank()) {
                String w = stripLongPrefixStr(windir.toLowerCase(Locale.ROOT).replace('/', '\\'));
                if (s.equals(w) || s.startsWith(w + "\\")) return true;
            }

            // Also protect bare drive root itself from being considered safe deletable target
            // isProtected true for drive root already via CleanerUtils, but double-check
            // Also protect if path contains "\windowsapps\" anywhere (can be relocated)
            if (s.contains("\\windowsapps\\") || s.endsWith("\\windowsapps")) return true;
            if (s.contains("\\system volume information\\")) return true;

        } catch (Exception e) {
            AppLogger.warning("DuplicateSafety.isProtected check failed for " + path + ": " + e.getMessage());
        }
        return false;
    }

    /**
     * Whether a scan root is allowed to be added.
     * Returns null if allowed, otherwise a human-readable reason.
     */
    public static String validateScanRoot(Path path) {
        if (path == null) return "Path is null.";
        try {
            if (!Files.exists(path)) return "Folder does not exist.";
            if (!Files.isDirectory(path)) return "Path is not a directory.";
            if (isProtected(path)) {
                return "System folders are protected and cannot be scanned:\n" + path
                        + "\n\nChoose a non-system folder (e.g., Documents, Downloads, Photos) on any drive.";
            }
            // Block system drive root (e.g., C:\) even though isProtected(C:\) is false (otherwise every file on C: would be protected).
            // Allowing C:\ would still skip Windows subtree but would scan entire drive — too broad and risky.
            // Non-system drive roots (D:\, E:\) are allowed if they do not contain a Windows folder; if they do contain Windows, treat as protected root.
            Path abs = stripLongPrefixPath(path.toAbsolutePath().normalize());
            String rawS = abs.toString().toLowerCase(Locale.ROOT).replace('/', '\\');
            String s = stripLongPrefixStr(rawS);
            boolean isDriveRoot = s.matches("^[a-z]:\\\\$") || s.matches("^[a-z]:$");
            if (isDriveRoot) {
                String drive = s.substring(0, 2); // c:
                if (drive.equals(SYSTEM_DRIVE)) {
                    return "Scanning the entire system drive is not allowed for safety.\n"
                            + "Please add a specific folder (e.g., " + drive + "\\Users\\YourName\\Documents).";
                }
                // For non-system drives, check if it contains a Windows folder — if so, it's likely a system/Windows drive clone
                Path winSub = abs.resolve("Windows");
                try {
                    if (Files.exists(winSub) && Files.isDirectory(winSub)) {
                        return "This drive contains a Windows system folder and cannot be scanned as a whole:\n" + path
                                + "\n\nPlease add a specific non-system subfolder.";
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            return "Invalid path: " + e.getMessage();
        }
        return null;
    }

    public static boolean canAddScanRoot(Path path) {
        return validateScanRoot(path) == null;
    }

    /**
     * Keeper rank: higher = more desirable to keep.
     * Protected = 0 (should never be keeper if alternative exists)
     * System drive non-system location = 10
     * Non-system drive = 20
     */
    public static int keeperRank(Path p) {
        if (p == null) return 0;
        if (isProtected(p)) return 0;
        try {
            Path abs = stripLongPrefixPath(p.toAbsolutePath().normalize());
            String raw = abs.toString().toLowerCase(Locale.ROOT).replace('/', '\\');
            String s = stripLongPrefixStr(raw);
            // drive letter
            if (s.length() >= 2 && s.charAt(1) == ':') {
                String drive = s.substring(0, 2); // c:
                if (drive.equals(SYSTEM_DRIVE)) return 10;
                return 20;
            }
        } catch (Exception ignored) {}
        return 10;
    }

    public static boolean isSafeToDelete(Path p) {
        return !isProtected(p);
    }

    /**
     * Check dos:system attribute — best effort, returns false on error.
     */
    public static boolean isSystemFile(Path p) {
        try {
            return (Boolean) Files.getAttribute(stripLongPrefixPath(p), "dos:isSystem", java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (Exception ignored) {
            try { return (Boolean) Files.getAttribute(p, "dos:isSystem"); } catch (Exception ignored2) { return false; }
        }
    }

    public static boolean isHiddenFile(Path p) {
        try {
            return Files.isHidden(stripLongPrefixPath(p));
        } catch (Exception ignored) {
            try { return Files.isHidden(p); } catch (Exception ignored2) { return false; }
        }
    }
}
