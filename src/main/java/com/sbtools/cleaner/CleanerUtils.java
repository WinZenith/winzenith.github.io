package com.sbtools.cleaner;

import com.sbtools.util.AppLogger;
import com.sbtools.util.FormatUtils;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class CleanerUtils {

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
        String path = rawValue;
        if (path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1);
        }
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
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
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
                                    return true;
                                }
                            })
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
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
        long cleaned = 0;
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                cleaned += deleteDirectoryContents(dir);
            }
        }
        return cleaned;
    }

    public static long cleanDirectoryPatternOlderThan(List<Path> dirs, java.time.Duration maxAge) {
        long cleaned = 0;
        long cutoff = System.currentTimeMillis() - maxAge.toMillis();
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                cleaned += deleteDirectoryContentsOlderThan(dir, cutoff);
            }
        }
        return cleaned;
    }

    public static long deleteDirectoryContents(Path dir) {
        java.util.concurrent.atomic.AtomicLong cleaned = new java.util.concurrent.atomic.AtomicLong();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file);
                        cleaned.addAndGet(attrs.size());
                    } catch (Exception ignored) {
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc) {
                    if (!d.equals(dir)) {
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
        long cleaned = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> sorted = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path f : sorted) {
                if (f.equals(dir)) continue;
                    try {
                        long lastModified = f.toFile().lastModified();
                        if (lastModified > 0 && lastModified >= cutoffMillis) continue;
                        if (Files.isHidden(f)) continue;
                        if (Files.isRegularFile(f) || Files.isSymbolicLink(f)) {
                        long size = Files.size(f);
                        deletePermanently(f);
                        if (!Files.exists(f)) cleaned += size;
                    } else if (Files.isDirectory(f)) {
                        deletePermanently(f);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return cleaned;
    }

    public static void deletePermanently(Path source) {
        try {
            Files.deleteIfExists(source);
        } catch (IOException e) {
            AppLogger.warning("Could not delete " + source + ": " + e.getMessage());
        }
    }

    public static boolean isEmptyDirectory(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            return !ds.iterator().hasNext();
        } catch (Exception e) {
            return false;
        }
    }

    public static String formatBytes(long bytes) {
        return FormatUtils.formatBytes(bytes);
    }
}
