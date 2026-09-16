package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class RecycleBinCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.EMPTY_RECYCLE_BIN; }

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
        List<Path> userBins = currentUserRecycleBinDirs();
        if (userBins.isEmpty()) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Unavailable (could not resolve current-user Recycle Bin)");
            return;
        }
        long[] stats = scanRecycleBinSizeAndCount(userBins, token);
        long size = stats[0];
        int count = (int) stats[1];
        if (token != null && token.isCancelled()) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Canceled");
            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
            row.setErrorMessage("Scan canceled by user");
            return;
        }
        row.setTotalBytes(size);
        row.setItemCount(count);
        row.setSizeOrCountText(size > 0 ? CleanerUtils.formatBytes(size) + " (" + count + " files)" : "Empty");
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        List<Path> userBins = currentUserRecycleBinDirs();
        if (userBins.isEmpty()) {
            AppLogger.warning("Skipping Recycle Bin: current-user SID folder unresolved "
                    + "(Clear-RecycleBin when elevated can empty other users' bins)");
            return 0L;
        }
        long size = scanRecycleBinSizeAndCount(userBins, token)[0];
        if (token != null && token.isCancelled()) return 0L;
        java.util.concurrent.atomic.AtomicLong fallbackCleaned = new java.util.concurrent.atomic.AtomicLong(0);
        java.util.List<Path> failedDeletes = new java.util.ArrayList<>();
        try {
            for (Path recycleBin : userBins) {
                if (token != null && token.isCancelled()) break;
                if (!CleanerUtils.isRealDirectory(recycleBin)) continue;
                java.util.List<Path> filesToDelete = new java.util.ArrayList<>();
                java.util.List<Path> dirsToDelete = new java.util.ArrayList<>();
                try {
                    final com.sbtools.util.CancellationToken walkToken = token;
                    Files.walkFileTree(recycleBin, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (walkToken != null && walkToken.isCancelled()) return FileVisitResult.TERMINATE;
                            if (CleanerUtils.shouldSkipWalkDir(dir, recycleBin, attrs)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (!dir.equals(recycleBin)) {
                                dirsToDelete.add(dir);
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (walkToken != null && walkToken.isCancelled()) return FileVisitResult.TERMINATE;
                            filesToDelete.add(file);
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (Exception ignored) {}

                for (Path f : filesToDelete) {
                    if (token != null && token.isCancelled()) break;
                    try {
                        // $I control files are metadata, excluded from scan —
                        // exclude here too so freed never exceeds scanned.
                        boolean isControl = f.getFileName() != null
                                && f.getFileName().toString().startsWith("$I");
                        long sz = !isControl && Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)
                                ? Files.size(f) : 0L;
                        CleanerUtils.deletePermanently(f, token);
                        if (!Files.exists(f, LinkOption.NOFOLLOW_LINKS)) {
                            if (sz > 0) fallbackCleaned.addAndGet(sz);
                        } else {
                            failedDeletes.add(f);
                        }
                    } catch (Exception ignored) {
                        failedDeletes.add(f);
                    }
                }
                dirsToDelete.sort(Comparator.comparingInt(Path::getNameCount).reversed());
                for (Path d : dirsToDelete) {
                    if (token != null && token.isCancelled()) break;
                    try { Files.deleteIfExists(d); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ex2) {
            AppLogger.warning("Failed to empty Recycle Bin: " + ex2.getMessage());
        }
        long fallback = fallbackCleaned.get();
        if (!failedDeletes.isEmpty()) {
            AppLogger.warning("Recycle Bin fallback incomplete, failed to delete " + failedDeletes.size() + " entries");
            return fallback;
        }
        if (fallback > 0) return fallback;
        // Verify PowerShell fallback actually emptied the bin before reporting pre-scan size
        long remaining = scanRecycleBinSizeAndCount(userBins, token)[0];
        if (remaining == 0 && size > 0) return size;
        return fallback;
    }

    private long[] scanRecycleBinSizeAndCount(List<Path> userBins, com.sbtools.util.CancellationToken token) {
        AtomicLong totalSize = new AtomicLong(0);
        AtomicLong totalCount = new AtomicLong(0);
        try {
            for (Path recycleBin : userBins) {
                if (token != null && token.isCancelled()) break;
                if (!CleanerUtils.isRealDirectory(recycleBin)) continue;
                try {
                    Files.walkFileTree(recycleBin, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                            if (CleanerUtils.shouldSkipWalkDir(dir, recycleBin, attrs)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (token != null && token.isCancelled()) return FileVisitResult.TERMINATE;
                            if (attrs.isRegularFile() && !file.getFileName().toString().startsWith("$I")) {
                                totalSize.addAndGet(attrs.size());
                                totalCount.incrementAndGet();
                            }
                            return FileVisitResult.CONTINUE;
                        }
                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return new long[]{totalSize.get(), totalCount.get()};
    }

    public static boolean isPlausibleSid(String sid) {
        return sid != null && sid.length() >= 6 && sid.regionMatches(true, 0, "S-1-", 0, 4);
    }

    static List<Path> currentUserRecycleBinDirs() {
        String sid = currentUserSid();
        if (!isPlausibleSid(sid)) return List.of();
        List<Path> dirs = new ArrayList<>();
        java.io.File[] roots = java.io.File.listRoots();
        if (roots == null) return dirs;
        for (java.io.File root : roots) {
            Path userBin = root.toPath().resolve("$Recycle.Bin").resolve(sid);
            if (CleanerUtils.isRealDirectory(userBin)) dirs.add(userBin);
        }
        return dirs;
    }

    static String currentUserSid() {
        try {
            WinNT.HANDLEByReference token = new WinNT.HANDLEByReference();
            if (Advapi32.INSTANCE.OpenProcessToken(
                    Kernel32.INSTANCE.GetCurrentProcess(), WinNT.TOKEN_QUERY, token)) {
                try {
                    Advapi32Util.Account acct = Advapi32Util.getTokenAccount(token.getValue());
                    if (acct != null && isPlausibleSid(acct.sidString)) return acct.sidString;
                } finally {
                    Kernel32.INSTANCE.CloseHandle(token.getValue());
                }
            }
        } catch (Throwable ignored) {}
        try {
            String domain = System.getenv("USERDOMAIN");
            String user = System.getenv("USERNAME");
            String name = (domain != null && !domain.isBlank() && user != null && !user.isBlank())
                    ? domain + "\\" + user : Advapi32Util.getUserName();
            Advapi32Util.Account acct = Advapi32Util.getAccountByName(name);
            if (acct != null && isPlausibleSid(acct.sidString)) return acct.sidString;
        } catch (Throwable ignored) {}
        return null;
    }
}
