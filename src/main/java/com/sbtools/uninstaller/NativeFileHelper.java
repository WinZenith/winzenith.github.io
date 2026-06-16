package com.sbtools.uninstaller;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sbtools.util.AppLogger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public class NativeFileHelper {

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
            // Recursively delete contents first so the directory can be deleted
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteOrQueue(child);
                }
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
