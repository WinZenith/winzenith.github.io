package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessManager;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
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

    private void scanWithWalkFileTree(CleanupRow row, Path root) {
        scanWithWalkFileTree(row, root, com.sbtools.util.CancellationToken.NONE);
    }

    private void scanWithWalkFileTree(CleanupRow row, Path root, com.sbtools.util.CancellationToken token) {
        AtomicLong totalBytes = new AtomicLong(0);
        AtomicLong itemCount = new AtomicLong(0);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
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

        long size = getDirectorySize(windowsOld, token);
        if (token != null && token.isCancelled()) return 0L;
        if (removeWithRd(windowsOld, token)) return size;

        if (token != null && token.isCancelled()) return 0L;
        // Re-validate before takeown/icacls: ownership changes must never run
        // against a path that stopped being the validated Windows.old.
        Path revalidated = getValidWindowsOldPath();
        if (revalidated == null || !revalidated.toAbsolutePath().normalize().equals(
                windowsOld.toAbsolutePath().normalize())) {
            AppLogger.warning("Windows.old validation changed, aborting takeown for safety");
            return 0;
        }
        windowsOld = revalidated;
        AppLogger.warning("rd /s /q failed for Windows.old, attempting takeown/icacls");
        try {
            ProcessBuilder takeown = new ProcessBuilder("takeown", "/F", windowsOld.toString(), "/R", "/D", "Y");
            takeown.redirectErrorStream(true);
            Process takeownP = ProcessManager.start(takeown);
            if (!waitCancellable(takeownP, 60, token)) takeownP.destroyForcibly();
            if (token != null && token.isCancelled()) return 0L;

            ProcessBuilder icacls = new ProcessBuilder("icacls", windowsOld.toString(), "/grant", "administrators:F", "/T");
            icacls.redirectErrorStream(true);
            Process icaclsP = ProcessManager.start(icacls);
            if (!waitCancellable(icaclsP, 60, token)) icaclsP.destroyForcibly();
        } catch (Exception e) {
            AppLogger.warning("Failed to take ownership of Windows.old: " + e.getMessage());
        }

        if (token != null && token.isCancelled()) return 0L;
        if (removeWithRd(windowsOld, token)) return size;
        AppLogger.warning("Failed to remove Windows.old");
        return 0;
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
        try {
            Object reparse = Files.getAttribute(p, "dos:isReparsePoint",
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return Boolean.TRUE.equals(reparse);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Path getValidWindowsOldPath() {
        Path windowsOld = getWindowsOldPath();
        if (windowsOld == null || !Files.isDirectory(windowsOld)) return null;

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
        if (!Files.isDirectory(windowsSubdir)) {
            AppLogger.warning("Windows.old does not contain a Windows subdirectory, skipping");
            return null;
        }
        try {
            // The sentinel must itself be real (not a link); otherwise rd would follow it.
            if (Files.isSymbolicLink(windowsSubdir) || isReparsePoint(windowsSubdir)) {
                AppLogger.warning("Windows.old\\Windows is a link, skipping for safety");
                return null;
            }
        } catch (Exception ignored) {
            return null;
        }

        return windowsOld;
    }

    private boolean removeWithRd(Path target, com.sbtools.util.CancellationToken token) {
        try {
            // Re-validate immediately before the destructive step (TOCTOU): the
            // target must still be the validated Windows.old and not a link.
            Path valid = getValidWindowsOldPath();
            if (valid == null || !valid.toAbsolutePath().normalize().equals(
                    target.toAbsolutePath().normalize())) {
                AppLogger.warning("Windows.old validation changed, aborting removal for safety");
                return false;
            }
            if (Files.isSymbolicLink(target) || isReparsePoint(target)) {
                AppLogger.warning("Windows.old became a link, aborting removal for safety");
                return false;
            }
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "rd", "/s", "/q", target.toString());
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = waitCancellable(p, 120, token);
            if (token != null && token.isCancelled()) { p.destroyForcibly(); return false; }
            if (finished && !Files.exists(target)) return true;
            if (!finished) p.destroyForcibly();
        } catch (Exception ignored) {}
        return false;
    }

    private boolean waitCancellable(Process p, long timeoutSeconds, com.sbtools.util.CancellationToken token) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (token != null && token.isCancelled()) return false;
            if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) return true;
        }
        return false;
    }

    private long getDirectorySize(Path root) {
        return getDirectorySize(root, com.sbtools.util.CancellationToken.NONE);
    }

    private long getDirectorySize(Path root, com.sbtools.util.CancellationToken token) {
        AtomicLong totalBytes = new AtomicLong(0);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
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
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {}
        return totalBytes.get();
    }

    private boolean isSymlinkOrJunction(Path path, BasicFileAttributes attrs) {
        if (attrs.isSymbolicLink() || attrs.isOther()) return true;
        try {
            if (Files.isSymbolicLink(path)) return true;
        } catch (Exception e) {
            return true;
        }
        return isReparsePoint(path);
    }
}
