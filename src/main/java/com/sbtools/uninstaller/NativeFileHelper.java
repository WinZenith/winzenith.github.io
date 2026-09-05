package com.sbtools.uninstaller;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sbtools.util.AppLogger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class NativeFileHelper {

    /**
     * Result of a recycle-aware delete attempt.
     */
    public enum DeleteOutcome {
        /** Removed immediately (permanent delete) or already gone. */
        DELETED,
        /** Moved to the Recycle Bin (recoverable). */
        RECYCLED,
        /** Could not be removed now; scheduled for deletion on next reboot. */
        QUEUED_FOR_REBOOT,
        /** Removal failed and could not be queued. */
        FAILED
    }

    /**
     * Attempts to delete a file or directory. If it cannot be deleted immediately because
     * it is in use or locked, it queues it for deletion on the next reboot.
     *
     * @param file The file or folder to delete.
     * @return true if deleted immediately, false if scheduled for reboot.
     */
    public static boolean deleteOrQueue(File file) {
        if (!file.exists()) {
            return true;
        }

        if (file.isDirectory()) {
            boolean allChildrenDeleted = true;
            File[] children = file.listFiles();
            if (children == null) {
                // Cannot list children (security restriction) — treat as failure
                // so the directory is queued for reboot deletion
                allChildrenDeleted = false;
            } else {
                for (File child : children) {
                    if (!deleteOrQueue(child)) {
                        allChildrenDeleted = false;
                    }
                }
            }
            // If any child was queued for reboot, queue the parent directory too
            // instead of attempting direct deletion (which would fail as not-empty)
            if (!allChildrenDeleted) {
                boolean scheduled = queueForReboot(file.getAbsolutePath());
                if (scheduled) {
                    AppLogger.info("Queued directory for deletion on next reboot (contains locked children): " + file.getAbsolutePath());
                } else {
                    AppLogger.warning("Failed to queue directory for reboot deletion: " + file.getAbsolutePath());
                }
                return false;
            }
        }

        // Try deleting immediately
        try {
            Path path = file.toPath();
            Files.delete(path);
            AppLogger.info("Deleted filesystem leftover immediately: " + file.getAbsolutePath());
            return true;
        } catch (Exception e) {
            // Log warning and try to schedule deletion for next reboot
            AppLogger.debug("Immediate deletion failed for: " + file.getAbsolutePath() + " (" + e.getMessage() + "). Scheduling for reboot...");
            boolean scheduled = queueForReboot(file.getAbsolutePath());
            if (scheduled) {
                AppLogger.info("Queued filesystem leftover for deletion on next reboot: " + file.getAbsolutePath());
            } else {
                AppLogger.warning("Failed to queue file for reboot deletion: " + file.getAbsolutePath());
            }
            return false;
        }
    }

    /**
     * Recycle-aware delete. When {@code preferRecycle} is true (and on Windows),
     * files/folders are first moved to the Recycle Bin via {@code SHFileOperation}
     * ({@code FO_DELETE | FOF_ALLOWUNDO}) so the user can recover them. Locked
     * items that cannot be recycled fall back to reboot-deletion queuing.
     *
     * @param file The file or folder to remove.
     * @param preferRecycle true to try the Recycle Bin first.
     * @return outcome describing what happened.
     */
    public static DeleteOutcome deleteWithOutcome(File file, boolean preferRecycle) {
        if (file == null) return DeleteOutcome.FAILED;
        if (!file.exists()) return DeleteOutcome.DELETED;
        if (preferRecycle) {
            try {
                if (moveToRecycleBin(file)) {
                    AppLogger.info("Moved leftover to Recycle Bin: " + file.getAbsolutePath());
                    return DeleteOutcome.RECYCLED;
                }
                AppLogger.debug("Recycle Bin move failed for: " + file.getAbsolutePath()
                        + " — falling back to permanent delete.");
            } catch (Throwable t) {
                AppLogger.debug("Recycle Bin unavailable for " + file.getAbsolutePath()
                        + " (" + t.getMessage() + ") — falling back to permanent delete.");
            }
        }
        boolean ok = deleteOrQueue(file);
        if (ok) {
            // deleteOrQueue returns true both for immediate delete and already-gone.
            // If the file is gone now and we did not recycle, treat as DELETED.
            return DeleteOutcome.DELETED;
        }
        // deleteOrQueue queued for reboot when it returns false after attempting
        // MoveFileEx; distinguish queued vs hard failure by existence + best effort.
        return DeleteOutcome.QUEUED_FOR_REBOOT;
    }

    /**
     * Moves a file or directory to the Recycle Bin using Shell32 SHFileOperation
     * with FOF_ALLOWUNDO. No confirmation UI is shown. Returns true only when the
     * path no longer exists afterwards.
     */
    public static boolean moveToRecycleBin(File file) {
        if (file == null || !file.exists()) return true;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (!os.contains("win")) return false;
            com.sun.jna.platform.win32.Shell32 shell =
                    com.sun.jna.platform.win32.Shell32.INSTANCE;
            com.sun.jna.platform.win32.Shell32.SHFILEOPSTRUCT op =
                    new com.sun.jna.platform.win32.Shell32.SHFILEOPSTRUCT();
            op.wFunc = com.sun.jna.platform.win32.Shell32.FO_DELETE;
            // pFrom must be double-null-terminated; JNA marshals Java String with
            // a single terminator, so append an explicit extra null.
            op.pFrom = file.getAbsolutePath() + "\0";
            op.pTo = null;
            op.fFlags = com.sun.jna.platform.win32.Shell32.FOF_ALLOWUNDO
                    | com.sun.jna.platform.win32.Shell32.FOF_NOCONFIRMATION
                    | com.sun.jna.platform.win32.Shell32.FOF_SILENT
                    | com.sun.jna.platform.win32.Shell32.FOF_NOERRORUI;
            int res = shell.SHFileOperation(op);
            if (res != 0) {
                AppLogger.debug("SHFileOperation returned " + res
                        + " for " + file.getAbsolutePath());
                return false;
            }
            return !new File(file.getAbsolutePath()).exists();
        } catch (Throwable t) {
            AppLogger.debug("moveToRecycleBin failed for " + file.getAbsolutePath()
                    + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Invokes Kernel32 MoveFileEx API with MOVEFILE_DELAY_UNTIL_REBOOT flag.
     *
     * @param absolutePath Absolute path to the file or directory.
     * @return true if registration succeeded, false otherwise.
     */
    public static boolean queueForReboot(String absolutePath) {
        try {
            // Kernel32.MOVEFILE_DELAY_UNTIL_REBOOT is 4
            boolean result = Kernel32.INSTANCE.MoveFileEx(absolutePath, null, new DWORD(Kernel32.MOVEFILE_DELAY_UNTIL_REBOOT));
            if (!result) {
                int errorCode = Kernel32.INSTANCE.GetLastError();
                AppLogger.warning("MoveFileEx failed for " + absolutePath + " with Kernel32 error code: " + errorCode);
            }
            return result;
        } catch (Throwable t) {
            AppLogger.error("Failed to execute MoveFileEx via JNA for path: " + absolutePath, t);
            return false;
        }
    }
}
