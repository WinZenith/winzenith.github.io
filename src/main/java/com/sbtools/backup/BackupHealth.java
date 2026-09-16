package com.sbtools.backup;

import com.sbtools.util.AppPaths;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared, single-pass filesystem health inspection for driver backups.
 * Centralises the safety checks (shallow / system locations) so the UI
 * and the service agree on what "healthy" means, and avoids double
 * {@code Files.walk} scans (size + INF count in one pass).
 */
public final class BackupHealth {

    /** ponytail: cap walk size for pathological trees; raise if pnputil layout changes */
    public static final int MAX_VISIT_FILES = 50_000;

    private BackupHealth() {
    }

    public enum Status {
        OK,
        MISSING,
        EMPTY,
        UNREADABLE,
        UNSAFE
    }

    public record Stats(long bytes, long infCount, long fileCount, Status status) {
    }

    /** Manual-only retention warnings (no auto-delete, ever). */
    public static final long WARNING_SIZE_BYTES = 5L * 1024 * 1024 * 1024;
    public static final int WARNING_COUNT = 50;
    public static final int WARNING_AGE_DAYS = 90;
    /** Minimum free space we like to see on the backup volume. */
    public static final long MIN_FREE_BYTES = 500L * 1024 * 1024;

    public static Stats inspect(String backupFolder) {
        if (backupFolder == null || backupFolder.isBlank()) {
            return new Stats(0, 0, 0, Status.MISSING);
        }
        Path folder;
        try {
            folder = Path.of(backupFolder);
        } catch (Exception e) {
            return new Stats(0, 0, 0, Status.UNREADABLE);
        }
        return inspect(folder);
    }

