package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RegistryCleaner implements CleanerExtension {

    private static final Set<String> SAFE_DELETE_HKCU_RUN_PATHS = Set.of(
            "Software\\Microsoft\\Windows\\CurrentVersion\\Run",
            "Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce"
    );

    private static final Set<String> SAFE_DELETE_HKLM_RUN_PATHS = Set.of(
            "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    );

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.REGISTRY; }

    @Override
    public boolean requiresAdmin() { return true; }

    @Override
    public void scan(CleanupRow row) {
        int count = 0;
        // Registry cleaners are Windows-only; JNA throws UnsatisfiedLinkError
        // (an Error, not Exception) on other OSes — never attempt the scan.
        if (!com.sbtools.util.AppPaths.isWindows()) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Not supported on this OS");
            return;
        }
        for (String keyPath : SAFE_DELETE_HKCU_RUN_PATHS) {
            count += countInvalidRegistryValues(WinReg.HKEY_CURRENT_USER, keyPath);
        }
        for (String keyPath : SAFE_DELETE_HKLM_RUN_PATHS) {
            count += countInvalidRegistryValues(WinReg.HKEY_LOCAL_MACHINE, keyPath);
        }
        count += countOrphanedSharedDLLs();
        row.setTotalBytes(0);
        row.setItemCount(count);
        row.setSizeOrCountText(count + " invalid entr" + (count == 1 ? "y" : "ies"));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        if (!com.sbtools.util.AppPaths.isWindows()) return 0L;
        long deleted = 0;
        for (String keyPath : SAFE_DELETE_HKCU_RUN_PATHS) {
            if (token != null && token.isCancelled()) break;
            deleted += deleteInvalidRegistryValues(backupRootOrNull, WinReg.HKEY_CURRENT_USER, keyPath, token);
        }
        for (String keyPath : SAFE_DELETE_HKLM_RUN_PATHS) {
            if (token != null && token.isCancelled()) break;
            deleted += deleteInvalidRegistryValues(backupRootOrNull, WinReg.HKEY_LOCAL_MACHINE, keyPath, token);
        }
        if (token != null && token.isCancelled()) return deleted;
        deleted += cleanOrphanedSharedDLLs(backupRootOrNull, token);
        return deleted;
    }

    /**
     * Fail-safe missing check: returns true only when the target drive is ready
     * and the file is definitely absent. Offline/removable/network drives,
     * unready roots, relative paths, unexpanded variables, and ACL/indeterminate
     * paths return false (entry is kept) so transiently unavailable or
     * access-denied targets are never flagged as orphans.
     * <p>
     * Uses {@link Files#notExists} rather than {@code !Files.exists}: when
     * existence cannot be determined (e.g. ACL-denied under WindowsApps), both
     * {@code exists} and {@code notExists} return false — so {@code !exists}
     * would wrongly treat the path as missing.
     */
    static boolean isConfidentlyMissing(String cleanPath) {
        if (cleanPath == null || cleanPath.isBlank() || cleanPath.contains("%")) return false;
        Path p;
        try {
            p = Paths.get(cleanPath);
        } catch (Exception e) {
            return false;
        }
        try {
            if (!p.isAbsolute()) return false;
            Path root = p.getRoot();
            if (root == null) return false;
            java.io.File rootFile = root.toFile();
            try {
                if (!rootFile.exists()) return false;
                // Unready/offline drive (no media, disconnected network): keep entry.
                if (rootFile.getTotalSpace() <= 0) return false;
            } catch (Exception e) {
                return false;
            }
            try {
                // Throws when the volume is unavailable — treat as "cannot tell".
                java.nio.file.FileStore store = Files.getFileStore(root);
                if (store == null) return false;
            } catch (Exception e) {
                return false;
            }
            // notExists == true only when known absent; false if present OR indeterminate.
            return Files.notExists(p);
        } catch (Exception e) {
            return false;
        }
    }

    private int countInvalidRegistryValues(WinReg.HKEY hive, String keyPath) {
        int count = 0;
        try {
            if (Advapi32Util.registryKeyExists(hive, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(hive, keyPath);
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    Object raw = entry.getValue();
                    if (raw == null) continue;
                    String value = raw.toString();
                    if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\""))
                        value = value.substring(1, value.length() - 1);
                    String cleanPath = CleanerUtils.extractPathFromRegistryValue(value);
                    if (isConfidentlyMissing(cleanPath)) count++;
                }
            }
        } catch (Throwable ignored) {}
        return count;
    }

    private long deleteInvalidRegistryValues(Path backupRootOrNull, WinReg.HKEY hive, String keyPath) {
        return deleteInvalidRegistryValues(backupRootOrNull, hive, keyPath, com.sbtools.util.CancellationToken.NONE);
    }

    private long deleteInvalidRegistryValues(Path backupRootOrNull, WinReg.HKEY hive, String keyPath, com.sbtools.util.CancellationToken token) {
        long count = 0;
        try {
            if (Advapi32Util.registryKeyExists(hive, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(hive, keyPath);
                List<String> toDelete = new ArrayList<>();
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    if (token != null && token.isCancelled()) break;
                    Object raw = entry.getValue();
                    if (raw == null) continue;
                    String value = raw.toString();
                    if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\""))
                        value = value.substring(1, value.length() - 1);
                    String cleanPath = CleanerUtils.extractPathFromRegistryValue(value);
                    if (isConfidentlyMissing(cleanPath)) toDelete.add(entry.getKey());
                }
                if (!toDelete.isEmpty() && (token == null || !token.isCancelled())) {
                    if (backupRootOrNull == null) {
                        String hiveName = hive == WinReg.HKEY_LOCAL_MACHINE ? "HKLM" : "HKCU";
                        com.sbtools.util.AppLogger.warning(
                                "Registry backup required for " + hiveName + "\\" + keyPath
                                        + " — skipping delete for safety");
                        return 0;
                    }
                    String hiveName = hive == WinReg.HKEY_LOCAL_MACHINE ? "HKLM" : "HKCU";
                    Path regBackup = backupRootOrNull.resolve("registry-" + hiveName + "-" + keyPath.replace("\\", "_") + ".reg");
                    boolean backedUp = exportRegKey(
                            (hive == WinReg.HKEY_LOCAL_MACHINE ? "HKLM" : "HKCU") + "\\" + keyPath, regBackup);
                    if (!backedUp) {
                        com.sbtools.util.AppLogger.warning(
                                "Registry backup failed for " + hiveName + "\\" + keyPath
                                        + " — skipping delete for safety");
                        return 0;
                    }
                    for (String valName : toDelete) {
                        if (token != null && token.isCancelled()) break;
                        try { Advapi32Util.registryDeleteValue(hive, keyPath, valName); count++; } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return count;
    }

    private boolean exportRegKey(String fullKey, Path regBackup) {
        return CleanerUtils.exportRegistryKey(fullKey, regBackup);
    }

    private int countOrphanedSharedDLLs() {
        int count = 0;
        String keyPath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\SharedDLLs";
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(WinReg.HKEY_LOCAL_MACHINE, keyPath);
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    String rawPath = entry.getKey();
                    try {
                        Object valObj = entry.getValue();
                        int refCount = 0;
                        if (valObj instanceof Integer) {
                            refCount = (Integer) valObj;
                        } else if (valObj != null) {
                            try { refCount = Integer.parseInt(valObj.toString()); } catch (Exception ignored) {}
                        }
                        String expanded = CleanerUtils.expandEnvironmentVariables(rawPath);
                        if (refCount <= 1 && isConfidentlyMissing(expanded)) count++;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return count;
    }

    private long cleanOrphanedSharedDLLs(Path backupRootOrNull) {
        return cleanOrphanedSharedDLLs(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    private long cleanOrphanedSharedDLLs(Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        long count = 0;
        String keyPath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\SharedDLLs";
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(WinReg.HKEY_LOCAL_MACHINE, keyPath);
                List<String> toDelete = new ArrayList<>();
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    if (token != null && token.isCancelled()) break;
                    String rawPath = entry.getKey();
                    try {
                        Object valObj = entry.getValue();
                        int refCount = 0;
                        if (valObj instanceof Integer) {
                            refCount = (Integer) valObj;
                        } else if (valObj != null) {
                            try { refCount = Integer.parseInt(valObj.toString()); } catch (Exception ignored) {}
                        }
                        String expanded = CleanerUtils.expandEnvironmentVariables(rawPath);
                        if (refCount <= 1 && isConfidentlyMissing(expanded)) toDelete.add(rawPath);
                    } catch (Exception ignored) {}
                }
                if (!toDelete.isEmpty() && (token == null || !token.isCancelled())) {
                    if (backupRootOrNull == null) {
                        com.sbtools.util.AppLogger.warning(
                                "Registry backup required for HKLM\\" + keyPath + " — skipping delete for safety");
                        return 0;
                    }
                    if (!backupRegKey(backupRootOrNull, "shareddlls", "HKLM", keyPath)) {
                        com.sbtools.util.AppLogger.warning(
                                "Registry backup failed for HKLM\\" + keyPath + " — skipping delete for safety");
                        return 0;
                    }
                    for (String valName : toDelete) {
                        if (token != null && token.isCancelled()) break;
                        try {
                            Advapi32Util.registryDeleteValue(WinReg.HKEY_LOCAL_MACHINE, keyPath, valName);
                            count++;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return count;
    }

    private boolean backupRegKey(Path backupRootOrNull, String description, String hiveName, String keyPath) {
        if (backupRootOrNull == null) return false;
        Path regBackup = backupRootOrNull.resolve("registry-" + description + ".reg");
        return CleanerUtils.exportRegistryKey(hiveName + "\\" + keyPath, regBackup);
    }
}
