package com.sbtools.startup;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Lightweight append-only audit log for startup changes.
 *
 * <p>Every toggle/delete/restore writes one line so users (and support) can
 * answer "what changed, when". Stored next to the portable base when writable,
 * otherwise under LOCALAPPDATA — same strategy as {@code StartupService} backups.
 * Failures are logged but never break the primary operation.</p>
 */
public final class StartupAuditLog {

    private static final String FILE_NAME = "startup-changes.log";
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final Object LOCK = new Object();

    private StartupAuditLog() {
    }

    public enum Action {
        TOGGLE, DELETE, RESTORE, RESTORE_BACKUP_DELETED
    }

    public static Path logFile() {
        Path portable = AppPaths.portableBaseDir();
        if (portable != null) {
            try {
                Path dir = portable.resolve("logs");
                Files.createDirectories(dir);
                if (Files.isWritable(dir)) {
                    return dir.resolve(FILE_NAME);
                }
            } catch (Exception ignored) {
                // fall through to localAppData
            }
        }
        return AppPaths.logsDir().resolve(FILE_NAME);
    }

    public static void record(Action action, StartupItem item, String detail) {
        try {
            String line = format(action.name(), describe(item), detail);
            append(line);
        } catch (Exception e) {
            AppLogger.warning("Failed to write startup audit log: " + e.getMessage());
        }
    }

    public static void record(Action action, StartupService.StartupBackupEntry entry, String detail) {
        try {
            String what = entry == null ? "(unknown)"
                    : entry.getType() + ":" + entry.getName() + " @" + entry.getLocation();
            append(format(action.name(), what, detail));
        } catch (Exception e) {
            AppLogger.warning("Failed to write startup audit log: " + e.getMessage());
        }
    }

    public static void recordRaw(String action, String what, String detail) {
        try {
            append(format(action, what, detail));
        } catch (Exception e) {
            AppLogger.warning("Failed to write startup audit log: " + e.getMessage());
        }
    }

    private static String describe(StartupItem item) {
        if (item == null) {
            return "(unknown)";
        }
        return item.getType() + ":" + item.getName() + " @" + item.getLocation()
                + " enabled=" + item.isEnabled();
    }

    private static String format(String action, String what, String detail) {
        String ts = FMT.format(Instant.now());
        String safeDetail = detail == null ? "" : detail.replaceAll("\\R", " ");
        if (safeDetail.length() > 500) {
            safeDetail = safeDetail.substring(0, 500) + "...";
        }
        return ts + " [" + action + "] " + what + (safeDetail.isEmpty() ? "" : " | " + safeDetail);
    }

    private static void append(String line) throws IOException {
        Path file = logFile();
        synchronized (LOCK) {
            try {
                Files.createDirectories(file.getParent());
            } catch (Exception ignored) {
            }
            Files.writeString(file, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }
}