    public static Stats inspect(Path folder) {
        if (folder == null) {
            return new Stats(0, 0, 0, Status.MISSING);
        }
        if (!isPathShapeSafe(folder) || isReparseOrSymlink(folder)) {
            return new Stats(0, 0, 0, Status.UNSAFE);
        }
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return new Stats(0, 0, 0, Status.MISSING);
        }
        long[] acc = {0, 0, 0}; // bytes, inf, files
        boolean[] capped = {false};
        try {
            walkRegularFiles(folder, (p, attrs) -> {
                acc[2]++;
                if (acc[2] > MAX_VISIT_FILES) {
                    capped[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                acc[0] += attrs.size();
                try {
                    if (p.getFileName().toString().toLowerCase().endsWith(".inf")) {
                        acc[1]++;
                    }
                } catch (Exception ignored) {
                }
                return FileVisitResult.CONTINUE;
            });
        } catch (IOException e) {
            return new Stats(acc[0], acc[1], acc[2], Status.UNREADABLE);
        }
        long bytes = acc[0];
        long inf = acc[1];
        long files = acc[2];
        if (capped[0]) {
            long infSeen = inf > 0 ? inf : shallowInfFileCount(folder);
            if (infSeen > 0) {
                return new Stats(bytes, infSeen, files, Status.OK);
            }
            return new Stats(bytes, inf, files, Status.UNREADABLE);
        }
        if (inf == 0) {
            return new Stats(bytes, 0, files, Status.EMPTY);
        }
        return new Stats(bytes, inf, files, Status.OK);
    }

    /**
     * On-disk {@code .inf} basenames under {@code folder} (symlinks skipped).
     * Empty when the path is missing, unsafe, or contains no INF files.
     */
    public static List<String> listInfBasenames(Path folder) {
        List<String> names = new ArrayList<>();
        if (folder == null || !isPathShapeSafe(folder) || isReparseOrSymlink(folder)
                || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return names;
        }
        int[] files = {0};
        try {
            walkRegularFiles(folder, (p, attrs) -> {
                files[0]++;
                if (files[0] > MAX_VISIT_FILES) {
                    return FileVisitResult.TERMINATE;
                }
                try {
                    String name = p.getFileName().toString();
                    if (name.toLowerCase().endsWith(".inf")) {
                        names.add(name);
                    }
                } catch (Exception ignored) {
                }
                return FileVisitResult.CONTINUE;
            });
        } catch (IOException ignored) {
        }
        return names;
    }

    /**
     * Count regular .inf files matching {@code infBasename} (case-insensitive name).
     * Does not follow symbolic links or directory junctions.
     */
    public static int countMatchingInfFiles(Path folder, String infBasename) throws IOException {
        if (folder == null || infBasename == null || infBasename.isBlank()
                || isReparseOrSymlink(folder)) {
            return 0;
        }
        String want = infBasename.trim();
        int[] acc = {0, 0}; // matches, files
        walkRegularFiles(folder, (p, attrs) -> {
            acc[1]++;
            if (acc[1] > MAX_VISIT_FILES) {
                return FileVisitResult.TERMINATE;
            }
            if (p.getFileName().toString().equalsIgnoreCase(want)) {
                acc[0]++;
                if (acc[0] > 1) {
                    return FileVisitResult.TERMINATE;
                }
            }
            return FileVisitResult.CONTINUE;
        });
        return acc[0];
    }

    @FunctionalInterface
    private interface RegularFileFn {
        FileVisitResult visit(Path file, BasicFileAttributes attrs) throws IOException;
    }

    /** Walk regular files; never descend into junctions/symlinks. */
    static void walkRegularFiles(Path root, RegularFileFn fn) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && isReparseOrSymlink(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (isReparseOrSymlink(file) || !attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                return fn.visit(file, attrs);
            }
        });
    }

    /**
     * Shape-only guard against walking system roots on a tampered index.
     * Paths under known app backup roots (portable, LOCALAPPDATA, custom
     * settings dir) are allowed even when that root lives under Program Files.
     */
    public static boolean isPathShapeSafe(Path folder) {
        try {
            Path norm = folder.toAbsolutePath().normalize();
            String s = norm.toString().toLowerCase().replace('/', '\\');
            if (s.length() <= 3 || s.matches("^[a-z]:\\\\?$")) {
                return false;
            }
            if (s.contains("\\windows\\") || s.endsWith("\\windows") || s.equals("c:\\windows")) {
                return false;
            }
            if (isUnderKnownBackupRoot(norm)) {
                return norm.getNameCount() >= 2;
            }
            if (s.contains("\\program files") || s.contains("\\programdata")) {
                return false;
            }
            return norm.getNameCount() >= 2;
        } catch (Exception e) {
            return false;
        }
    }

    /** True when folder is under portable or LOCALAPPDATA backups root (or custom settings root). */
    private static boolean isUnderKnownBackupRoot(Path folder) {
        for (Path root : knownBackupRoots()) {
            try {
                if (root != null && (folder.startsWith(root) || folder.equals(root))) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static List<Path> knownBackupRoots() {
        List<Path> roots = new ArrayList<>();
        try {
            roots.add(AppPaths.backupsRootNoCreate().toAbsolutePath().normalize());
        } catch (Exception ignored) {
        }
        try {
            roots.add(AppPaths.legacyBackupsRoot().toAbsolutePath().normalize());
        } catch (Exception ignored) {
        }
        try {
            com.sbtools.settings.AppSettings s = new com.sbtools.settings.SettingsStore().load();
            if (s != null && s.backupDirectory() != null && !s.backupDirectory().isBlank()) {
                Path custom = Path.of(s.backupDirectory().trim()).toAbsolutePath().normalize();
                roots.add(custom);
            }
        } catch (Exception ignored) {
        }
        return roots;
    }

    /**
     * True for symlinks and Windows junctions/mount points without following them.
     * {@code Files.isSymbolicLink} and the {@code dos:*} views miss
     * {@code IO_REPARSE_TAG_MOUNT_POINT}; Win32 {@code FILE_ATTRIBUTE_REPARSE_POINT}
     * is the reliable signal.
     */
    public static boolean isReparseOrSymlink(Path path) {
        if (path == null) {
            return false;
        }
        try {
            if (Files.isSymbolicLink(path)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            String abs = path.toAbsolutePath().toString();
            int attrs = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetFileAttributes(abs);
            if (attrs != com.sun.jna.platform.win32.WinNT.INVALID_FILE_ATTRIBUTES
                    && (attrs & com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || a.isOther()) {
                return true;
            }
        } catch (Exception ignored) {
        }
        for (String attr : new String[] {"dos:isReparsePoint", "dos:reparsePoint"}) {
            try {
                Object v = Files.getAttribute(path, attr, LinkOption.NOFOLLOW_LINKS);
                if (Boolean.TRUE.equals(v)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * Deletes a backup tree without following junctions/symlinks. A reparse
     * root is removed as a link only — never walked — so Delete cannot escape
     * into {@code C:\Windows} via a planted junction.
     */
    public static void deleteTree(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (isReparseOrSymlink(directory)) {
            try {
                Files.setAttribute(directory, "dos:readonly", Boolean.FALSE);
            } catch (Exception ignored) {
            }
            Files.deleteIfExists(directory);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Could not delete reparse backup path: " + directory);
            }
            return;
        }
        List<Path> failed = new ArrayList<>();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(directory) && isReparseOrSymlink(dir)) {
                    try {
                        Files.setAttribute(dir, "dos:readonly", Boolean.FALSE);
                    } catch (Exception ignored) {
                    }
                    try {
                        Files.deleteIfExists(dir);
                    } catch (IOException e) {
                        failed.add(dir);
                    }
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    Files.setAttribute(file, "dos:readonly", Boolean.FALSE);
                } catch (Exception ignored) {
                }
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    failed.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                try {
                    Files.setAttribute(dir, "dos:readonly", Boolean.FALSE);
                } catch (Exception ignored) {
                }
                try {
                    Files.deleteIfExists(dir);
                } catch (IOException e) {
                    failed.add(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (!failed.isEmpty() || Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Could not delete backup folder completely: " + directory
                    + (failed.isEmpty() ? "" : " (" + failed.size() + " path(s) failed)"));
        }
    }

    public static String statusLabel(Status status) {
        return switch (status) {
            case OK -> "OK";
            case MISSING -> "Missing";
            case EMPTY -> "No INF";
            case UNREADABLE -> "Unreadable";
            case UNSAFE -> "Unsafe path";
        };
    }

    public static boolean isHealthy(Status status) {
        return status == Status.OK;
    }

    public static long usableSpace(Path root) {
        try {
            if (root == null) {
                return -1;
            }
            Path probe = root.toAbsolutePath().normalize();
            // Walk up to nearest existing ancestor for FileStore lookup.
            while (probe != null && !Files.exists(probe)) {
                probe = probe.getParent();
            }
            if (probe == null) {
                return -1;
            }
            return Files.getFileStore(probe).getUsableSpace();
        } catch (Exception e) {
            return -1;
        }
    }

    /** Shallow .inf probe when a full walk hits {@link #MAX_VISIT_FILES} before seeing an INF. */
    static long shallowInfFileCount(Path folder) {
        if (folder == null || isReparseOrSymlink(folder)
                || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        long[] found = {0};
        try {
            walkRegularFiles(folder, (p, attrs) -> {
                if (p.getFileName().toString().toLowerCase().endsWith(".inf")) {
                    found[0] = 1;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            });
        } catch (IOException ignored) {
        }
        return found[0];
    }

    public static boolean isOld(Instant createdAt, int days) {
        if (createdAt == null) {
            return false;
        }
        try {
            return createdAt.isBefore(Instant.now().minus(days, ChronoUnit.DAYS));
        } catch (Exception e) {
            return false;
        }
    }
}
