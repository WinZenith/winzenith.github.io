package com.sbtools.shredder;

import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.duplicates.DuplicateSafety;
import com.sbtools.util.AppLogger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Strict safety gate for Secure Erase (strict-blocking mode).
 * <p>
 * Reuses {@link DuplicateSafety} + {@link CleanerUtils} protection so shredder
 * can never destroy OS/app locations. Unlike the legacy critical-file warning
 * (single/batch files only), this blocks:
 * <ul>
 *   <li>protected OS paths (Windows, Program Files, ProgramData, AppData,
 *       WindowsApps, System Volume Information, $Recycle.Bin root, Recovery,
 *       EFI, Boot)</li>
 *   <li>drive roots (C:\, D:\) and the user profile / WINDIR themselves</li>
 *   <li>symlink / junction / reparse-point targets (link itself must be deleted,
 *       never followed)</li>
 * </ul>
 * Returns {@code null} when the path is allowed, otherwise a human-readable
 * block reason for the UI alert.
 */
public final class ShredderSafety {

    private ShredderSafety() {}

    public static String validateFileForShred(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "Path is empty.";
        Path p;
        try {
            p = Paths.get(rawPath);
        } catch (Exception e) {
            return "Invalid path: " + e.getMessage();
        }
        return validatePath(p, false);
    }

    public static String validateFolderForShred(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "Path is empty.";
        Path p;
        try {
            p = Paths.get(rawPath);
        } catch (Exception e) {
            return "Invalid path: " + e.getMessage();
        }
        try {
            if (Files.exists(p) && !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                return "Not a folder: " + rawPath;
            }
        } catch (Exception ignored) {}
        return validatePath(p, true);
    }

    private static String validatePath(Path path, boolean isFolderOp) {
        try {
            Path abs;
            try {
                abs = path.toAbsolutePath().normalize();
            } catch (Exception e) {
                return "Invalid path: " + e.getMessage();
            }
            // 1. Reparse / symlink guard: never follow links in a destructive op.
            try {
                if (Files.isSymbolicLink(path) || Files.isSymbolicLink(abs)) {
                    return "Refusing to shred a symbolic link (would destroy the target):\n" + path;
                }
                Object reparse = Files.getAttribute(path, "dos:isReparsePoint",
                        LinkOption.NOFOLLOW_LINKS);
                if (reparse instanceof Boolean && (Boolean) reparse) {
                    return "Refusing to shred a junction / reparse point (would escape the target):\n" + path;
                }
            } catch (Exception ignored) {
                // Attribute unavailable (e.g. missing file) — continue to protected checks.
            }
            // 2. Protected OS locations (shared with Duplicate Finder + Cleaner).
            try {
                if (DuplicateSafety.isProtected(path) || DuplicateSafety.isProtected(abs)) {
                    return "System folders are protected and cannot be securely deleted:\n" + path
                            + "\n\nChoose a non-system file/folder (e.g. Documents, Downloads).";
                }
                if (CleanerUtils.isProtectedPath(path) || CleanerUtils.isProtectedPath(abs)) {
                    return "Protected OS path — shredding is blocked:\n" + path;
                }
            } catch (Exception e) {
                AppLogger.warning("ShredderSafety protected check failed for " + path + ": " + e.getMessage());
            }
            // 3. Drive roots, profile, WINDIR themselves.
            String s = abs.toString().toLowerCase(Locale.ROOT).replace('/', '\\');
            if (s.matches("^[a-z]:\\\\?$") || s.matches("^[a-z]:$")) {
                return "Refusing to shred an entire drive root:\n" + path;
            }
            String userProfile = System.getenv("USERPROFILE");
            if (userProfile != null && !userProfile.isBlank()) {
                String up = userProfile.toLowerCase(Locale.ROOT).replace('/', '\\');
                if (s.equals(up)) {
                    return "Refusing to shred the whole user profile:\n" + path;
                }
            }
            String windir = System.getenv("WINDIR");
            if (windir != null && !windir.isBlank()) {
                String w = windir.toLowerCase(Locale.ROOT).replace('/', '\\');
                if (s.equals(w) || s.startsWith(w + "\\")) {
                    return "Windows system path — shredding is blocked:\n" + path;
                }
            }
            // 4. Bare system-drive check for folder ops (C:\Windows already covered,
            // but guard against e.g. C:\ itself slipping through normalization).
            if (isFolderOp) {
                for (File root : File.listRoots()) {
                    try {
                        if (abs.equals(root.toPath().toAbsolutePath().normalize())) {
                            return "Refusing to shred a drive root:\n" + path;
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            return "Invalid path: " + e.getMessage();
        }
        return null;
    }

    public static boolean isBlocked(String rawPath, boolean isFolderOp) {
        return isFolderOp
                ? validateFolderForShred(rawPath) != null
                : validateFileForShred(rawPath) != null;
    }
}
