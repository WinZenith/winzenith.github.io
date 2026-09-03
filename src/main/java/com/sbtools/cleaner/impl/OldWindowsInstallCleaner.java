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
    public void scan(CleanupRow row) {
        Path windowsOld = getValidWindowsOldPath();
        if (windowsOld == null) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Not found");
            return;
        }
        scanWithWalkFileTree(row, windowsOld);
    }

    private void scanWithWalkFileTree(CleanupRow row, Path root) {
        AtomicLong totalBytes = new AtomicLong(0);
        AtomicLong itemCount = new AtomicLong(0);
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (isSymlinkOrJunction(dir, attrs)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    totalBytes.addAndGet(attrs.size());
                    itemCount.incrementAndGet();
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ignored) {}
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
        Path windowsOld = getValidWindowsOldPath();
        if (windowsOld == null) return 0;

        long size = getDirectorySize(windowsOld, token);
        if (token != null && token.isCancelled()) return 0L;
        if (removeWithRd(windowsOld, token)) return size;

        if (token != null && token.isCancelled()) return 0L;
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
        if (drive == null || drive.isEmpty()) drive = "C:";
        return Paths.get(drive, "Windows.old");
    }

    private Path getValidWindowsOldPath() {
        Path windowsOld = getWindowsOldPath();
        if (windowsOld == null || !Files.isDirectory(windowsOld)) return null;

        try {
            if (Files.isSymbolicLink(windowsOld)) {
                AppLogger.warning("Windows.old is a symbolic link, skipping for safety");
                return null;
            }
        } catch (Exception ignored) {}

        Path windowsSubdir = windowsOld.resolve("Windows");
        if (!Files.isDirectory(windowsSubdir)) {
            AppLogger.warning("Windows.old does not contain a Windows subdirectory, skipping");
            return null;
        }

        return windowsOld;
    }

    private boolean removeWithRd(Path target, com.sbtools.util.CancellationToken token) {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "rd /s /q \"" + target + "\"");
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
        if (attrs.isSymbolicLink()) return true;
        try {
            return Files.isSymbolicLink(path);
        } catch (Exception e) {
            return false;
        }
    }
}
