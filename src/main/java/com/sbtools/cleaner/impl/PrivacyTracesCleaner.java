package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

public class PrivacyTracesCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.PRIVACY_TRACES; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;

        // Registry access is Windows-only; JNA throws Error (not Exception)
        // on other OSes — skip registry traces there.
        boolean isWindows = com.sbtools.util.AppPaths.isWindows();
        Path recentDir = CleanerUtils.safeEnvPath("APPDATA", "Microsoft", "Windows", "Recent");
        if (recentDir != null && Files.isDirectory(recentDir) && CleanerUtils.isSafeToCleanDirectory(recentDir)) {
            try (Stream<Path> files = Files.list(recentDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    try {
                        if (Files.isRegularFile(f)) { totalSize += Files.size(f); itemCount++; }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        if (isWindows) {
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU")) {
                Map<String, Object> values = Advapi32Util.registryGetValues(WinReg.HKEY_CURRENT_USER,
                        "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU");
                for (String key : values.keySet()) {
                    if (!"MRUListEx".equals(key) && !"MRUList".equals(key)) itemCount++;
                }
            }
        } catch (Throwable ignored) {}
        }

        if (isWindows) {
        try {
            String recentDocsPath = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RecentDocs";
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, recentDocsPath)) {
                String[] subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_CURRENT_USER, recentDocsPath);
                for (String ext : subKeys) {
                    try {
                        Object value = Advapi32Util.registryGetValue(WinReg.HKEY_CURRENT_USER, recentDocsPath + "\\" + ext, null);
                        if (value != null) {
                            byte[] bytes = (value instanceof byte[]) ? (byte[]) value : value.toString().getBytes();
                            itemCount += Math.max(1, CleanerUtils.countDocumentsInRecentDocsBinary(bytes));
                        }
                    } catch (Exception ignored) { itemCount++; }
                }
            }
        } catch (Throwable ignored) {}
        }

        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(itemCount + " item" + (itemCount == 1 ? "" : "s") + " / " + CleanerUtils.formatBytes(totalSize));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long cleaned = 0;
        Path recentDir = CleanerUtils.safeEnvPath("APPDATA", "Microsoft", "Windows", "Recent");
        if (recentDir != null && Files.isDirectory(recentDir) && CleanerUtils.isSafeToCleanDirectory(recentDir)) {
            try (Stream<Path> files = Files.list(recentDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (token != null && token.isCancelled()) break;
                    try {
                        if (Files.isRegularFile(f)) {
                            long size = Files.size(f);
                            CleanerUtils.deletePermanently(f, token);
                            if (!Files.exists(f)) cleaned += size;
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        if (token != null && token.isCancelled()) return cleaned;

        if (com.sbtools.util.AppPaths.isWindows()) {
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU")) {
                Map<String, Object> values = Advapi32Util.registryGetValues(WinReg.HKEY_CURRENT_USER,
                        "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU");
                for (String key : values.keySet()) {
                    if (token != null && token.isCancelled()) break;
                    if (!"MRUListEx".equals(key) && !"MRUList".equals(key)) {
                        try { Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER,
                                "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU", key); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        }

        if (token != null && token.isCancelled()) return cleaned;

        if (com.sbtools.util.AppPaths.isWindows()) {
        try {
            String recentDocsPath = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RecentDocs";
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, recentDocsPath)) {
                String[] subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_CURRENT_USER, recentDocsPath);
                for (String subKey : subKeys) {
                    if (token != null && token.isCancelled()) break;
                    try {
                        Advapi32Util.registryDeleteKey(WinReg.HKEY_CURRENT_USER, recentDocsPath + "\\" + subKey);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        }

        return cleaned;
    }
}
