package com.sbtools.uninstaller;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sbtools.util.AppLogger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

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
        DeleteOutcome o = deleteOrQueueWithOutcome(file, null);
        return o == DeleteOutcome.DELETED;
    }

    public static DeleteOutcome deleteOrQueueWithOutcome(File file) {
        return deleteOrQueueWithOutcome(file, null);
    }

    public static DeleteOutcome deleteOrQueueWithOutcome(File file, AtomicBoolean cancelled) {
        if (file == null) return DeleteOutcome.FAILED;
        if (cancelled(cancelled)) return DeleteOutcome.FAILED;
        Path p;
        try {
            p = file.toPath();
        } catch (Exception e) {
            return DeleteOutcome.FAILED;
        }
        // Never follow junctions. File.isDirectory() follows links, so recursing
        // after a failed attribute read would wipe the TARGET (e.g. a Users junction).
        Boolean link = isLinkOrReparse(p);
        if (link == null) {
            AppLogger.warning("Refused to recurse into path with unknown reparse state: "
                    + file.getAbsolutePath());
            return deleteNodeOnly(file, cancelled);
        }
        if (link) {
            return deleteNodeOnly(file, cancelled);
        }
        if (!file.exists()) {
            return DeleteOutcome.DELETED;
        }

        if (file.isDirectory()) {
            boolean allChildrenDeleted = true;
            File[] children = file.listFiles();
            if (children == null) {
                // Cannot list children — refuse reboot-queue. MoveFileEx on an
                // unlistable tree can still remove unknown inner junctions on reboot.
                AppLogger.warning("Refused to reboot-queue unlistable directory: " + file.getAbsolutePath());
                return DeleteOutcome.FAILED;
            }
            for (File child : children) {
                if (cancelled(cancelled)) {
                    allChildrenDeleted = false;
                    break;
                }
                DeleteOutcome o = deleteOrQueueWithOutcome(child, cancelled);
                if (o != DeleteOutcome.DELETED) {
                    allChildrenDeleted = false;
                }
            }
            // Cancel must not reboot-queue the leftover tree — that would still
            // wipe remaining files after the user aborted.
            if (cancelled(cancelled)) return DeleteOutcome.FAILED;
            // If any child was queued for reboot, queue the parent directory too
            // instead of attempting direct deletion (which would fail as not-empty)
            if (!allChildrenDeleted) {
                boolean scheduled = queueForReboot(file.getAbsolutePath());
                if (scheduled) {
                    AppLogger.info("Queued directory for deletion on next reboot (contains locked children): " + file.getAbsolutePath());
                    return DeleteOutcome.QUEUED_FOR_REBOOT;
                } else {
                    AppLogger.warning("Failed to queue directory for reboot deletion: " + file.getAbsolutePath());
                    return DeleteOutcome.FAILED;
                }
            }
        }

        if (cancelled(cancelled)) return DeleteOutcome.FAILED;
        // Try deleting immediately
        try {
            Path path = file.toPath();
            Files.delete(path);
            AppLogger.info("Deleted filesystem leftover immediately: " + file.getAbsolutePath());
            return DeleteOutcome.DELETED;
        } catch (Exception e) {
            if (cancelled(cancelled)) return DeleteOutcome.FAILED;
            // Log warning and try to schedule deletion for next reboot
            AppLogger.debug("Immediate deletion failed for: " + file.getAbsolutePath() + " (" + e.getMessage() + "). Scheduling for reboot...");
            boolean scheduled = queueForReboot(file.getAbsolutePath());
            if (scheduled) {
                AppLogger.info("Queued filesystem leftover for deletion on next reboot: " + file.getAbsolutePath());
                return DeleteOutcome.QUEUED_FOR_REBOOT;
            } else {
                AppLogger.warning("Failed to queue file for reboot deletion: " + file.getAbsolutePath());
                return DeleteOutcome.FAILED;
            }
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
        return deleteWithOutcome(file, preferRecycle, null);
    }

    public static DeleteOutcome deleteWithOutcome(File file, boolean preferRecycle, AtomicBoolean cancelled) {
        if (file == null) return DeleteOutcome.FAILED;
        if (cancelled(cancelled)) return DeleteOutcome.FAILED;
        Path p;
        try {
            p = file.toPath();
        } catch (Exception e) {
            return DeleteOutcome.FAILED;
        }
        Boolean link = isLinkOrReparse(p);
        if (link == null || Boolean.TRUE.equals(link)) {
            // SHFileOperation follows junctions. Never recycle a link/unknown node.
            return deleteNodeOnly(file, cancelled);
        }
        if (!file.exists()) return DeleteOutcome.DELETED;
        if (preferRecycle) {
            if (file.isFile()) {
                try {
                    if (moveToRecycleBin(file)) {
                        AppLogger.info("Moved leftover to Recycle Bin: " + file.getAbsolutePath());
                        return DeleteOutcome.RECYCLED;
                    }
                } catch (Throwable t) {
                    AppLogger.debug("Recycle Bin unavailable for " + file.getAbsolutePath()
                            + " (" + t.getMessage() + ") — falling back to permanent delete.");
                }
            } else if (file.isDirectory()) {
                return recycleDirectorySafely(file, cancelled);
            }
        }
        return deleteOrQueueWithOutcome(file, cancelled);
    }

    /**
     * Recycle children one-by-one without SHFileOperation on the tree.
     * Shell recycle of a directory follows inner junctions and can wipe the target.
     */
    private static DeleteOutcome recycleDirectorySafely(File dir, AtomicBoolean cancelled) {
        if (cancelled(cancelled)) return DeleteOutcome.FAILED;
        File[] children = dir.listFiles();
        if (children == null) {
            AppLogger.warning("Refused to recycle/queue unlistable directory: " + dir.getAbsolutePath());
            return DeleteOutcome.FAILED;
        }
        boolean allGone = true;
        boolean anyRecycled = false;
        for (File child : children) {
            if (cancelled(cancelled)) return DeleteOutcome.FAILED;
            DeleteOutcome o = deleteWithOutcome(child, true, cancelled);
            if (o == DeleteOutcome.RECYCLED) anyRecycled = true;
            if (o != DeleteOutcome.DELETED && o != DeleteOutcome.RECYCLED) allGone = false;
        }
        if (cancelled(cancelled)) return DeleteOutcome.FAILED;
        if (!allGone) {
            boolean scheduled = queueForReboot(dir.getAbsolutePath());
            return scheduled ? DeleteOutcome.QUEUED_FOR_REBOOT : DeleteOutcome.FAILED;
        }
        try {
            if (moveToRecycleBin(dir)) {
                AppLogger.info("Moved leftover to Recycle Bin: " + dir.getAbsolutePath());
                return DeleteOutcome.RECYCLED;
            }
        } catch (Throwable ignored) {}
        DeleteOutcome empty = deleteOrQueueWithOutcome(dir, cancelled);
        if (empty == DeleteOutcome.DELETED && anyRecycled) return DeleteOutcome.RECYCLED;
        return empty;
    }

    /** Deletes this path only (link or file). Never lists/recurses children. */
    private static DeleteOutcome deleteNodeOnly(File file, AtomicBoolean cancelled) {
        if (cancelled(cancelled)) return DeleteOutcome.FAILED;
        Path p;
        try {
            p = file.toPath();
            Files.deleteIfExists(p);
            AppLogger.info("Deleted link/node (not followed): " + file.getAbsolutePath());
            return DeleteOutcome.DELETED;
        } catch (Exception e) {
            if (cancelled(cancelled)) return DeleteOutcome.FAILED;
            // Never MoveFileEx a reparse point — reboot-queue can apply to the TARGET.
            try {
                if (file != null && !Boolean.FALSE.equals(isLinkOrReparse(file.toPath()))) {
                    AppLogger.warning("Refused reboot-queue for reparse/unknown node: "
                            + file.getAbsolutePath());
                    return DeleteOutcome.FAILED;
                }
            } catch (Exception ignored) {
                return DeleteOutcome.FAILED;
            }
            boolean scheduled = queueForReboot(file.getAbsolutePath());
            if (scheduled) {
                AppLogger.info("Queued link/node for reboot deletion: " + file.getAbsolutePath());
                return DeleteOutcome.QUEUED_FOR_REBOOT;
            }
            AppLogger.warning("Failed to delete/queue link/node: " + file.getAbsolutePath());
            return DeleteOutcome.FAILED;
        }
    }

    private static boolean cancelled(AtomicBoolean cancelled) {
        return cancelled != null && cancelled.get();
    }

    /**
     * Moves a file or directory to the Recycle Bin using Shell32 SHFileOperation
     * with FOF_ALLOWUNDO. No confirmation UI is shown. Returns true only when the
     * path no longer exists afterwards.
     */
    public static boolean moveToRecycleBin(File file) {
        if (file == null || !file.exists()) return true;
        try {
            Boolean link = isLinkOrReparse(file.toPath());
            if (link == null || Boolean.TRUE.equals(link)) return false;
        } catch (Exception e) {
            return false;
        }
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
     * {@code true} = symlink/junction, {@code false} = regular, {@code null} = unknown
     * (callers must not recurse). {@code dos:reparsePoint} is not available on all JDKs.
     */
    static Boolean isLinkOrReparse(Path p) {
        if (p == null) return null;
        try {
            if (Files.isSymbolicLink(p)) return true;
        } catch (Exception e) {
            return null;
        }
        try {
            java.nio.file.attribute.DosFileAttributes attrs =
                    Files.readAttributes(p, java.nio.file.attribute.DosFileAttributes.class,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink()) return true;
        } catch (Exception ignored) {
        }
        try {
            Object v = Files.getAttribute(p, "dos:reparsePoint", java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return Boolean.TRUE.equals(v);
        } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
            // JDK does not expose dos:reparsePoint — use Win32 file attributes.
        } catch (java.nio.file.NoSuchFileException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
        return win32ReparsePoint(p);
    }

    private static Boolean win32ReparsePoint(Path p) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (!os.contains("win")) return false;
            int attrs = Kernel32.INSTANCE.GetFileAttributes(p.toAbsolutePath().toString());
            if (attrs == com.sun.jna.platform.win32.WinNT.INVALID_FILE_ATTRIBUTES) return null;
            return (attrs & com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_REPARSE_POINT) != 0;
        } catch (Exception e) {
            return null;
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
            if (absolutePath == null || absolutePath.isBlank()) return false;
            Boolean link = isLinkOrReparse(Path.of(absolutePath));
            if (!Boolean.FALSE.equals(link)) {
                AppLogger.warning("Refused reboot-queue for reparse/unknown path: " + absolutePath);
                return false;
            }
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
