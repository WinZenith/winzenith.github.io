package com.sbtools.cleaner;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.CancellationToken;
import com.sbtools.util.FormatUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CleanerUtils {

    private static final Set<String> PROTECTED_ABSOLUTE_PREFIXES = new HashSet<>();

    /** Default depth cap for directory scans to avoid runaway walks. Additive optimization. */
    public static final int DEFAULT_SCAN_MAX_DEPTH = 10;

    /** Stats for directory deletes: bytes freed + locked/skipped file count. */
    public record DeleteStats(long bytesFreed, int filesDeleted, int skippedLocked) {}

    private static final Set<String> PROTECTED_ROOT_FILE_NAMES = new HashSet<>(Set.of(
            "$windows.~bt", "$windows.~ws", "$sysreset",
            "pagefile.sys", "hiberfil.sys", "swapfile.sys",
            "bootmgr", "bootmgr.efi", "ntldr", "ntdetect.com",
            "bootnxt", "recovery"
    ));

    static {
        String windir = System.getenv("WINDIR");
        if (windir != null) {
            String w = windir.toLowerCase().replace('/', '\\');
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\system32");
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\syswow64");
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\winsxs");
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\boot");
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\fonts");
            // Safety hardening: never traverse into servicing / core OS component stores.
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\servicing");
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\systemapps");
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\systemresources");
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\security");
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\policydefinitions");
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\schemas");
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\wbem");
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\logs\\cbs");
            PROTECTED_ABSOLUTE_PREFIXES.add(w + "\\logs\\dism");
        }
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\system32");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\syswow64");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\winsxs");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\boot");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\fonts");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\servicing");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\systemapps");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\systemresources");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\security");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\policydefinitions");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\schemas");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\wbem");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\logs\\cbs");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\logs\\dism");
        addUserDataFolderPrefixes();
        addJavaHomePrefix();
    }

    private static volatile String cachedPortablePrefix;

    private static void addUserDataFolderPrefixes() {
        addKnownFolderPrefixes(safeEnv("USERPROFILE"));
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) addKnownFolderPrefixes(home);
        addShellKnownFolderPrefixes();
    }

    private static void addShellKnownFolderPrefixes() {
        try {
            addKnownFolderPath(com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(
                    com.sun.jna.platform.win32.KnownFolders.FOLDERID_Documents));
            addKnownFolderPath(com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(
                    com.sun.jna.platform.win32.KnownFolders.FOLDERID_Desktop));
            addKnownFolderPath(com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(
                    com.sun.jna.platform.win32.KnownFolders.FOLDERID_Downloads));
            addKnownFolderPath(com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(
                    com.sun.jna.platform.win32.KnownFolders.FOLDERID_Pictures));
            addKnownFolderPath(com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(
                    com.sun.jna.platform.win32.KnownFolders.FOLDERID_Videos));
            addKnownFolderPath(com.sun.jna.platform.win32.Shell32Util.getKnownFolderPath(
                    com.sun.jna.platform.win32.KnownFolders.FOLDERID_Music));
        } catch (Throwable ignored) {}
    }

    private static void addKnownFolderPath(String path) {
        if (path == null || path.isBlank()) return;
        String n = path.toLowerCase().replace('/', '\\');
        if (n.endsWith("\\")) n = n.substring(0, n.length() - 1);
        PROTECTED_ABSOLUTE_PREFIXES.add(n);
    }

    private static void addKnownFolderPrefixes(String root) {
        if (root == null || root.isBlank()) return;
        String base = root.toLowerCase().replace('/', '\\');
        if (base.endsWith("\\")) base = base.substring(0, base.length() - 1);
        for (String name : new String[]{"documents", "desktop", "downloads", "pictures", "videos"}) {
            PROTECTED_ABSOLUTE_PREFIXES.add(base + "\\" + name);
        }
    }

    private static void addJavaHomePrefix() {
        String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) return;
        PROTECTED_ABSOLUTE_PREFIXES.add(javaHome.toLowerCase().replace('/', '\\'));
    }

    private CleanerUtils() {
    }

    public static String safeEnv(String name) {
        String val = System.getenv(name);
        return (val != null && !val.isBlank()) ? val : null;
    }

    public static Path safeEnvPath(String envName, String... subPath) {
        String base = safeEnv(envName);
        if (base == null) return null;
        if (subPath.length == 0) return Paths.get(base);
        return Paths.get(base, subPath);
    }

    public static void addEnvPath(List<Path> list, String envName, String... subPath) {
        Path p = safeEnvPath(envName, subPath);
        if (p != null && isRealDirectory(p)) {
            list.add(p);
        }
    }

    public static void addPath(List<Path> list, String pathStr) {
        if (pathStr != null && !pathStr.isBlank()) {
            Path p = Paths.get(pathStr);
            if (isRealDirectory(p)) {
                list.add(p);
            }
        }
    }

    public static List<Path> deduplicatePaths(List<Path> paths) {
        Set<String> seen = new java.util.LinkedHashSet<>();
        List<Path> result = new ArrayList<>();
        for (Path p : paths) {
            try {
                String canonical = p.toRealPath().toString().toLowerCase();
                if (seen.add(canonical)) {
                    result.add(p);
                }
            } catch (Exception e) {
                if (seen.add(p.toString().toLowerCase())) {
                    result.add(p);
                }
            }
        }
        return result;
    }

    public static String expandEnvironmentVariables(String path) {
        if (path == null) return null;
        Matcher m = Pattern.compile("%([^%]+)%").matcher(path);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String envName = m.group(1);
            String envVal = System.getenv(envName);
            m.appendReplacement(sb, envVal != null ? Matcher.quoteReplacement(envVal) : m.group(0));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String extractPathFromRegistryValue(String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) return null;
        String path = rawValue.trim();
        if (path.startsWith("\"")) {
            int closeQuote = path.indexOf('"', 1);
            if (closeQuote > 0) {
                path = path.substring(1, closeQuote).trim();
            } else {
                path = path.substring(1).trim();
            }
        } else {
            if (path.startsWith("\\\\")) {
                return null;
            }
            String lower = path.toLowerCase();
            int exeIdx = indexOfExeSuffix(lower);
            if (exeIdx > 0) {
                // Executable only — rundll32/wscript/cmd leftover args are not the path.
                path = path.substring(0, exeIdx + 4).trim();
            } else {
                int dllIdx = lower.lastIndexOf(".dll");
                if (dllIdx > 0) {
                    String afterDll = path.substring(dllIdx + 4);
                    int spaceIdx = afterDll.indexOf(" ");
                    if (spaceIdx >= 0) {
                        path = path.substring(0, dllIdx + 4 + spaceIdx);
                    }
                }
                int cplIdx = path.toLowerCase().lastIndexOf(".cpl");
                if (cplIdx > 0) {
                    String afterCpl = path.substring(cplIdx + 4);
                    int spaceIdx = afterCpl.indexOf(",");
                    if (spaceIdx >= 0) {
                        path = path.substring(0, cplIdx + 4);
                    }
                }
            }
        }
        if (!path.contains("\\") && !path.contains("/")) {
            // Bare filenames (no directory) are LoadLibrary/search-path references,
            // not concrete file paths. Treating "foo.dll" as missing (CWD lookup)
            // would create false "invalid entry" positives — never report them.
            return null;
        }
        path = expandEnvironmentVariables(path);
        if (path != null && path.contains("%")) {
            // Unexpanded variable (unknown/missing env): the target cannot be
            // evaluated confidently — skip rather than flag as invalid.
            return null;
        }
        return path;
    }

    /** First `.exe` that is a suffix (end of string or followed by space/quote/slash). */
    public static int indexOfExeSuffix(String lower) {
        if (lower == null) return -1;
        int idx = 0;
        while ((idx = lower.indexOf(".exe", idx)) >= 0) {
            int end = idx + 4;
            if (end == lower.length()) return idx;
            char c = lower.charAt(end);
            if (c == ' ' || c == '"' || c == '/' || c == '\t') return idx;
            idx = end;
        }
        return -1;
    }

    public static int countDocumentsInRecentDocsBinary(byte[] data) {
        if (data == null || data.length < 2) return 0;
        int count = 0;
        for (int i = 0; i < data.length - 1; i++) {
            if (data[i] == 0x00 && data[i + 1] == 0x00) break;
            if (data[i] == 0x20 && (i == 0 || data[i - 1] == 0x00)) count++;
        }
        return Math.max(count, 0);
    }

    public static void scanDirectorySizes(CleanupRow row, List<Path> dirs) {
        // Capped by default to avoid runaway walks on huge trees (e.g. user profile).
        // Explicit-depth overload remains for callers needing deeper scans.
        scanDirectorySizes(row, dirs, DEFAULT_SCAN_MAX_DEPTH);
    }

    public static void scanDirectorySizes(CleanupRow row, List<Path> dirs, int maxDepth) {
        scanDirectorySizes(row, dirs, maxDepth, null);
    }

    public static void scanDirectorySizes(CleanupRow row, List<Path> dirs, int maxDepth, CancellationToken token) {
        java.util.concurrent.atomic.AtomicLong totalSize = new java.util.concurrent.atomic.AtomicLong(0);
        java.util.concurrent.atomic.AtomicLong itemCount = new java.util.concurrent.atomic.AtomicLong(0);
        int depth = maxDepth > 0 ? maxDepth : DEFAULT_SCAN_MAX_DEPTH;
        for (Path dir : dirs) {
            if (token != null && token.isCancelled()) break;
            if (dir != null && Files.isDirectory(dir)) {
                if (!isSafeToCleanDirectory(dir)) {
                    AppLogger.warning("Skipping unsafe scan directory: " + dir);
                    continue;
                }
                try {
                    Files.walkFileTree(dir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                            depth, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                            if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                            if (shouldSkipWalkDir(d, dir, attrs)) return FileVisitResult.SKIP_SUBTREE;
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                            if (attrs.isRegularFile()) {
                                totalSize.addAndGet(attrs.size());
                                itemCount.incrementAndGet();
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize.get());
        row.setItemCount((int) itemCount.get());
        row.setSizeOrCountText(formatBytes(totalSize.get())
                + (itemCount.get() > 0 ? " (" + itemCount.get() + " files)" : ""));
    }

    public static void scanDirectorySizesOlderThan(CleanupRow row, List<Path> dirs, java.time.Duration maxAge) {
        scanDirectorySizesOlderThan(row, dirs, maxAge, null);
    }

    public static void scanDirectorySizesOlderThan(CleanupRow row, List<Path> dirs,
            java.time.Duration maxAge, CancellationToken token) {
        java.util.concurrent.atomic.AtomicLong totalSize = new java.util.concurrent.atomic.AtomicLong(0);
        java.util.concurrent.atomic.AtomicLong itemCount = new java.util.concurrent.atomic.AtomicLong(0);
        long cutoff = System.currentTimeMillis() - maxAge.toMillis();
        for (Path dir : dirs) {
            if (token != null && token.isCancelled()) break;
            if (dir != null && Files.isDirectory(dir)) {
                if (!isSafeToCleanDirectory(dir)) {
                    AppLogger.warning("Skipping unsafe scan directory: " + dir);
                    continue;
                }
                try {
                    Files.walkFileTree(dir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                            DEFAULT_SCAN_MAX_DEPTH, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                            if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                            if (shouldSkipWalkDir(d, dir, attrs)) return FileVisitResult.SKIP_SUBTREE;
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                            try {
                                if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                                if (Files.isHidden(file)) return FileVisitResult.CONTINUE;
                                long lastModified = attrs.lastModifiedTime() != null
                                        ? attrs.lastModifiedTime().toMillis() : 0L;
                                if (lastModified > 0 && lastModified < cutoff) {
                                    totalSize.addAndGet(attrs.size());
                                    itemCount.incrementAndGet();
                                }
                            } catch (Exception ignored) {
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize.get());
        row.setItemCount((int) itemCount.get());
        row.setSizeOrCountText(formatBytes(totalSize.get())
                + (itemCount.get() > 0 ? " (" + itemCount.get() + " files)" : ""));
    }

    public static long cleanDirectoryPattern(List<Path> dirs) {
        return cleanDirectoryPattern(dirs, null);
    }

    public static long cleanDirectoryPattern(List<Path> dirs, CancellationToken token) {
        return cleanDirectoryPattern(dirs, DEFAULT_SCAN_MAX_DEPTH, token);
    }

    public static long cleanDirectoryPattern(List<Path> dirs, int maxDepth, CancellationToken token) {
        int depth = maxDepth > 0 ? maxDepth : DEFAULT_SCAN_MAX_DEPTH;
        long cleaned = 0;
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                if (!isSafeToCleanDirectory(dir)) {
                    AppLogger.warning("Skipping unsafe clean directory: " + dir);
                    continue;
                }
                cleaned += deleteDirectoryContents(dir, depth, token);
            }
            if (token != null && token.isCancelled()) break;
        }
        return cleaned;
    }

    public static long cleanDirectoryPatternOlderThan(List<Path> dirs, java.time.Duration maxAge) {
        return cleanDirectoryPatternOlderThan(dirs, maxAge, null);
    }

    public static long cleanDirectoryPatternOlderThan(List<Path> dirs, java.time.Duration maxAge, CancellationToken token) {
        long cleaned = 0;
        long cutoff = System.currentTimeMillis() - maxAge.toMillis();
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                if (!isSafeToCleanDirectory(dir)) {
                    AppLogger.warning("Skipping unsafe clean directory: " + dir);
                    continue;
                }
                cleaned += deleteDirectoryContentsOlderThan(dir, cutoff, token);
            }
            if (token != null && token.isCancelled()) break;
        }
        return cleaned;
    }

    /**
     * Conservative safety check: target must exist, must not be a protected OS path,
     * must not be a filesystem root, and must not be an ancestor of (or equal to)
     * sensitive roots (user profile, Windows dir, AppData locations). Guards against
     * hijacked/misconfigured env vars (e.g. TEMP=C:\) redirecting a clean at the
     * whole drive or profile.
     */
    public static boolean isSafeToCleanDirectory(Path dir) {
        if (dir == null) return false;
        try {
            if (!isRealDirectory(dir)) return false;
            if (isProtectedPath(dir)) return false;
            Path abs = dir.toAbsolutePath().normalize();
            if (abs.getParent() == null) return false;
            Path root = abs.getRoot();
            if (root != null && abs.equals(root)) return false;
            if (abs.getNameCount() <= 1) return false;
            // Never allow cleaning the whole user profile, Windows dir, or drive root content.
            String absStr = abs.toString().toLowerCase().replace('/', '\\');
            String userProfile = safeEnv("USERPROFILE");
            if (userProfile != null && isSameOrAncestor(absStr, userProfile.toLowerCase().replace('/', '\\'))) return false;
            String windir = safeEnv("WINDIR");
            if (windir != null && isSameOrAncestor(absStr, windir.toLowerCase().replace('/', '\\'))) return false;
            String localAppData = safeEnv("LOCALAPPDATA");
            if (localAppData != null && isSameOrAncestor(absStr, localAppData.toLowerCase().replace('/', '\\'))) return false;
            String appData = safeEnv("APPDATA");
            if (appData != null && isSameOrAncestor(absStr, appData.toLowerCase().replace('/', '\\'))) return false;
            String systemDrive = safeEnv("SYSTEMDRIVE");
            if (systemDrive != null) {
                String sd = systemDrive.toLowerCase().replace('/', '\\');
                if (!sd.endsWith("\\")) sd = sd + "\\";
                // Bare drive root itself (e.g. "c:\") is never a safe target.
                if (absStr.equals(sd.substring(0, sd.length() - 1)) || absStr.equals(sd)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isSameOrAncestor(String candidateDir, String sensitivePath) {
        String c = candidateDir.endsWith("\\") ? candidateDir.substring(0, candidateDir.length() - 1) : candidateDir;
        String s = sensitivePath.endsWith("\\") ? sensitivePath.substring(0, sensitivePath.length() - 1) : sensitivePath;
        if (c.equals(s)) return true;
        // Candidate is an ancestor of the sensitive path (e.g. C:\Users vs C:\Users\name).
        return s.startsWith(c + "\\");
    }

    /**
     * Delete with locked-file accounting. Existing {@link #deleteDirectoryContents(Path, CancellationToken)}
     * delegates here for backward compatibility.
     */
    public static DeleteStats deleteDirectoryContentsWithStats(Path dir, CancellationToken token) {
        return deleteDirectoryContentsWithStats(dir, DEFAULT_SCAN_MAX_DEPTH, token);
    }

    public static DeleteStats deleteDirectoryContentsWithStats(Path dir, int maxDepth, CancellationToken token) {
        java.util.concurrent.atomic.AtomicLong cleaned = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicInteger deleted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger skipped = new java.util.concurrent.atomic.AtomicInteger();
        if (dir == null || !isRealDirectory(dir) || isProtectedPath(dir)) {
            if (dir != null && isProtectedPath(dir)) {
                AppLogger.warning("Skipping protected directory: " + dir);
            }
            return new DeleteStats(0, 0, 0);
        }
        if (!isSafeToCleanDirectory(dir)) {
            AppLogger.warning("Skipping unsafe delete directory: " + dir);
            return new DeleteStats(0, 0, 0);
        }
        int depth = maxDepth > 0 ? maxDepth : DEFAULT_SCAN_MAX_DEPTH;
        try {
            Files.walkFileTree(dir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), depth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (shouldSkipWalkDir(d, dir, attrs)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (isProtectedPath(file)) {
                        skipped.incrementAndGet();
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        if (isReparseLike(file, attrs)) {
                            Files.deleteIfExists(file);
                            return FileVisitResult.CONTINUE;
                        }
                        boolean existed = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
                        Files.deleteIfExists(file);
                        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS) && existed) {
                            cleaned.addAndGet(attrs.size());
                            deleted.incrementAndGet();
                        } else if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                            skipped.incrementAndGet();
                        }
                    } catch (Exception e) {
                        skipped.incrementAndGet();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (!d.equals(dir) && !isProtectedPath(d)) {
                        try {
                            Files.deleteIfExists(d);
                        } catch (Exception e) {
                            skipped.incrementAndGet();
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    skipped.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
        }
        return new DeleteStats(cleaned.get(), deleted.get(), skipped.get());
    }

    public static long deleteDirectoryContents(Path dir) {
        return deleteDirectoryContents(dir, null);
    }

    public static long deleteDirectoryContents(Path dir, CancellationToken token) {
        return deleteDirectoryContents(dir, DEFAULT_SCAN_MAX_DEPTH, token);
    }

    public static long deleteDirectoryContents(Path dir, int maxDepth, CancellationToken token) {
        return deleteDirectoryContentsWithStats(dir, maxDepth, token).bytesFreed();
    }

    /**
     * Pattern clean with locked-file accounting (additive; existing callers unaffected).
     */
    public static DeleteStats cleanDirectoryPatternWithStats(List<Path> dirs, CancellationToken token) {
        long bytes = 0;
        int files = 0;
        int skipped = 0;
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir) && isSafeToCleanDirectory(dir)) {
                DeleteStats s = deleteDirectoryContentsWithStats(dir, DEFAULT_SCAN_MAX_DEPTH, token);
                bytes += s.bytesFreed();
                files += s.filesDeleted();
                skipped += s.skippedLocked();
            }
            if (token != null && token.isCancelled()) break;
        }
        return new DeleteStats(bytes, files, skipped);
    }

    public static long deleteDirectoryContentsOlderThan(Path dir, long cutoffMillis) {
        return deleteDirectoryContentsOlderThan(dir, cutoffMillis, null);
    }

    public static long deleteDirectoryContentsOlderThan(Path dir, long cutoffMillis, CancellationToken token) {
        // Defense in depth: never run an age-based recursive delete against an
        // unsafe root (hijacked env var, drive root, profile ancestor, link).
        if (!isSafeToCleanDirectory(dir)) {
            AppLogger.warning("Skipping unsafe age-based clean directory: " + dir);
            return 0;
        }
        java.util.concurrent.atomic.AtomicLong cleaned = new java.util.concurrent.atomic.AtomicLong(0);
        try {
            // Bounded depth (matches scan cap) + streaming visitor: no full-path
            // materialization, so huge trees cannot OOM. Directories are removed
            // deepest-first via postVisitDirectory, only when empty.
            Files.walkFileTree(dir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                    DEFAULT_SCAN_MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (shouldSkipWalkDir(d, dir, attrs)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    try {
                        if (isProtectedPath(file)) return FileVisitResult.CONTINUE;
                        try {
                            if (Files.isHidden(file)) return FileVisitResult.CONTINUE;
                        } catch (Exception ignored) {}
                        long lastModified = attrs.lastModifiedTime() != null
                                ? attrs.lastModifiedTime().toMillis() : 0L;
                        if (lastModified > 0 && lastModified >= cutoffMillis) return FileVisitResult.CONTINUE;
                        // Zero/unknown mtime: keep (fail-safe, avoids deleting
                        // files the age check cannot evaluate).
                        if (lastModified <= 0) return FileVisitResult.CONTINUE;
                        long size = attrs.size();
                        deletePermanently(file, token);
                        if (!Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) cleaned.addAndGet(size);
                    } catch (Exception ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (d.equals(dir) || isProtectedPath(d)) return FileVisitResult.CONTINUE;
                    try {
                        // Only removes the dir when it became empty; silently keeps
                        // non-empty (recent files, hidden files) parents.
                        deleteDirectoryIfEmptySafe(d, token);
                    } catch (Exception ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {
        }
        return cleaned.get();
    }

    public static void deletePermanently(Path source) {
        deletePermanently(source, null);
    }

    public static void deletePermanently(Path source, CancellationToken token) {
        if (token != null && token.isCancelled()) return;
        if (source == null) return;
        if (isProtectedPath(source)) {
            AppLogger.warning("Skipping protected path: " + source);
            return;
        }
        try {
            Path parent = source.toAbsolutePath().normalize().getParent();
            // Intermediate junctions are followed for the leaf; refuse unless
            // every parent component is a real directory.
            if (parent != null && !isRealDirectory(parent)) {
                AppLogger.warning("Skipping delete through junction/symlink parent: " + source);
                return;
            }
        } catch (Exception e) {
            return;
        }
        // Delete the directory entry itself (junction/symlink), never the target.
        try {
            if (isReparseLike(source, null)) {
                Files.deleteIfExists(source);
                return;
            }
        } catch (Exception ignored) {}
        try {
            Files.deleteIfExists(source);
        } catch (IOException e) {
            AppLogger.warning("Could not delete " + source + ": " + e.getMessage());
        }
    }

    /**
     * Safely delete a directory if empty and not protected. Uses deletePermanently check.
     */
    public static boolean deleteDirectoryIfEmptySafe(Path dir, CancellationToken token) {
        if (token != null && token.isCancelled()) return false;
        if (isProtectedPath(dir)) return false;
        try {
            if (!isEmptyDirectory(dir)) return false;
            Files.deleteIfExists(dir);
            return !Files.exists(dir);
        } catch (Exception ignored) { return false; }
    }

    public static boolean isEmptyDirectory(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            return !ds.iterator().hasNext();
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isProtectedPath(Path path) {
        if (path == null) return false;
        try {
            Path abs = path.toAbsolutePath().normalize();
            String absStr = abs.toString().toLowerCase().replace('/', '\\');

            for (String prefix : PROTECTED_ABSOLUTE_PREFIXES) {
                if (absStr.equals(prefix) || absStr.startsWith(prefix + "\\")) {
                    return true;
                }
            }
            String portable = portablePrefix();
            if (portable != null && (absStr.equals(portable) || absStr.startsWith(portable + "\\"))) {
                return true;
            }

            Path root = abs.getRoot();
            if (root != null && abs.getParent() != null && abs.getParent().equals(root)) {
                String fileName = abs.getFileName().toString();
                if (PROTECTED_ROOT_FILE_NAMES.contains(fileName.toLowerCase())) return true;
            }

            for (File driveRoot : File.listRoots()) {
                if (abs.startsWith(driveRoot.toPath())) {
                    int namesCount = abs.getNameCount();
                    if (namesCount <= 1) {
                        String fileName = abs.getFileName() != null ? abs.getFileName().toString() : "";
                        if (fileName.equalsIgnoreCase("$Windows.~BT")
                                || fileName.equalsIgnoreCase("$Windows.~WS")
                                || fileName.equalsIgnoreCase("$SysReset")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String portablePrefix() {
        String cached = cachedPortablePrefix;
        if (cached != null) return cached.isEmpty() ? null : cached;
        synchronized (CleanerUtils.class) {
            cached = cachedPortablePrefix;
            if (cached != null) return cached.isEmpty() ? null : cached;
            try {
                Path portable = AppPaths.portableBaseDir();
                if (portable != null) {
                    cached = portable.toAbsolutePath().normalize().toString().toLowerCase().replace('/', '\\');
                    cachedPortablePrefix = cached;
                    return cached;
                }
            } catch (Exception ignored) {}
            cachedPortablePrefix = "";
            return null;
        }
    }

    /**
     * Fail-closed process check: if process enumeration is unavailable, returns true
     * so callers skip destructive work rather than deleting files in use.
     */
    public static boolean isAnyProcessRunning(String... imageNames) {
        if (imageNames == null || imageNames.length == 0) return false;
        java.util.Set<String> want = new java.util.HashSet<>();
        for (String n : imageNames) {
            if (n != null && !n.isBlank()) want.add(n.toLowerCase(java.util.Locale.ROOT));
        }
        if (want.isEmpty()) return false;
        try {
            return ProcessHandle.allProcesses().anyMatch(ph -> {
                String cmd = ph.info().command().orElse("");
                if (cmd.isBlank()) return false;
                try {
                    Path fileName = Path.of(cmd).getFileName();
                    if (fileName == null) return false;
                    return want.contains(fileName.toString().toLowerCase(java.util.Locale.ROOT));
                } catch (Exception e) {
                    return false;
                }
            });
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean exportRegistryKey(String fullKey, Path dest) {
        if (fullKey == null || fullKey.isBlank() || dest == null) return false;
        try {
            Files.createDirectories(dest.getParent());
            ProcessBuilder exportPb = new ProcessBuilder("reg", "export", fullKey, dest.toString(), "/y");
            exportPb.redirectErrorStream(true);
            Process exportProcess = com.sbtools.util.ProcessManager.start(exportPb);
            boolean ok = exportProcess.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) {
                exportProcess.destroyForcibly();
                return false;
            }
            return exportProcess.exitValue() == 0 && Files.isRegularFile(dest);
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Delete matching regular files without following junctions/symlinks.
     */
    public static long deleteFilesMatching(Path root, int maxDepth, CancellationToken token,
            java.util.function.Predicate<Path> match) {
        if (root == null || !Files.isDirectory(root) || !isSafeToCleanDirectory(root)) return 0L;
        int depth = maxDepth > 0 ? maxDepth : DEFAULT_SCAN_MAX_DEPTH;
        java.util.concurrent.atomic.AtomicLong cleaned = new java.util.concurrent.atomic.AtomicLong();
        try {
            Files.walkFileTree(root, java.util.EnumSet.noneOf(FileVisitOption.class), depth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (shouldSkipWalkDir(d, root, attrs)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (!attrs.isRegularFile() || isProtectedPath(file)) return FileVisitResult.CONTINUE;
                    try {
                        if (match != null && !match.test(file)) return FileVisitResult.CONTINUE;
                        long size = attrs.size();
                        deletePermanently(file, token);
                        if (!Files.exists(file)) cleaned.addAndGet(size);
                    } catch (Exception ignored) {}
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {}
        return cleaned.get();
    }

    public static void scanFilesMatching(CleanupRow row, List<Path> dirs, int maxDepth,
            java.util.function.Predicate<Path> match, CancellationToken token) {
        java.util.concurrent.atomic.AtomicLong totalSize = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong itemCount = new java.util.concurrent.atomic.AtomicLong();
        int depth = maxDepth > 0 ? maxDepth : DEFAULT_SCAN_MAX_DEPTH;
        for (Path dir : dirs) {
            if (token != null && token.isCancelled()) break;
            if (dir == null || !Files.isDirectory(dir) || !isSafeToCleanDirectory(dir)) continue;
            try {
                Files.walkFileTree(dir, java.util.EnumSet.noneOf(FileVisitOption.class), depth, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                        if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                        if (shouldSkipWalkDir(d, dir, attrs)) return FileVisitResult.SKIP_SUBTREE;
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                        if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                        try {
                            if (match != null && !match.test(file)) return FileVisitResult.CONTINUE;
                            totalSize.addAndGet(attrs.size());
                            itemCount.incrementAndGet();
                        } catch (Exception ignored) {}
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Exception ignored) {}
        }
        row.setTotalBytes(totalSize.get());
        row.setItemCount((int) itemCount.get());
        row.setSizeOrCountText(formatBytes(totalSize.get())
                + (itemCount.get() > 0 ? " (" + itemCount.get() + " files)" : ""));
    }

    public static boolean shouldSkipWalkDir(Path d, Path root, BasicFileAttributes attrs) {
        if (d == null) return true;
        if (root != null && !d.equals(root) && isProtectedPath(d)) return true;
        return isReparseLike(d, attrs);
    }

    /**
     * True only for a real on-disk directory with no junction/symlink in any
     * path component. Intermediate reparse points are followed by
     * {@code NOFOLLOW} on the leaf only, so each prefix is checked.
     */
    public static boolean isRealDirectory(Path p) {
        if (p == null) return false;
        try {
            Path abs = p.toAbsolutePath().normalize();
            Path cur = abs.getRoot();
            if (cur == null) return false;
            int names = abs.getNameCount();
            if (names <= 0) return false;
            for (int i = 0; i < names; i++) {
                cur = cur.resolve(abs.getName(i));
                BasicFileAttributes attrs = Files.readAttributes(cur, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isDirectory() || isReparseLike(cur, attrs)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Junction/symlink/reparse. {@code dos:isReparsePoint} is not always
     * registered; IllegalArgumentException must not be treated as "is a
     * junction" or every directory would be skipped. NTFS junctions still
     * report {@code isOther()} under NOFOLLOW — load attrs when the caller
     * did not pass them, otherwise a junction looks like a plain directory.
     */
    public static boolean isReparseLike(Path p, BasicFileAttributes attrs) {
        if (p == null) return true;
        if (attrs == null) {
            try {
                attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (Exception e) {
                return true;
            }
        }
        if (attrs.isSymbolicLink() || attrs.isOther()) return true;
        try {
            if (Files.isSymbolicLink(p)) return true;
        } catch (Exception e) {
            return true;
        }
        try {
            Object reparse = Files.getAttribute(p, "dos:isReparsePoint", LinkOption.NOFOLLOW_LINKS);
            return Boolean.TRUE.equals(reparse);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean isWindowsServiceRunning(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder("sc", "query", serviceName);
            pb.redirectErrorStream(true);
            Process p = com.sbtools.util.ProcessManager.start(pb);
            boolean finished = waitForProcessUninterruptibly(p, 5_000L);
            if (!finished) { p.destroyForcibly(); return false; }
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return output.contains("RUNNING");
        } catch (Exception ignored) { return false; }
    }

    /**
     * Fail-closed: running, pending, or query failure. Used to restart a
     * service we stopped even when {@code sc query} itself failed.
     */
    public static boolean serviceShouldBeRestoredAfterStop(String serviceName) {
        return windowsServiceBusyOrUnknown(serviceName);
    }

    /**
     * Fail-closed busy check for Windows Update: wuauserv is often stopped on
     * Win10/11 while UsoSvc (Update Orchestrator) is the process actually
     * writing SoftwareDistribution\\Download. Unknown query results skip cleanup.
     */
    public static boolean isWindowsUpdateBusy() {
        return windowsServiceBusyOrUnknown("wuauserv")
                || windowsServiceBusyOrUnknown("UsoSvc")
                || windowsServiceBusyOrUnknown("TrustedInstaller")
                || dismProcessBusyOrUnknown();
    }

    /** Delivery Optimization cache: skip while DO or Windows Update is active. */
    public static boolean isDeliveryOptimizationBusy() {
        return isWindowsUpdateBusy() || windowsServiceBusyOrUnknown("DoSvc");
    }

    static boolean serviceQueryMeansBusy(String scQueryOutput, boolean queryOk) {
        if (!queryOk || scQueryOutput == null || scQueryOutput.isBlank()) return true;
        return scQueryOutput.contains("RUNNING") || scQueryOutput.contains("PENDING");
    }

    private static boolean windowsServiceBusyOrUnknown(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) return true;
        try {
            ProcessBuilder pb = new ProcessBuilder("sc", "query", serviceName);
            pb.redirectErrorStream(true);
            Process p = com.sbtools.util.ProcessManager.start(pb);
            boolean finished = waitForProcessUninterruptibly(p, 5_000L);
            if (!finished) {
                p.destroyForcibly();
                return true;
            }
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return serviceQueryMeansBusy(output, true);
        } catch (Exception ignored) {
            return true;
        }
    }

    private static boolean dismProcessBusyOrUnknown() {
        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH");
            pb.redirectErrorStream(true);
            Process p = com.sbtools.util.ProcessManager.start(pb);
            boolean finished = waitForProcessUninterruptibly(p, 5_000L);
            if (!finished) {
                p.destroyForcibly();
                return true;
            }
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return output.toLowerCase().contains("dism.exe");
        } catch (Exception ignored) {
            return true;
        }
    }

    /**
     * Wait without aborting on thread interrupt (used when a stopped Windows
     * service must be restarted even if the user cancelled the clean).
     * Restores the interrupt flag afterward.
     */
    public static boolean waitForProcessUninterruptibly(Process p, long timeoutMs) {
        if (p == null) return true;
        boolean interrupted = false;
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        try {
            while (p.isAlive() && System.currentTimeMillis() < deadline) {
                try {
                    if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) return true;
                } catch (InterruptedException ie) {
                    interrupted = true;
                }
            }
            return !p.isAlive();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public static String formatBytes(long bytes) {
        return FormatUtils.formatBytes(bytes);
    }
}
