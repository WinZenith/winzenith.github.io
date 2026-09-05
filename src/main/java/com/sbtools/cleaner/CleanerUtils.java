package com.sbtools.cleaner;

import com.sbtools.util.AppLogger;
import com.sbtools.util.CancellationToken;
import com.sbtools.util.FormatUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class CleanerUtils {

    private static final Set<String> PROTECTED_ABSOLUTE_PREFIXES = new HashSet<>();

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
        }
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\system32");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\syswow64");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\winsxs");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\boot");
        PROTECTED_ABSOLUTE_PREFIXES.add("c:\\windows\\fonts");
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
        if (p != null && Files.exists(p)) {
            list.add(p);
        }
    }

    public static void addPath(List<Path> list, String pathStr) {
        if (pathStr != null && !pathStr.isBlank()) {
            Path p = Paths.get(pathStr);
            if (Files.exists(p)) {
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
            int exeIdx = path.toLowerCase().lastIndexOf(".exe");
            if (exeIdx > 0) {
                String afterExe = path.substring(exeIdx + 4);
                int spaceIdx = afterExe.indexOf(" -");
                if (spaceIdx >= 0) {
                    path = path.substring(0, exeIdx + 4 + spaceIdx);
                }
                spaceIdx = afterExe.indexOf("/");
                if (spaceIdx >= 0) {
                    path = path.substring(0, exeIdx + 4 + spaceIdx);
                }
            }
            int dllIdx = path.toLowerCase().lastIndexOf(".dll");
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
        if (!path.contains("\\") && !path.contains("/")) {
            if (path.toLowerCase().endsWith(".dll") || path.toLowerCase().endsWith(".cpl")) {
                return path;
            }
            return null;
        }
        path = expandEnvironmentVariables(path);
        return path;
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
        scanDirectorySizes(row, dirs, -1);
    }

    public static void scanDirectorySizes(CleanupRow row, List<Path> dirs, int maxDepth) {
        long totalSize = 0;
        int itemCount = 0;
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> walk = maxDepth > 0 ? Files.walk(dir, maxDepth) : Files.walk(dir)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .collect(java.util.stream.Collectors.summarizingLong(p -> {
                                try { return Files.size(p); } catch (Exception e) { return p.toFile().length(); }
                            }));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    public static void scanDirectorySizesOlderThan(CleanupRow row, List<Path> dirs, java.time.Duration maxAge) {
        long totalSize = 0;
        int itemCount = 0;
        long cutoff = System.currentTimeMillis() - maxAge.toMillis();
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                try {
                                    if (Files.isHidden(p)) return false;
                                    long lastModified = p.toFile().lastModified();
                                    return lastModified > 0 && lastModified < cutoff;
                                } catch (Exception e) {
                                    return false;
                                }
                            })
                            .collect(java.util.stream.Collectors.summarizingLong(p -> {
                                try { return Files.size(p); } catch (Exception e) { return p.toFile().length(); }
                            }));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    public static long cleanDirectoryPattern(List<Path> dirs) {
        return cleanDirectoryPattern(dirs, null);
    }

    public static long cleanDirectoryPattern(List<Path> dirs, CancellationToken token) {
        long cleaned = 0;
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                cleaned += deleteDirectoryContents(dir, token);
            }
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
                cleaned += deleteDirectoryContentsOlderThan(dir, cutoff, token);
            }
        }
        return cleaned;
    }

    public static long deleteDirectoryContents(Path dir) {
        return deleteDirectoryContents(dir, null);
    }

    public static long deleteDirectoryContents(Path dir, CancellationToken token) {
        java.util.concurrent.atomic.AtomicLong cleaned = new java.util.concurrent.atomic.AtomicLong();
        try {
            Files.walkFileTree(dir, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (!d.equals(dir) && isProtectedPath(d)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // Skip reparse points / junctions to avoid traversing outside target
                    if (!d.equals(dir) && attrs.isOther()) {
                        try {
                            Object reparse = Files.getAttribute(d, "dos:isReparsePoint", java.nio.file.LinkOption.NOFOLLOW_LINKS);
                            if (reparse instanceof Boolean && (Boolean) reparse) return FileVisitResult.SKIP_SUBTREE;
                        } catch (Exception ignored) {}
                    }
                    if (attrs.isSymbolicLink()) return FileVisitResult.SKIP_SUBTREE;
                    try {
                        if (Files.isSymbolicLink(d)) return FileVisitResult.SKIP_SUBTREE;
                    } catch (Exception ignored) {}
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (isProtectedPath(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        Files.deleteIfExists(file);
                        cleaned.addAndGet(attrs.size());
                    } catch (Exception ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (!d.equals(dir) && !isProtectedPath(d)) {
                        try {
                            Files.deleteIfExists(d);
                        } catch (Exception ignored) {
                        }
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

    public static long deleteDirectoryContentsOlderThan(Path dir, long cutoffMillis) {
        return deleteDirectoryContentsOlderThan(dir, cutoffMillis, null);
    }

    public static long deleteDirectoryContentsOlderThan(Path dir, long cutoffMillis, CancellationToken token) {
        long cleaned = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> sorted = walk.sorted(Comparator.comparingInt(Path::getNameCount).reversed()
                    .thenComparing(Comparator.reverseOrder())).toList();
            for (Path f : sorted) {
                if (token != null && token.isCancelled()) break;
                if (f.equals(dir)) continue;
                    try {
                        long lastModified = f.toFile().lastModified();
                        if (lastModified > 0 && lastModified >= cutoffMillis) continue;
                        if (Files.isHidden(f)) continue;
                        if (isProtectedPath(f)) continue;
                        if (Files.isRegularFile(f) || Files.isSymbolicLink(f)) {
                        long size = Files.size(f);
                        deletePermanently(f, token);
                        if (!Files.exists(f)) cleaned += size;
                    } else if (Files.isDirectory(f)) {
                        deletePermanently(f, token);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return cleaned;
    }

    public static void deletePermanently(Path source) {
        deletePermanently(source, null);
    }

    public static void deletePermanently(Path source, CancellationToken token) {
        if (token != null && token.isCancelled()) return;
        if (isProtectedPath(source)) {
            AppLogger.warning("Skipping protected path: " + source);
            return;
        }
        // Handle reparse points / junctions safely: delete link itself, not target
        try {
            if (Files.isSymbolicLink(source)) {
                Files.deleteIfExists(source);
                return;
            }
            try {
                Object reparse = Files.getAttribute(source, "dos:isReparsePoint", java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (reparse instanceof Boolean && (Boolean) reparse) {
                    Files.deleteIfExists(source);
                    return;
                }
            } catch (Exception ignored) {}
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

    public static String formatBytes(long bytes) {
        return FormatUtils.formatBytes(bytes);
    }
}
