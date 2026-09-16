package com.sbtools.shredder;

import com.sbtools.cleaner.CleanerUtils;
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
 * Reuses {@link CleanerUtils} protection plus built-in OS-location checks so shredder
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
            // 1b. Ancestor link guard: C:\Safe\link\System32\... where link -> C:\Windows
            // passes leaf + string checks but resolves into the OS. Walk parents NOFOLLOW.
            try {
                Path cur = abs;
                while (cur != null) {
                    try {
                        if (Files.isSymbolicLink(cur)) {
                            return "Refusing to shred through a symbolic link in the path:\n" + path
                                    + "\n\nLink component: " + cur;
                        }
                        Object rp = Files.getAttribute(cur, "dos:isReparsePoint",
                                LinkOption.NOFOLLOW_LINKS);
                        if (rp instanceof Boolean && (Boolean) rp) {
                            // The leaf itself was cleared above; any ancestor reparse escapes.
                            if (!cur.equals(abs)) {
                                return "Refusing to shred through a junction / reparse point in the path:\n" + path
                                        + "\n\nLink component: " + cur;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    cur = cur.getParent();
                }
            } catch (Exception ignored) {
            }
            // 1c. Real-path re-check: resolve links and re-apply OS protection to the target.
            try {
                Path real = null;
                try {
                    real = abs.toRealPath();
                } catch (Exception ignored) {
                }
                if (real != null && !real.equals(abs)) {
                    try {
                        if (isProtectedSystemPath(real) || CleanerUtils.isProtectedPath(real)) {
                            return "Resolved target is a protected OS path — shredding is blocked:\n" + path
                                    + "\n\nResolves to: " + real;
                        }
                    } catch (Exception e) {
                        AppLogger.warning("ShredderSafety real-path check failed for " + path + ": " + e.getMessage());
                    }
                }
            } catch (Exception ignored) {
            }
            // 2. Protected OS locations (shared with Cleaner).
            try {
                if (isProtectedSystemPath(path) || isProtectedSystemPath(abs)) {
                    return "System folders are protected and cannot be securely deleted:\n" + path
                            + "\n\nChoose a non-system file/folder (e.g. Documents, Downloads).";
                }
                if (CleanerUtils.isProtectedPath(path) || CleanerUtils.isProtectedPath(abs)) {
                    return "Protected OS path — shredding is blocked:\n" + path;
                }
            } catch (Exception e) {
                AppLogger.warning("ShredderSafety protected check failed for " + path + ": " + e.getMessage());
            }
            // 2b. File-mode must never accept directories (drag-and-drop can mix
            // folders into the batch file flow, bypassing folder count/admin gates).
            if (!isFolderOp) {
                try {
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.isDirectory(abs, LinkOption.NOFOLLOW_LINKS)) {
                        return "Selected path is a folder. Use \"Secure Delete Folder\" (Browse Folder) for directories:\n" + path;
                    }
                } catch (Exception ignored) {
                }
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

    /**
     * Reboot-delete must not follow links or touch OS paths. Recycle Bin wipe
     * items already proven under {@code X:\$Recycle.Bin\} are allowed.
     */
    public static String validateForRebootDelete(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "Path is empty.";
        if (isTrustedRecycleBinPath(rawPath)) return null;
        String fileBlock = validateFileForShred(rawPath);
        if (fileBlock == null) return null;
        try {
            Path p = Paths.get(rawPath);
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
                    || Files.isDirectory(p.toAbsolutePath().normalize(), LinkOption.NOFOLLOW_LINKS)) {
                return validateFolderForShred(rawPath);
            }
        } catch (Exception ignored) {
        }
        return fileBlock;
    }

    /**
     * Recycle Bin wipe only: allow a file/folder that is already proven to live
     * under {@code X:\$Recycle.Bin\...}. Normal shred still blocks these paths.
     */
    public static String validateRecycleBinItemForWipe(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "Path is empty.";
        if (!isTrustedRecycleBinPath(rawPath)) {
            return "Not a trusted Recycle Bin path (must be under X:\\$Recycle.Bin\\):\n" + rawPath;
        }
        return null;
    }

    /**
     * Trust boundary for Recycle Bin wipe: path must be contained under
     * {@code X:\$Recycle.Bin\} (not the root itself) and must not be a link.
     * Original-location paths are rejected so wipe never shreds a live file.
     */
    public static boolean isTrustedRecycleBinPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return false;
        try {
            Path p = Paths.get(rawPath).toAbsolutePath().normalize();
            String s = p.toString().toLowerCase(Locale.ROOT).replace('/', '\\');
            s = stripLongPrefixStr(s);
            if (!s.matches("^[a-z]:\\\\\\$recycle\\.bin\\\\.+")) return false;
            try {
                if (Files.isSymbolicLink(p)) return false;
                Object rp = Files.getAttribute(p, "dos:isReparsePoint", LinkOption.NOFOLLOW_LINKS);
                if (rp instanceof Boolean && (Boolean) rp) return false;
                Path cur = p;
                while (cur != null) {
                    try {
                        if (Files.isSymbolicLink(cur)) return false;
                        Object a = Files.getAttribute(cur, "dos:isReparsePoint",
                                LinkOption.NOFOLLOW_LINKS);
                        if (a instanceof Boolean && (Boolean) a && !cur.equals(p)) return false;
                    } catch (Exception ignored) {
                    }
                    cur = cur.getParent();
                }
                try {
                    Path real = p.toRealPath();
                    String rs = real.toString().toLowerCase(Locale.ROOT).replace('/', '\\');
                    rs = stripLongPrefixStr(rs);
                    if (!rs.matches("^[a-z]:\\\\\\$recycle\\.bin\\\\.+")) return false;
                } catch (Exception ignored) {
                    // Missing file: prefix + ancestor checks above already passed.
                }
            } catch (Exception ignored) {
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

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
            if (!stripped.equals(s)) return Paths.get(stripped);
        } catch (Exception ignored) {}
        return p;
    }

    // System/app locations shredder must never touch.
    private static boolean isProtectedSystemPath(Path path) {
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
            for (File root : File.listRoots()) {
                String rootPath = root.getPath().toLowerCase(Locale.ROOT).replace('/', '\\');
                if (!rootPath.endsWith("\\")) rootPath = rootPath + "\\";
                String winRoot = rootPath + "windows";
                if (s.equals(winRoot) || s.startsWith(winRoot + "\\")) return true;
                String winOld = rootPath + "windows.old";
                if (s.equals(winOld) || s.startsWith(winOld + "\\")) return true;
                String wApps = rootPath + "program files\\windowsapps";
                if (s.equals(wApps) || s.startsWith(wApps + "\\")) return true;
                String wAppsX86 = rootPath + "program files (x86)\\windowsapps";
                if (s.equals(wAppsX86) || s.startsWith(wAppsX86 + "\\")) return true;
                String pf = rootPath + "program files";
                if (s.equals(pf) || s.startsWith(pf + "\\")) return true;
                String pf86 = rootPath + "program files (x86)";
                if (s.equals(pf86) || s.startsWith(pf86 + "\\")) return true;
                String pdata = rootPath + "programdata";
                if (s.equals(pdata) || s.startsWith(pdata + "\\")) return true;
                String svi = rootPath + "system volume information";
                if (s.equals(svi) || s.startsWith(svi + "\\")) return true;
                String rb = rootPath + "$recycle.bin";
                if (s.equals(rb) || s.startsWith(rb + "\\")) return true;
                String rec = rootPath + "recovery";
                if (s.equals(rec) || s.startsWith(rec + "\\")) return true;
                String efi = rootPath + "efi";
                if (s.equals(efi) || s.startsWith(efi + "\\")) return true;
                String boot = rootPath + "boot";
                if (s.equals(boot) || s.startsWith(boot + "\\")) return true;
            }
            String windir = System.getenv("WINDIR");
            if (windir != null && !windir.isBlank()) {
                String w = stripLongPrefixStr(windir.toLowerCase(Locale.ROOT).replace('/', '\\'));
                if (s.equals(w) || s.startsWith(w + "\\")) return true;
            }
            if (s.contains("\\windowsapps\\") || s.endsWith("\\windowsapps")) return true;
            if (s.contains("\\system volume information\\") || s.endsWith("\\system volume information")) return true;
            if (s.contains("\\appdata\\") || s.endsWith("\\appdata")) return true;
            boolean isDriveLetterPath = s.matches("^[a-z]:\\\\.*") || s.matches("^[a-z]:$");
            if (!isDriveLetterPath) {
                if (s.contains("\\program files (x86)\\") || s.endsWith("\\program files (x86)")) return true;
                if (s.contains("\\program files\\") || s.endsWith("\\program files")) return true;
                if (s.contains("\\programdata\\") || s.endsWith("\\programdata")) return true;
                if (s.contains("\\windows\\") || s.endsWith("\\windows")) return true;
                if (s.contains("\\winnt\\") || s.endsWith("\\winnt")) return true;
                if (s.contains("\\recovery\\") || s.endsWith("\\recovery")) return true;
                if (s.contains("\\efi\\") || s.endsWith("\\efi")) return true;
                if (s.contains("\\boot\\") || s.endsWith("\\boot")) return true;
            }
            if (s.contains("\\$recycle.bin\\") || s.endsWith("\\$recycle.bin")) return true;
        } catch (Exception e) {
            AppLogger.warning("ShredderSafety protected check failed for " + path + ": " + e.getMessage());
        }
        return false;
    }
}
