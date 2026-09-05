package com.sbtools.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Shared, single-pass filesystem health inspection for driver backups.
 * Centralises the safety checks (shallow / system locations) so the UI
 * and the service agree on what "healthy" means, and avoids double
 * {@code Files.walk} scans (size + INF count in one pass).
 */
public final class BackupHealth {

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
        if (!isPathShapeSafe(folder)) {
            return new Stats(0, 0, 0, Status.UNSAFE);
        }
        if (!Files.isDirectory(folder)) {
            return new Stats(0, 0, 0, Status.MISSING);
        }
        long bytes = 0;
        long inf = 0;
        long files = 0;
        try (var stream = Files.walk(folder, 5)) {
            var it = stream.iterator();
            while (it.hasNext()) {
                Path p = it.next();
                boolean isFile;
                try {
                    isFile = Files.isRegularFile(p) && !Files.isSymbolicLink(p);
                } catch (Exception e) {
                    continue;
                }
                if (!isFile) {
                    continue;
                }
                files++;
                try {
                    bytes += Files.size(p);
                } catch (IOException ignored) {
                }
                try {
                    if (p.toString().toLowerCase().endsWith(".inf")) {
                        inf++;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (IOException e) {
            return new Stats(bytes, inf, files, Status.UNREADABLE);
        }
        if (inf == 0) {
            return new Stats(bytes, 0, files, Status.EMPTY);
        }
        return new Stats(bytes, inf, files, Status.OK);
    }

    /**
     * Shape-only guard against walking system roots on a tampered index.
     * Mirrors the historical checks in {@code RestoreRow} and
     * {@code DriverBackupService} without requiring settings access.
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
            if (s.contains("\\program files") || s.contains("\\programdata")) {
                return false;
            }
            return norm.getNameCount() >= 2;
        } catch (Exception e) {
            return false;
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
