package com.sbtools.drivers;

import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.settings.AppSettings;
import com.sbtools.util.AdminCheck;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-install safety checks for driver updates.
 *
 * <p>Additive only: never performs installs, backups, or system changes.
 * Returns a {@link PreflightResult} so callers can block on errors
 * (no admin, no disk space) while showing warnings without blocking.</p>
 */
public final class DriverPreflightService {

    /** Minimum free bytes required in the download directory to start an install. */
    private static final long MIN_DOWNLOAD_FREE_BYTES = 500L * 1024 * 1024; // 500 MB
    /** Minimum free bytes required on the backups volume when auto-backup is on. */
    private static final long MIN_BACKUP_FREE_BYTES = 1024L * 1024 * 1024; // 1 GB

    private DriverPreflightService() {
    }

    public record PreflightResult(boolean ok, String blockReason, List<String> warnings) {
        public boolean hasWarnings() {
            return warnings != null && !warnings.isEmpty();
        }
    }

    /**
     * Runs all pre-flight checks. Never throws: failures are reported
     * as {@code ok=false} with a human-readable reason.
     */
    public static PreflightResult check(DriverUpdateCandidate candidate, AppSettings settings,
                                        RebootPendingStore rebootStore) {
        List<String> warnings = new ArrayList<>();
        try {
            // 1) Admin is mandatory for pnputil/msiexec/setup. Fail fast.
            boolean admin;
            try {
                admin = AdminCheck.isRunningAsAdminFresh();
            } catch (Exception ex) {
                admin = false;
            }
            if (!admin) {
                return new PreflightResult(false,
                        "Administrator rights required. Please restart the app as administrator and retry.",
                        warnings);
            }

            if (candidate == null || candidate.installed() == null) {
                return new PreflightResult(false, "Invalid driver candidate (missing device info).", warnings);
            }

            // 2) Download directory writable + enough free space.
            String configuredDir = settings != null ? settings.downloadDirectory() : null;
            Path downloadsDir = (configuredDir != null && !configuredDir.isBlank())
                    ? Path.of(configuredDir)
                    : Paths.get(System.getProperty("user.home"), "Downloads");
            try {
                Files.createDirectories(downloadsDir);
            } catch (Exception ex) {
                return new PreflightResult(false,
                        "Download directory is not writable: " + downloadsDir + " (" + ex.getMessage() + ")",
                        warnings);
            }
            try {
                long free = Files.getFileStore(downloadsDir).getUsableSpace();
                if (free < MIN_DOWNLOAD_FREE_BYTES) {
                    return new PreflightResult(false,
                            "Not enough free space in " + downloadsDir + " (" + formatBytes(free)
                                    + " free, need at least " + formatBytes(MIN_DOWNLOAD_FREE_BYTES)
                                    + "). Free disk space and retry. No changes were made.",
                            warnings);
                }
            } catch (Exception ex) {
                AppLogger.debug("Preflight: could not query download free space: " + ex.getMessage());
            }

            // 3) Backup volume space when auto-backup is enabled.
            if (settings != null && settings.autoBackupDrivers()) {
                try {
                    Path backupsRoot = AppPaths.backupsRoot(settings);
                    // Don't create here; probe parent store when dir doesn't exist yet.
                    Path probe = Files.exists(backupsRoot) ? backupsRoot
                            : backupsRoot.toAbsolutePath().getParent() != null
                            ? backupsRoot.toAbsolutePath().getParent() : Paths.get(System.getProperty("user.home"));
                    long backupFree = Files.getFileStore(probe).getUsableSpace();
                    if (backupFree < MIN_BACKUP_FREE_BYTES) {
                        warnings.add("Low disk space on backup volume (" + formatBytes(backupFree)
                                + " free). Backup may fail; install will abort rather than proceed unsafely.");
                    }
                } catch (Exception ex) {
                    AppLogger.debug("Preflight: could not query backup free space: " + ex.getMessage());
                }
                if (candidate.installed().infName() == null || candidate.installed().infName().isBlank()) {
                    warnings.add("Automatic driver backup is not supported for this device (no INF name). "
                            + "A system restore point will be the only rollback.");
                }
            }

            // 4) Reboot-pending warning: installing over a pending reboot is allowed
            // but the device may need two restarts.
            try {
                if (rebootStore != null && candidate.installed().deviceId() != null
                        && rebootStore.isPending(candidate.installed().deviceId())) {
                    warnings.add("This driver already has a pending restart. "
                            + "Installing again may require two restarts to take effect.");
                }
            } catch (Exception ignored) {
            }

            // 5) Device health warning (non-OK status).
            String status = candidate.installed().status();
            if (status != null && !status.isBlank() && !"OK".equalsIgnoreCase(status)) {
                warnings.add("Device reports status '" + status
                        + "'. The update may not resolve a hardware problem; check Device Manager if it persists.");
            }

            return new PreflightResult(true, null, warnings);
        } catch (Exception ex) {
            AppLogger.warning("Preflight check failed: " + ex.getMessage());
            return new PreflightResult(false, "Pre-flight check failed: " + ex.getMessage(), warnings);
        }
    }

    /** Shared helpers so single + batch paths classify candidates identically. */
    public static boolean isWindowsUpdateInstall(DriverUpdateCandidate c) {
        return c != null && "WindowsUpdate".equals(c.source())
                && c.packageId() != null && !c.packageId().isBlank();
    }

    public static boolean needsManualDownload(DriverUpdateCandidate c) {
        if (c == null) return true;
        if (isWindowsUpdateInstall(c)) return false;
        return c.downloadUrl() == null || c.downloadUrl().isBlank();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
