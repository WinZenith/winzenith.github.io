package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicLong;

public class OldWindowsInstallCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() {
        return CleanupCategory.OLD_WINDOWS_INSTALL;
    }

    @Override
    public boolean requiresAdmin() { return true; }

    @Override
    public long getCleanTimeoutSeconds() { return 600; }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Canceled");
            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
            row.setErrorMessage("Scan canceled by user");
            return;
        }
        if (com.sbtools.util.WindowsServicingSafety.isServicingPending()) {
            String reasons = String.join("; ", com.sbtools.util.WindowsServicingSafety.getPendingReasons());
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Skipped (pending system restart: " + reasons + ")");
            return;
        }
        Path windowsOld = getValidWindowsOldPath();
        if (windowsOld == null) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Not found");
            return;
        }
        scanWithWalkFileTree(row, windowsOld, token);
    }

    private void scanWithWalkFileTree(CleanupRow row, Path root, com.sbtools.util.CancellationToken token) {
        AtomicLong totalBytes = new AtomicLong(0);
        AtomicLong itemCount = new AtomicLong(0);
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (isSymlinkOrJunction(dir, attrs)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    totalBytes.addAndGet(attrs.size());
                    itemCount.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {}
        if (token != null && token.isCancelled()) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Canceled");
            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
            row.setErrorMessage("Scan canceled by user");
            return;
        }
        row.setTotalBytes(totalBytes.get());
        row.setItemCount((int) itemCount.get());
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalBytes.get()) + " (" + itemCount.get() + " files)");
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        if (com.sbtools.util.WindowsServicingSafety.isServicingPending()) {
            AppLogger.info("Skipping Windows.old removal: pending system restart ("
                    + String.join("; ", com.sbtools.util.WindowsServicingSafety.getPendingReasons()) + ")");
            return 0;
        }
        Path windowsOld = getValidWindowsOldPath();
        if (windowsOld == null) return 0;

        // Re-validate immediately before the destructive step (TOCTOU).
        Path valid = getValidWindowsOldPath();
        if (valid == null || !valid.toAbsolutePath().normalize().equals(
                windowsOld.toAbsolutePath().normalize())) {
            AppLogger.warning("Windows.old validation changed, aborting removal for safety");
            return 0;
        }
        // Never rd /s or takeown /R: Windows.old contains profile junctions
        // (Documents, OneDrive, Application Data) that can point at the live
        // user profile. Recurse with NOFOLLOW and unlink reparse nodes only.
        long cleaned = deleteTreeSkippingReparse(valid, token);
        if (Files.exists(valid, LinkOption.NOFOLLOW_LINKS)) {
            AppLogger.warning("Windows.old not fully removed (ACL/locked files left in place)");
        }
        return cleaned;
    }

    private Path getWindowsOldPath() {
        String drive = CleanerUtils.safeEnv("SYSTEMDRIVE");
        // Sanitize SYSTEMDRIVE: must be a bare drive designator (e.g. "C:" or "C:\").
        // Anything else (path traversal, UNC, empty) falls back to C: so the
        // resolved target is always "<drive>:\Windows.old" and nothing broader.
        if (drive == null || !(drive.matches("(?i)^[a-z]:\\\\?$"))) drive = "C:";
        return Paths.get(drive.endsWith("\\") ? drive : drive + "\\", "Windows.old");
    }

    private static boolean isReparsePoint(Path p) {
        return CleanerUtils.isReparseLike(p, null);
    }

    private Path getValidWindowsOldPath() {
        Path windowsOld = getWindowsOldPath();
        if (windowsOld == null || !CleanerUtils.isRealDirectory(windowsOld)) return null;

        try {
            if (Files.isSymbolicLink(windowsOld) || isReparsePoint(windowsOld)) {
                AppLogger.warning("Windows.old is a link/junction, skipping for safety");
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }

        // Constrain to exactly "<drive>:\Windows.old" so a hijacked SYSTEMDRIVE
        // can never redirect deletion elsewhere.
        try {
            Path abs = windowsOld.toAbsolutePath().normalize();
            if (abs.getFileName() == null || !"windows.old".equalsIgnoreCase(abs.getFileName().toString())) return null;
            Path parent = abs.getParent();
            if (parent == null || !parent.equals(abs.getRoot())) return null;
        } catch (Exception ignored) {
            return null;
        }

        Path windowsSubdir = windowsOld.resolve("Windows");
        if (!CleanerUtils.isRealDirectory(windowsSubdir)) {
            AppLogger.warning("Windows.old does not contain a real Windows subdirectory, skipping");
            return null;
        }
        try {
            if (Files.isSymbolicLink(windowsSubdir) || isReparsePoint(windowsSubdir)) {
                AppLogger.warning("Windows.old\\Windows is a link, skipping for safety");
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }

        return windowsOld;
    }

    /**
     * Delete a tree without following junctions/symlinks. Reparse nodes are
     * unlinked themselves so live junction targets (e.g. current Documents)
     * are never entered or owned.
     */
    static long deleteTreeSkippingReparse(Path root, com.sbtools.util.CancellationToken token) {
        if (root == null) return 0L;
        if (!CleanerUtils.isRealDirectory(root)) return 0L;
        AtomicLong cleaned = new AtomicLong(0);
        try {
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                    new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (!dir.equals(root) && isSymlinkOrJunction(dir, attrs)) {
                        try { Files.deleteIfExists(dir); } catch (Exception ignored) {}
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(root) && CleanerUtils.shouldSkipWalkDir(dir, root, attrs)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    try {
                        if (attrs.isSymbolicLink() || attrs.isOther() || isReparsePoint(file)) {
                            Files.deleteIfExists(file);
                            return FileVisitResult.CONTINUE;
                        }
                        long size = attrs.isRegularFile() ? attrs.size() : 0L;
                        Files.deleteIfExists(file);
                        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) cleaned.addAndGet(size);
                    } catch (Exception ignored) {}
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                    if (!dir.equals(root)) {
                        try { Files.deleteIfExists(dir); } catch (Exception ignored) {}
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            try { Files.deleteIfExists(root); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        return cleaned.get();
    }

    static boolean isSymlinkOrJunction(Path path, BasicFileAttributes attrs) {
        if (attrs == null) return true;
        if (attrs.isSymbolicLink() || attrs.isOther()) return true;
        try {
            if (Files.isSymbolicLink(path)) return true;
        } catch (Exception e) {
            return true;
        }
        return isReparsePoint(path);
    }
}
