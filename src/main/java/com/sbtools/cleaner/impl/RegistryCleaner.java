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
    public void scan(CleanupRow row) {
        int count = 0;
        for (String keyPath : SAFE_DELETE_HKCU_RUN_PATHS) {
            count += countInvalidRegistryValues(WinReg.HKEY_CURRENT_USER, keyPath);
        }
        for (String keyPath : SAFE_DELETE_HKLM_RUN_PATHS) {
            count += countInvalidRegistryValues(WinReg.HKEY_LOCAL_MACHINE, keyPath);
        }
        count += countOrphanedSharedDLLs();
        row.setItemCount(count);
        row.setSizeOrCountText(count + " invalid entr" + (count == 1 ? "y" : "ies"));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        for (String keyPath : SAFE_DELETE_HKCU_RUN_PATHS) {
            deleteInvalidRegistryValues(backupRootOrNull, WinReg.HKEY_CURRENT_USER, keyPath);
        }
        for (String keyPath : SAFE_DELETE_HKLM_RUN_PATHS) {
            deleteInvalidRegistryValues(backupRootOrNull, WinReg.HKEY_LOCAL_MACHINE, keyPath);
        }
        cleanOrphanedSharedDLLs(backupRootOrNull);
        return 0;
    }

    private int countInvalidRegistryValues(WinReg.HKEY hive, String keyPath) {
        int count = 0;
        try {
            if (Advapi32Util.registryKeyExists(hive, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(hive, keyPath);
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    String value = entry.getValue().toString();
                    if (value.startsWith("\"") && value.endsWith("\""))
                        value = value.substring(1, value.length() - 1);
                    String cleanPath = CleanerUtils.extractPathFromRegistryValue(value);
                    if (cleanPath != null && !Files.exists(Paths.get(cleanPath))) count++;
                }
            }
        } catch (Exception ignored) {}
        return count;
    }

    private long deleteInvalidRegistryValues(Path backupRootOrNull, WinReg.HKEY hive, String keyPath) {
        long count = 0;
        try {
            if (Advapi32Util.registryKeyExists(hive, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(hive, keyPath);
                List<String> toDelete = new ArrayList<>();
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    String value = entry.getValue().toString();
                    if (value.startsWith("\"") && value.endsWith("\""))
                        value = value.substring(1, value.length() - 1);
                    String cleanPath = CleanerUtils.extractPathFromRegistryValue(value);
                    if (cleanPath != null && !Files.exists(Paths.get(cleanPath))) toDelete.add(entry.getKey());
                }
                if (!toDelete.isEmpty()) {
                    if (backupRootOrNull != null) {
                        String hiveName = hive == WinReg.HKEY_LOCAL_MACHINE ? "HKLM" : "HKCU";
                        Path regBackup = backupRootOrNull.resolve("registry-" + hiveName + "-" + keyPath.replace("\\", "_") + ".reg");
                        try { java.nio.file.Files.createDirectories(regBackup.getParent()); } catch (Exception ignored) {}
                        try {
                            ProcessBuilder exportPb = new ProcessBuilder("reg", "export",
                                    (hive == WinReg.HKEY_LOCAL_MACHINE ? "HKLM" : "HKCU") + "\\" + keyPath,
                                    regBackup.toString(), "/y");
                            exportPb.redirectErrorStream(true);
                            Process exportProcess = com.sbtools.util.ProcessManager.start(exportPb);
                            boolean ok = exportProcess.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                            if (!ok) exportProcess.destroyForcibly();
                        } catch (Exception ignored) {}
                    }
                    for (String valName : toDelete) {
                        try { Advapi32Util.registryDeleteValue(hive, keyPath, valName); count++; } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return count;
    }

    private int countOrphanedSharedDLLs() {
        int count = 0;
        String keyPath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\SharedDLLs";
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(WinReg.HKEY_LOCAL_MACHINE, keyPath);
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    String filePath = entry.getKey();
                    try {
                        Object valObj = entry.getValue();
                        int refCount = 0;
                        if (valObj instanceof Integer) {
                            refCount = (Integer) valObj;
                        } else {
                            try { refCount = Integer.parseInt(valObj.toString()); } catch (Exception ignored) {}
                        }
                        if (refCount <= 1 && !Files.exists(Paths.get(filePath))) count++;
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return count;
    }

    private long cleanOrphanedSharedDLLs(Path backupRootOrNull) {
        long count = 0;
        String keyPath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\SharedDLLs";
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(WinReg.HKEY_LOCAL_MACHINE, keyPath);
                List<String> toDelete = new ArrayList<>();
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    String filePath = entry.getKey();
                    try {
                        Object valObj = entry.getValue();
                        int refCount = 0;
                        if (valObj instanceof Integer) {
                            refCount = (Integer) valObj;
                        } else {
                            try { refCount = Integer.parseInt(valObj.toString()); } catch (Exception ignored) {}
                        }
                        if (refCount <= 1 && !Files.exists(Paths.get(filePath))) toDelete.add(filePath);
                    } catch (Exception ignored) {}
                }
                if (!toDelete.isEmpty()) backupRegKey(backupRootOrNull, "shareddlls", "HKLM", keyPath);
                for (String valName : toDelete) {
                    try { Advapi32Util.registryDeleteValue(WinReg.HKEY_LOCAL_MACHINE, keyPath, valName); count++; } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return count;
    }

    private void backupRegKey(Path backupRootOrNull, String description, String hiveName, String keyPath) {
        if (backupRootOrNull == null) return;
        try {
            Path regBackup = backupRootOrNull.resolve("registry-" + description + ".reg");
            java.nio.file.Files.createDirectories(regBackup.getParent());
            ProcessBuilder pb = new ProcessBuilder("reg", "export", hiveName + "\\" + keyPath, regBackup.toString(), "/y");
            pb.redirectErrorStream(true);
            Process p = com.sbtools.util.ProcessManager.start(pb);
            boolean ok = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!ok) p.destroyForcibly();
        } catch (Exception ignored) {}
    }
}
