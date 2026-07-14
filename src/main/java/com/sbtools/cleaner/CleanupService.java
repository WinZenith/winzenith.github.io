package com.sbtools.cleaner;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.FormatUtils;
import com.sbtools.util.ProcessManager;
import com.sbtools.util.CancelableCompletableFuture;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

public class CleanupService {

    private static final long PER_CATEGORY_SCAN_TIMEOUT_SECONDS = 30;

    public static final class CleanSummary {
        private final long totalBytes;
        private final int totalItems;
        private final Map<CleanupCategory, Long> perCategory;

        public CleanSummary(long totalBytes, int totalItems, Map<CleanupCategory, Long> perCategory) {
            this.totalBytes = totalBytes;
            this.totalItems = totalItems;
            this.perCategory = perCategory;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public int getTotalItems() {
            return totalItems;
        }

        public Map<CleanupCategory, Long> getPerCategory() {
            return perCategory;
        }
    }

    // Per-category cleaner abstraction and registry
    private interface Cleaner {
        void scan(CleanupRow row);

        long clean(Path backupRootOrNull) throws Exception;
    }

    private final java.util.Map<CleanupCategory, Cleaner> cleaners = new java.util.HashMap<>();

    public CleanupService() {
        initCleaners();
    }

    private void initCleaners() {
        // Map each category to its scan/clean implementation (using existing methods)
        cleaners.put(CleanupCategory.REGISTRY, new Cleaner() {
            public void scan(CleanupRow row) {
                scanRegistry(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanRegistry(backupRootOrNull);
            }
        });
        cleaners.put(CleanupCategory.REGISTRY_DEFRAG, new Cleaner() {
            public void scan(CleanupRow row) {
                scanRegistryDefrag(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanRegistryDefrag();
            }
        });
        cleaners.put(CleanupCategory.EMPTY_RECYCLE_BIN, new Cleaner() {
            public void scan(CleanupRow row) {
                scanRecycleBin(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanRecycleBin();
            }
        });
        cleaners.put(CleanupCategory.JUNK_FILES, new Cleaner() {
            public void scan(CleanupRow row) {
                scanJunkFiles(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanDirectoryPatternOlderThan(getJunkDirs(), java.time.Duration.ofDays(1));
            }
        });
        cleaners.put(CleanupCategory.PRIVACY_TRACES, new Cleaner() {
            public void scan(CleanupRow row) {
                scanPrivacyTraces(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanPrivacyTraces();
            }
        });
        cleaners.put(CleanupCategory.WEB_BROWSING_TRACES, new Cleaner() {
            public void scan(CleanupRow row) {
                scanBrowserTraces(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanBrowserTraces();
            }
        });
        cleaners.put(CleanupCategory.CACHE, new Cleaner() {
            public void scan(CleanupRow row) {
                scanCache(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanDirectoryPattern(getCacheDirs());
            }
        });
        cleaners.put(CleanupCategory.INSTALLER_FILES, new Cleaner() {
            public void scan(CleanupRow row) {
                scanInstallerFiles(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanInstallerFiles();
            }
        });
        cleaners.put(CleanupCategory.TEMPORARY_SYSTEM_FILES, new Cleaner() {
            public void scan(CleanupRow row) {
                scanTempSystemFiles(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanDirectoryPattern(getTempSystemDirs());
            }
        });
        cleaners.put(CleanupCategory.MEMORY_DUMPS, new Cleaner() {
            public void scan(CleanupRow row) {
                scanMemoryDumps(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanMemoryDumps();
            }
        });
        cleaners.put(CleanupCategory.WINDOWS_ERROR_REPORTING, new Cleaner() {
            public void scan(CleanupRow row) {
                scanWindowsErrorReporting(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanWindowsErrorReporting();
            }
        });
        cleaners.put(CleanupCategory.WINDOWS_UPDATE_CLEANUP, new Cleaner() {
            public void scan(CleanupRow row) {
                scanWindowsUpdateCleanup(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanWindowsUpdateCleanup();
            }
        });
        cleaners.put(CleanupCategory.THUMBNAIL_CACHE, new Cleaner() {
            public void scan(CleanupRow row) {
                scanThumbnailCache(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanThumbnailCache();
            }
        });
        cleaners.put(CleanupCategory.EMPTY_FOLDERS, new Cleaner() {
            public void scan(CleanupRow row) {
                scanEmptyFolders(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanEmptyFolders();
            }
        });
        cleaners.put(CleanupCategory.NOTIFICATION_HISTORY, new Cleaner() {
            public void scan(CleanupRow row) {
                scanNotificationHistory(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanNotificationHistory();
            }
        });
        cleaners.put(CleanupCategory.FONT_CACHE, new Cleaner() {
            public void scan(CleanupRow row) {
                scanFontCache(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanFontCache();
            }
        });
        cleaners.put(CleanupCategory.TASKBAR_JUMP_LISTS, new Cleaner() {
            public void scan(CleanupRow row) {
                scanTaskbarJumpLists(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanTaskbarJumpLists();
            }
        });
        cleaners.put(CleanupCategory.OFFICE_DOCUMENT_CACHE, new Cleaner() {
            public void scan(CleanupRow row) {
                scanOfficeDocumentCache(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanOfficeDocumentCache();
            }
        });
        cleaners.put(CleanupCategory.WINDOWS_DEFENDER_CACHE, new Cleaner() {
            public void scan(CleanupRow row) {
                scanWindowsDefenderCache(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanWindowsDefenderCache();
            }
        });
        cleaners.put(CleanupCategory.WINDOWS_LOG_FILES, new Cleaner() {
            public void scan(CleanupRow row) {
                scanWindowsLogFiles(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanWindowsLogFiles();
            }
        });
        cleaners.put(CleanupCategory.WINDOWS_STORE_CACHE, new Cleaner() {
            public void scan(CleanupRow row) {
                scanWindowsStoreCache(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanWindowsStoreCache();
            }
        });
        cleaners.put(CleanupCategory.OTHER_PROGRAMS_CACHE, new Cleaner() {
            public void scan(CleanupRow row) {
                scanOtherProgramsCache(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanOtherProgramsCache();
            }
        });
        cleaners.put(CleanupCategory.NVIDIA_SHADER_CACHE, new Cleaner() {
            public void scan(CleanupRow row) {
                scanShaderCache(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanShaderCache();
            }
        });
        cleaners.put(CleanupCategory.SOFTWARE_DISTRIBUTION_CACHE, new Cleaner() {
            public void scan(CleanupRow row) {
                scanSoftwareDistributionCache(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanSoftwareDistributionCache();
            }
        });
        cleaners.put(CleanupCategory.WINDOWS_DIAGNOSTICS_CACHE, new Cleaner() {
            public void scan(CleanupRow row) {
                scanDiagnosticsCache(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanDiagnosticsCache();
            }
        });
        cleaners.put(CleanupCategory.OLD_WINDOWS_INSTALL, new Cleaner() {
            public void scan(CleanupRow row) {
                scanOldWindowsInstall(row);
            }

            public long clean(Path backupRootOrNull) {
                return cleanOldWindowsInstall();
            }
        });
    }

    public List<CleanupRow> scan(Runnable onProgress) {
        CleanupCategory[] categories = CleanupCategory.values();
        CleanupRow[] rows = new CleanupRow[categories.length];
        for (int i = 0; i < categories.length; i++) {
            rows[i] = new CleanupRow(categories[i]);
        }

        int threadCount = Math.min(Runtime.getRuntime().availableProcessors(), 6);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            return scanWithExecutor(rows, categories, onProgress, executor);
        } finally {
            executor.shutdown();
        }
    }

    public List<CleanupRow> scan(Runnable onProgress, ExecutorService sharedExecutor) {
        CleanupCategory[] categories = CleanupCategory.values();
        CleanupRow[] rows = new CleanupRow[categories.length];
        for (int i = 0; i < categories.length; i++) {
            rows[i] = new CleanupRow(categories[i]);
        }
        return scanWithExecutor(rows, categories, onProgress, sharedExecutor);
    }

    private List<CleanupRow> scanWithExecutor(CleanupRow[] rows, CleanupCategory[] categories,
                                               Runnable onProgress, ExecutorService executor) {
        CompletableFuture<?>[] futures = new CompletableFuture[categories.length];
        for (int i = 0; i < categories.length; i++) {
            final CleanupRow row = rows[i];
            futures[i] = CompletableFuture.runAsync(() -> {
                scanCategory(row);
                if (onProgress != null)
                    onProgress.run();
            }, executor);
        }
        CompletableFuture.allOf(futures).join();
        return List.of(rows);
    }

    private void scanCategory(CleanupRow row) {
        try {
            Cleaner c = cleaners.get(row.getCategory());
            if (c != null) {
                c.scan(row);
            } else {
                AppLogger.warning("No cleaner registered for " + row.getCategory().getDisplayName());
                row.setSizeOrCountText("Not supported");
            }
            if (row.getScanStatus() != CleanupRow.ScanStatus.ERROR) {
                row.setScanStatus(CleanupRow.ScanStatus.DONE);
            }
        } catch (Exception e) {
            AppLogger.warning("Scan failed for " + row.getCategory().getDisplayName() + ": " + e.getMessage());
            row.setSizeOrCountText("Error");
            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
            row.setErrorMessage(e.getMessage());
        }
    }

    public CleanSummary clean(List<CleanupRow> selectedRows, boolean registryBackup, Runnable onProgress) {
        long totalBytes = 0;
        int totalItems = 0;
        Map<CleanupCategory, Long> perCategory = new HashMap<>();

        Path backupRoot = null;
        if (registryBackup) {
            backupRoot = AppPaths.backupsRoot().resolve("cleanup-backups")
                    .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        }

        for (CleanupRow row : selectedRows) {
            if (!row.isSelected())
                continue;
            try {
                long cleaned = cleanCategory(row.getCategory(), registryBackup ? backupRoot : null);
                int items = row.getItemCount();
                totalBytes += cleaned;
                totalItems += items;
                perCategory.put(row.getCategory(), cleaned);
                if (onProgress != null)
                    onProgress.run();
            } catch (Exception e) {
                AppLogger.warning("Clean failed for " + row.getCategory().getDisplayName() + ": " + e.getMessage());
            }
        }
        return new CleanSummary(totalBytes, totalItems, perCategory);
    }

    /**
     * Asynchronous, cancelable scan. Returns a CancelableCompletableFuture that can
     * be cancelled
     * which will attempt to cancel per-category workers and shutdown the executor.
     */
    public CancelableCompletableFuture<java.util.List<CleanupRow>> scanAsync(Runnable onProgress) {
        CleanupCategory[] categories = CleanupCategory.values();
        CleanupRow[] rows = new CleanupRow[categories.length];
        for (int i = 0; i < categories.length; i++) {
            rows[i] = new CleanupRow(categories[i]);
        }

        int threadCount = Math.min(Runtime.getRuntime().availableProcessors(), 6);
        java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        java.util.List<CompletableFuture<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < categories.length; i++) {
            final CleanupRow row = rows[i];
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                long startMs = System.currentTimeMillis();
                try {
                    scanCategory(row);
                } catch (Exception e) {
                    AppLogger.warning("Scan failed for " + row.getCategory().getDisplayName() + ": " + e.getMessage());
                    row.setSizeOrCountText("Error");
                    row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                    row.setErrorMessage(e.getMessage());
                } finally {
                    long elapsed = System.currentTimeMillis() - startMs;
                    row.setScanDurationMs(elapsed);
                    if (row.getScanStatus() != CleanupRow.ScanStatus.ERROR) {
                        row.setScanStatus(CleanupRow.ScanStatus.DONE);
                    }
                    if (onProgress != null)
                        onProgress.run();
                }
            }, executor);

            CompletableFuture<Void> timed = f.orTimeout(PER_CATEGORY_SCAN_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        if (ex instanceof java.util.concurrent.TimeoutException) {
                            AppLogger.warning("Scan timed out for " + row.getCategory().getDisplayName());
                            row.setSizeOrCountText("Timed out");
                            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                            row.setErrorMessage("Scan timed out after " + PER_CATEGORY_SCAN_TIMEOUT_SECONDS + "s");
                        }
                        return null;
                    });
            futures.add(timed);
        }

        CompletableFuture<java.util.List<CleanupRow>> finalFuture = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> java.util.List.of(rows));
        finalFuture.whenComplete((r, ex) -> executor.shutdown());

        CancelableCompletableFuture<java.util.List<CleanupRow>> result = new CancelableCompletableFuture<>(futures,
                executor);
        result.completeFrom(finalFuture);
        return result;
    }

    /**
     * Re-scans only specific categories. Used after cleaning to refresh the
     * Size/Count column.
     */
    public CancelableCompletableFuture<java.util.List<CleanupRow>> scanCategoriesAsync(
            java.util.List<CleanupCategory> categories, Runnable onProgress) {
        CleanupRow[] rows = new CleanupRow[categories.size()];
        for (int i = 0; i < categories.size(); i++) {
            rows[i] = new CleanupRow(categories.get(i));
        }

        int threadCount = Math.min(rows.length, Math.min(Runtime.getRuntime().availableProcessors(), 6));
        java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        java.util.List<CompletableFuture<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            final CleanupRow row = rows[i];
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                long startMs = System.currentTimeMillis();
                try {
                    scanCategory(row);
                } catch (Exception e) {
                    AppLogger
                            .warning("Rescan failed for " + row.getCategory().getDisplayName() + ": " + e.getMessage());
                    row.setSizeOrCountText("Error");
                    row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                    row.setErrorMessage(e.getMessage());
                } finally {
                    row.setScanDurationMs(System.currentTimeMillis() - startMs);
                    if (row.getScanStatus() != CleanupRow.ScanStatus.ERROR) {
                        row.setScanStatus(CleanupRow.ScanStatus.DONE);
                    }
                    if (onProgress != null)
                        onProgress.run();
                }
            }, executor);

            CompletableFuture<Void> timed = f.orTimeout(PER_CATEGORY_SCAN_TIMEOUT_SECONDS,
                    java.util.concurrent.TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        if (ex instanceof java.util.concurrent.TimeoutException) {
                            AppLogger.warning("Rescan timed out for " + row.getCategory().getDisplayName());
                            row.setSizeOrCountText("Timed out");
                            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                            row.setErrorMessage("Scan timed out after " + PER_CATEGORY_SCAN_TIMEOUT_SECONDS + "s");
                        }
                        return null;
                    });
            futures.add(timed);
        }

        CompletableFuture<java.util.List<CleanupRow>> finalFuture = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> java.util.List.of(rows));
        finalFuture.whenComplete((r, ex) -> executor.shutdown());

        CancelableCompletableFuture<java.util.List<CleanupRow>> result = new CancelableCompletableFuture<>(futures,
                executor);
        result.completeFrom(finalFuture);
        return result;
    }

    /**
     * Asynchronous, cancelable clean. Returns a CancelableCompletableFuture that
     * completes with a CleanSummary.
     */
    public CancelableCompletableFuture<CleanSummary> cleanAsync(java.util.List<CleanupRow> selectedRows,
            boolean registryBackup, Runnable onProgress) {
        java.util.List<CleanupRow> tasks = selectedRows.stream().filter(CleanupRow::isSelected).toList();
        if (tasks.isEmpty()) {
            CompletableFuture<CleanSummary> done = CompletableFuture
                    .completedFuture(new CleanSummary(0, 0, new java.util.HashMap<>()));
            CancelableCompletableFuture<CleanSummary> cf = new CancelableCompletableFuture<>(
                    java.util.Collections.emptyList(), null);
            cf.completeFrom(done);
            return cf;
        }

        final java.nio.file.Path backupRoot = registryBackup
                ? AppPaths.backupsRoot().resolve("cleanup-backups")
                        .resolve(java.time.LocalDateTime.now()
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
                : null;

        int threadCount = Math.min(tasks.size(), Runtime.getRuntime().availableProcessors());
        java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        java.util.List<CompletableFuture<Long>> futures = new java.util.ArrayList<>();
        for (CleanupRow row : tasks) {
            final CleanupRow taskRow = row;
            CompletableFuture<Long> f = CompletableFuture.supplyAsync(() -> {
                try {
                    return cleanCategory(taskRow.getCategory(), backupRoot);
                } catch (Exception e) {
                    AppLogger.warning(
                            "Clean failed for " + taskRow.getCategory().getDisplayName() + ": " + e.getMessage());
                    return 0L;
                } finally {
                    if (onProgress != null)
                        onProgress.run();
                }
            }, executor);
            futures.add(f);
        }

        CompletableFuture<CleanSummary> finalFuture = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    long totalBytes = 0;
                    int totalItems = 0;
                    java.util.Map<CleanupCategory, Long> perCategory = new java.util.HashMap<>();
                    for (int i = 0; i < tasks.size(); i++) {
                        long cleaned = futures.get(i).join();
                        CleanupRow row = tasks.get(i);
                        totalBytes += cleaned;
                        totalItems += row.getItemCount();
                        perCategory.put(row.getCategory(), cleaned);
                    }
                    return new CleanSummary(totalBytes, totalItems, perCategory);
                });
        finalFuture.whenComplete((r, ex) -> executor.shutdown());

        CancelableCompletableFuture<CleanSummary> result = new CancelableCompletableFuture<>(
                new java.util.ArrayList<>(futures), executor);
        result.completeFrom(finalFuture);
        return result;
    }

    private long cleanCategory(CleanupCategory category, Path backupRootOrNull) throws Exception {
        Cleaner c = cleaners.get(category);
        if (c != null)
            return c.clean(backupRootOrNull);
        throw new UnsupportedOperationException("No cleaner registered for " + category);
    }

    // ── Registry ──────────────────────────────────────────────────────────

    private void scanRegistryDefrag(CleanupRow row) {
        long size = RegistryDefragService.estimateSize();
        row.setTotalBytes(size);
        if (size > 0) {
            row.setSizeOrCountText(formatBytes(size) + " estimated defrag gain");
        } else {
            row.setSizeOrCountText("Ready to compact");
        }
        row.setItemCount(1);
    }

    private long cleanRegistryDefrag() {
        RegistryDefragService.DefragResult result = RegistryDefragService.defrag();
        return result.defraggedCount() > 0 ? RegistryDefragService.estimateSize() : 0;
    }

    private void scanRegistry(CleanupRow row) {
        int count = 0;
        count += countInvalidRegistryValues(WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        count += countInvalidRegistryValues(WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce");
        count += countInvalidRegistryValues(WinReg.HKEY_LOCAL_MACHINE,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        count += countInvalidFileExtensions();
        count += countInvalidCLSIDEntries();
        count += countInvalidAppPaths();
        count += countInvalidUninstallerEntries();
        count += countOrphanedSharedDLLs();
        count += countInvalidInstallerComponents();
        row.setItemCount(count);
        row.setSizeOrCountText(count + " invalid entr" + (count == 1 ? "y" : "ies"));
    }

    private int countInvalidRegistryValues(WinReg.HKEY hive, String keyPath) {
        int count = 0;
        try {
            if (Advapi32Util.registryKeyExists(hive, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(hive, keyPath);
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    String value = entry.getValue().toString();
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (value.contains("\\") && !value.startsWith("-")) {
                        String cleanPath = value;
                        int spaceIdx = cleanPath.indexOf(" -");
                        if (spaceIdx > 0)
                            cleanPath = cleanPath.substring(0, spaceIdx);
                        Path p = Paths.get(cleanPath);
                        if (!Files.exists(p)) {
                            count++;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private int countInvalidFileExtensions() {
        int count = 0;
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT, "")) {
                String[] subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_CLASSES_ROOT, "");
                for (String key : subKeys) {
                    if (key.startsWith(".")) {
                        try {
                            String defaultVal = Advapi32Util.registryGetStringValue(WinReg.HKEY_CLASSES_ROOT, key, "");
                            if (defaultVal != null && !defaultVal.isEmpty()) {
                                if (defaultVal.startsWith("{")) {
                                    if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT,
                                            "CLSID\\" + defaultVal)) {
                                        count++;
                                    }
                                } else {
                                    if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT, defaultVal)) {
                                        count++;
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private int countInvalidCLSIDEntries() {
        int count = 0;
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT, "CLSID")) {
                String[] subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_CLASSES_ROOT, "CLSID");
                for (String guid : subKeys) {
                    if (guid.startsWith("{")) {
                        try {
                            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT,
                                    "CLSID\\" + guid + "\\InprocServer32")) {
                                String serverPath = Advapi32Util.registryGetStringValue(
                                        WinReg.HKEY_CLASSES_ROOT, "CLSID\\" + guid + "\\InprocServer32", "");
                                if (serverPath != null && !serverPath.isEmpty()) {
                                    Path p = Paths.get(serverPath);
                                    if (!Files.exists(p)) {
                                        count++;
                                    }
                                }
                            } else if (Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT,
                                    "CLSID\\" + guid + "\\LocalServer32")) {
                                String serverPath = Advapi32Util.registryGetStringValue(
                                        WinReg.HKEY_CLASSES_ROOT, "CLSID\\" + guid + "\\LocalServer32", "");
                                if (serverPath != null && !serverPath.isEmpty()) {
                                    String cleanPath = serverPath;
                                    if (cleanPath.startsWith("\"") && cleanPath.endsWith("\"")) {
                                        cleanPath = cleanPath.substring(1, cleanPath.length() - 1);
                                    }
                                    int spaceIdx = cleanPath.indexOf(" -");
                                    if (spaceIdx > 0)
                                        cleanPath = cleanPath.substring(0, spaceIdx);
                                    Path p = Paths.get(cleanPath);
                                    if (!Files.exists(p)) {
                                        count++;
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private int countInvalidAppPaths() {
        int count = 0;
        String keyPath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths";
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, keyPath)) {
                String[] subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, keyPath);
                for (String appKey : subKeys) {
                    try {
                        String exePath = Advapi32Util.registryGetStringValue(
                                WinReg.HKEY_LOCAL_MACHINE, keyPath + "\\" + appKey, "");
                        if (exePath != null && !exePath.isEmpty()) {
                            String cleanPath = exePath;
                            if (cleanPath.startsWith("\"") && cleanPath.endsWith("\"")) {
                                cleanPath = cleanPath.substring(1, cleanPath.length() - 1);
                            }
                            Path p = Paths.get(cleanPath);
                            if (!Files.exists(p)) {
                                count++;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private int countInvalidUninstallerEntries() {
        int count = 0;
        String keyPath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall";
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, keyPath)) {
                String[] subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, keyPath);
                for (String guid : subKeys) {
                    if (guid.startsWith("{")) {
                        try {
                            String fullPath = keyPath + "\\" + guid;
                            String displayIcon = null;
                            String uninstallString = null;
                            try {
                                displayIcon = Advapi32Util.registryGetStringValue(
                                        WinReg.HKEY_LOCAL_MACHINE, fullPath, "DisplayIcon");
                            } catch (Exception ignored) {
                            }
                            try {
                                uninstallString = Advapi32Util.registryGetStringValue(
                                        WinReg.HKEY_LOCAL_MACHINE, fullPath, "UninstallString");
                            } catch (Exception ignored) {
                            }
                            String pathToCheck = null;
                            if (displayIcon != null && !displayIcon.isEmpty()) {
                                pathToCheck = displayIcon;
                            } else if (uninstallString != null && !uninstallString.isEmpty()) {
                                pathToCheck = uninstallString;
                            }
                            if (pathToCheck != null) {
                                String cleanPath = pathToCheck;
                                if (cleanPath.startsWith("\"") && cleanPath.endsWith("\"")) {
                                    cleanPath = cleanPath.substring(1, cleanPath.length() - 1);
                                }
                                int spaceIdx = cleanPath.indexOf(" -");
                                if (spaceIdx > 0)
                                    cleanPath = cleanPath.substring(0, spaceIdx);
                                Path p = Paths.get(cleanPath);
                                if (!Files.exists(p)) {
                                    count++;
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private int countOrphanedSharedDLLs() {
        int count = 0;
        String keyPath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\SharedDLLs";
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(
                        WinReg.HKEY_LOCAL_MACHINE, keyPath);
                for (String filePath : values.keySet()) {
                    try {
                        Path p = Paths.get(filePath);
                        if (!Files.exists(p)) {
                            count++;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private int countInvalidInstallerComponents() {
        int count = 0;
        String basePath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Installer\\UserData";
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, basePath)) {
                String[] sids = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, basePath);
                for (String sid : sids) {
                    String compPath = basePath + "\\" + sid + "\\Components";
                    try {
                        if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, compPath)) {
                            String[] compGUIDs = Advapi32Util.registryGetKeys(
                                    WinReg.HKEY_LOCAL_MACHINE, compPath);
                            for (String compGUID : compGUIDs) {
                                try {
                                    String valPath = compPath + "\\" + compGUID;
                                    Map<String, Object> vals = Advapi32Util.registryGetValues(
                                            WinReg.HKEY_LOCAL_MACHINE, valPath);
                                    for (String valName : vals.keySet()) {
                                        if (valName.contains(":") || valName.contains("\\")) {
                                            Path p = Paths.get(valName);
                                            if (!Files.exists(p)) {
                                                count++;
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private long cleanRegistry(Path backupRootOrNull) {
        long cleaned = 0;
        cleaned += deleteInvalidRegistryValues(backupRootOrNull, WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        cleaned += deleteInvalidRegistryValues(backupRootOrNull, WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce");
        cleaned += deleteInvalidRegistryValues(backupRootOrNull, WinReg.HKEY_LOCAL_MACHINE,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        cleaned += cleanInvalidFileExtensions(backupRootOrNull);
        cleaned += cleanInvalidCLSIDEntries(backupRootOrNull);
        cleaned += cleanInvalidAppPaths(backupRootOrNull);
        cleaned += cleanInvalidUninstallerEntries(backupRootOrNull);
        cleaned += cleanOrphanedSharedDLLs(backupRootOrNull);
        cleaned += cleanInvalidInstallerComponents(backupRootOrNull);
        return cleaned;
    }

    private long deleteInvalidRegistryValues(Path backupRootOrNull, WinReg.HKEY hive, String keyPath) {
        long count = 0;
        try {
            if (Advapi32Util.registryKeyExists(hive, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(hive, keyPath);
                List<String> toDelete = new ArrayList<>();
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    String value = entry.getValue().toString();
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (value.contains("\\") && !value.startsWith("-")) {
                        String cleanPath = value;
                        int spaceIdx = cleanPath.indexOf(" -");
                        if (spaceIdx > 0)
                            cleanPath = cleanPath.substring(0, spaceIdx);
                        Path p = Paths.get(cleanPath);
                        if (!Files.exists(p)) {
                            toDelete.add(entry.getKey());
                        }
                    }
                }
                if (!toDelete.isEmpty()) {
                    if (backupRootOrNull != null) {
                        Path regBackup = backupRootOrNull.resolve("registry-" + keyPath.replace("\\", "_") + ".reg");
                        Files.createDirectories(regBackup.getParent());
                        try {
                            ProcessManager.start(new ProcessBuilder("reg", "export",
                                    (hive == WinReg.HKEY_LOCAL_MACHINE ? "HKLM" : "HKCU") + "\\" + keyPath,
                                    regBackup.toString(), "/y").inheritIO()).waitFor();
                        } catch (Exception ignored) {
                        }
                    }

                    for (String valName : toDelete) {
                        try {
                            Advapi32Util.registryDeleteValue(hive, keyPath, valName);
                            count++;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Registry cleanup error: " + e.getMessage());
        }
        return count;
    }

    private void backupRegKey(Path backupRootOrNull, String description, String hiveName, String keyPath) {
        if (backupRootOrNull != null) {
            try {
                Path regBackup = backupRootOrNull.resolve("registry-" + description + ".reg");
                Files.createDirectories(regBackup.getParent());
                ProcessManager.start(
                        new ProcessBuilder("reg", "export", hiveName + "\\" + keyPath, regBackup.toString(), "/y")
                                .inheritIO())
                        .waitFor();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean regDeleteKeyRecursive(String hiveName, String keyPath) {
        WinReg.HKEY hive = switch (hiveName) {
            case "HKLM" -> WinReg.HKEY_LOCAL_MACHINE;
            case "HKCR" -> WinReg.HKEY_CLASSES_ROOT;
            case "HKCU" -> WinReg.HKEY_CURRENT_USER;
            default -> null;
        };
        if (hive == null)
            return false;

        try {
            String[] subKeys = Advapi32Util.registryGetKeys(hive, keyPath);
            for (String sub : subKeys) {
                regDeleteKeyRecursive(hiveName, keyPath + "\\" + sub);
            }
            Advapi32Util.registryDeleteKey(hive, keyPath);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private long cleanInvalidFileExtensions(Path backupRootOrNull) {
        long count = 0;
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT, "")) {
                String[] subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_CLASSES_ROOT, "");
                List<String> toDelete = new ArrayList<>();
                for (String key : subKeys) {
                    if (key.startsWith(".")) {
                        try {
                            String defaultVal = Advapi32Util.registryGetStringValue(WinReg.HKEY_CLASSES_ROOT, key, "");
                            if (defaultVal != null && !defaultVal.isEmpty()) {
                                if (defaultVal.startsWith("{")) {
                                    if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT,
                                            "CLSID\\" + defaultVal)) {
                                        toDelete.add(key);
                                    }
                                } else {
                                    if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT, defaultVal)) {
                                        toDelete.add(key);
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (!toDelete.isEmpty()) {
                    backupRegKey(backupRootOrNull, "fileextensions", "HKCR", "");
                    for (String key : toDelete) {
                        try {
                            if (regDeleteKeyRecursive("HKCR", key))
                                count++;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private long cleanInvalidCLSIDEntries(Path backupRootOrNull) {
        long count = 0;
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT, "CLSID")) {
                String[] subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_CLASSES_ROOT, "CLSID");
                List<String> toDelete = new ArrayList<>();
                for (String guid : subKeys) {
                    if (guid.startsWith("{")) {
                        try {
                            boolean invalid = false;
                            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT,
                                    "CLSID\\" + guid + "\\InprocServer32")) {
                                String serverPath = Advapi32Util.registryGetStringValue(
                                        WinReg.HKEY_CLASSES_ROOT, "CLSID\\" + guid + "\\InprocServer32", "");
                                if (serverPath != null && !serverPath.isEmpty()) {
                                    Path p = Paths.get(serverPath);
                                    if (!Files.exists(p))
                                        invalid = true;
                                }
                            } else if (Advapi32Util.registryKeyExists(WinReg.HKEY_CLASSES_ROOT,
                                    "CLSID\\" + guid + "\\LocalServer32")) {
                                String serverPath = Advapi32Util.registryGetStringValue(
                                        WinReg.HKEY_CLASSES_ROOT, "CLSID\\" + guid + "\\LocalServer32", "");
                                if (serverPath != null && !serverPath.isEmpty()) {
                                    String cleanPath = serverPath;
                                    if (cleanPath.startsWith("\"") && cleanPath.endsWith("\"")) {
                                        cleanPath = cleanPath.substring(1, cleanPath.length() - 1);
                                    }
                                    int spaceIdx = cleanPath.indexOf(" -");
                                    if (spaceIdx > 0)
                                        cleanPath = cleanPath.substring(0, spaceIdx);
                                    Path p = Paths.get(cleanPath);
                                    if (!Files.exists(p))
                                        invalid = true;
                                }
                            }
                            if (invalid)
                                toDelete.add(guid);
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (!toDelete.isEmpty()) {
                    backupRegKey(backupRootOrNull, "clsid", "HKCR", "CLSID");
                    for (String guid : toDelete) {
                        try {
                            if (regDeleteKeyRecursive("HKCR", "CLSID\\" + guid))
                                count++;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private long cleanInvalidAppPaths(Path backupRootOrNull) {
        long count = 0;
        String keyPath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths";
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, keyPath)) {
                String[] subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, keyPath);
                List<String> toDelete = new ArrayList<>();
                for (String appKey : subKeys) {
                    try {
                        String exePath = Advapi32Util.registryGetStringValue(
                                WinReg.HKEY_LOCAL_MACHINE, keyPath + "\\" + appKey, "");
                        if (exePath != null && !exePath.isEmpty()) {
                            String cleanPath = exePath;
                            if (cleanPath.startsWith("\"") && cleanPath.endsWith("\"")) {
                                cleanPath = cleanPath.substring(1, cleanPath.length() - 1);
                            }
                            Path p = Paths.get(cleanPath);
                            if (!Files.exists(p)) {
                                toDelete.add(appKey);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (!toDelete.isEmpty()) {
                    backupRegKey(backupRootOrNull, "apppaths", "HKLM", keyPath);
                    for (String appKey : toDelete) {
                        try {
                            if (regDeleteKeyRecursive("HKLM", keyPath + "\\" + appKey))
                                count++;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private long cleanInvalidUninstallerEntries(Path backupRootOrNull) {
        long count = 0;
        String keyPath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall";
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, keyPath)) {
                String[] subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, keyPath);
                List<String> toDelete = new ArrayList<>();
                for (String guid : subKeys) {
                    if (guid.startsWith("{")) {
                        try {
                            String fullPath = keyPath + "\\" + guid;
                            String displayIcon = null;
                            String uninstallString = null;
                            try {
                                displayIcon = Advapi32Util.registryGetStringValue(
                                        WinReg.HKEY_LOCAL_MACHINE, fullPath, "DisplayIcon");
                            } catch (Exception ignored) {
                            }
                            try {
                                uninstallString = Advapi32Util.registryGetStringValue(
                                        WinReg.HKEY_LOCAL_MACHINE, fullPath, "UninstallString");
                            } catch (Exception ignored) {
                            }
                            String pathToCheck = null;
                            if (displayIcon != null && !displayIcon.isEmpty()) {
                                pathToCheck = displayIcon;
                            } else if (uninstallString != null && !uninstallString.isEmpty()) {
                                pathToCheck = uninstallString;
                            }
                            if (pathToCheck != null) {
                                String cleanPath = pathToCheck;
                                if (cleanPath.startsWith("\"") && cleanPath.endsWith("\"")) {
                                    cleanPath = cleanPath.substring(1, cleanPath.length() - 1);
                                }
                                int spaceIdx = cleanPath.indexOf(" -");
                                if (spaceIdx > 0)
                                    cleanPath = cleanPath.substring(0, spaceIdx);
                                Path p = Paths.get(cleanPath);
                                if (!Files.exists(p)) {
                                    toDelete.add(guid);
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (!toDelete.isEmpty()) {
                    backupRegKey(backupRootOrNull, "uninstall", "HKLM", keyPath);
                    for (String guid : toDelete) {
                        try {
                            if (regDeleteKeyRecursive("HKLM", keyPath + "\\" + guid))
                                count++;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private long cleanOrphanedSharedDLLs(Path backupRootOrNull) {
        long count = 0;
        String keyPath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\SharedDLLs";
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(
                        WinReg.HKEY_LOCAL_MACHINE, keyPath);
                List<String> toDelete = new ArrayList<>();
                for (String filePath : values.keySet()) {
                    try {
                        Path p = Paths.get(filePath);
                        if (!Files.exists(p)) {
                            toDelete.add(filePath);
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (!toDelete.isEmpty()) {
                    backupRegKey(backupRootOrNull, "shareddlls", "HKLM", keyPath);
                    for (String valName : toDelete) {
                        try {
                            Advapi32Util.registryDeleteValue(
                                    WinReg.HKEY_LOCAL_MACHINE, keyPath, valName);
                            count++;
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    private long cleanInvalidInstallerComponents(Path backupRootOrNull) {
        long count = 0;
        String basePath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Installer\\UserData";
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, basePath)) {
                String[] sids = Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, basePath);
                List<String> allToDelete = new ArrayList<>();
                Map<String, List<String>> deletionsBySid = new HashMap<>();
                for (String sid : sids) {
                    String compPath = basePath + "\\" + sid + "\\Components";
                    try {
                        if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, compPath)) {
                            String[] compGUIDs = Advapi32Util.registryGetKeys(
                                    WinReg.HKEY_LOCAL_MACHINE, compPath);
                            for (String compGUID : compGUIDs) {
                                try {
                                    String valPath = compPath + "\\" + compGUID;
                                    Map<String, Object> vals = Advapi32Util.registryGetValues(
                                            WinReg.HKEY_LOCAL_MACHINE, valPath);
                                    for (String valName : vals.keySet()) {
                                        if (valName.contains(":") || valName.contains("\\")) {
                                            Path p = Paths.get(valName);
                                            if (!Files.exists(p)) {
                                                allToDelete.add(valName);
                                                deletionsBySid.computeIfAbsent(sid, k -> new ArrayList<>())
                                                        .add(valPath + "\\" + valName);
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                if (!allToDelete.isEmpty()) {
                    backupRegKey(backupRootOrNull, "installercomponents", "HKLM", basePath);
                    for (Map.Entry<String, List<String>> entry : deletionsBySid.entrySet()) {
                        for (String fullPath : entry.getValue()) {
                            try {
                                String[] parts = fullPath.replace(basePath + "\\", "").split("\\\\", 2);
                                if (parts.length == 2) {
                                    Advapi32Util.registryDeleteValue(
                                            WinReg.HKEY_LOCAL_MACHINE, parts[0], parts[1]);
                                    count++;
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return count;
    }

    // ── Recycle Bin ───────────────────────────────────────────────────────

    private long getRecycleBinSize() {
        long size = 0;
        try {
            Path recycleBin = safeEnvPath("SYSTEMDRIVE", "$Recycle.Bin");
            if (recycleBin != null && Files.isDirectory(recycleBin)) {
                try (Stream<Path> walk = Files.walk(recycleBin)) {
                    size = walk.filter(Files::isRegularFile)
                            .filter(p -> !p.getFileName().toString().startsWith("$I"))
                            .mapToLong(p -> p.toFile().length())
                            .sum();
                }
            }
        } catch (Exception ignored) {
        }
        return size;
    }

    private void scanRecycleBin(CleanupRow row) {
        long size = getRecycleBinSize();
        row.setTotalBytes(size);
        if (size > 0) {
            row.setSizeOrCountText(formatBytes(size));
        } else {
            row.setSizeOrCountText("Empty");
        }
    }

    private long cleanRecycleBin() {
        long size = getRecycleBinSize();
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-Command",
                    "Clear-RecycleBin -Force -ErrorAction SilentlyContinue");
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                AppLogger.warning("Recycle Bin cleanup timed out");
            }
        } catch (Exception ex) {
            AppLogger.warning("Failed to empty Recycle Bin via PowerShell, trying fallback: " + ex.getMessage());
            try {
                String sysdrive = safeEnv("SYSTEMDRIVE");
                if (sysdrive != null) {
                    ProcessBuilder pb = new ProcessBuilder("cmd", "/c",
                            "rd", "/s", "/q", sysdrive + "\\$Recycle.Bin");
                    pb.redirectErrorStream(true);
                    Process p = ProcessManager.start(pb);
                    boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                    if (!finished) {
                        p.destroyForcibly();
                    }
                }
            } catch (Exception ex2) {
                AppLogger.warning("Failed to empty Recycle Bin: " + ex2.getMessage());
            }
        }
        return size;
    }

    // ── Junk Files ────────────────────────────────────────────────────────

    private List<Path> getJunkDirs() {
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "TEMP");
        addEnvPath(dirs, "TMP");
        addEnvPath(dirs, "WINDIR", "Temp");
        addEnvPath(dirs, "LOCALAPPDATA", "Temp");
        return dirs;
    }

    private void scanJunkFiles(CleanupRow row) {
        scanDirectorySizesOlderThan(row, getJunkDirs(), java.time.Duration.ofDays(1));
    }

    // ── Privacy Traces ────────────────────────────────────────────────────

    private void scanPrivacyTraces(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;

        Path recentDir = safeEnvPath("APPDATA", "Microsoft", "Windows", "Recent");
        if (recentDir != null && Files.isDirectory(recentDir)) {
            try (Stream<Path> files = Files.list(recentDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f)) {
                        totalSize += Files.size(f);
                        itemCount++;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Count RunMRU entries that will be cleaned
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU")) {
                Map<String, Object> values = Advapi32Util.registryGetValues(WinReg.HKEY_CURRENT_USER,
                        "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU");
                for (String key : values.keySet()) {
                    if (!"MRUListEx".equals(key) && !"MRUList".equals(key)) {
                        itemCount++;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // Count RecentDocs entries that will be cleaned
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RecentDocs")) {
                String[] subKeys = Advapi32Util.registryGetKeys(WinReg.HKEY_CURRENT_USER,
                        "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RecentDocs");
                itemCount += subKeys.length;
            }
        } catch (Exception ignored) {
        }

        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(itemCount + " item" + (itemCount == 1 ? "" : "s") + " / " + formatBytes(totalSize));
    }

    private long cleanPrivacyTraces() {
        long cleaned = 0;
        Path recentDir = safeEnvPath("APPDATA", "Microsoft", "Windows", "Recent");
        if (recentDir != null && Files.isDirectory(recentDir)) {
            try (Stream<Path> files = Files.list(recentDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f)) {
                        long size = Files.size(f);
                        deletePermanently(f);
                        cleaned += size;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU")) {
                Map<String, Object> values = Advapi32Util.registryGetValues(WinReg.HKEY_CURRENT_USER,
                        "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU");
                for (String key : values.keySet()) {
                    if (!"MRUListEx".equals(key) && !"MRUList".equals(key)) {
                        try {
                            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER,
                                    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU", key);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RecentDocs")) {
                Advapi32Util.registryDeleteKey(WinReg.HKEY_CURRENT_USER,
                        "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RecentDocs");
            }
        } catch (Exception ignored) {
        }

        return cleaned;
    }

    // ── Web Browsing Traces ───────────────────────────────────────────────

    private record BrowserProfile(String name, List<Path> cacheDirs) {
    }

    private List<BrowserProfile> getBrowserProfiles() {
        List<BrowserProfile> profiles = new ArrayList<>();

        String localAppData = safeEnv("LOCALAPPDATA");
        String appData = safeEnv("APPDATA");

        // Chrome - multi-profile
        if (localAppData != null) {
            Path chromeUserData = Paths.get(localAppData, "Google", "Chrome", "User Data");
            profiles.addAll(getChromiumProfiles("Chrome", chromeUserData));
        }

        // Edge - multi-profile
        if (localAppData != null) {
            Path edgeUserData = Paths.get(localAppData, "Microsoft", "Edge", "User Data");
            profiles.addAll(getChromiumProfiles("Edge", edgeUserData));
        }

        // Firefox - already enumerates all profiles
        List<Path> firefoxDirs = new ArrayList<>();
        if (appData != null) {
            Path firefoxProfiles = Paths.get(appData, "Mozilla", "Firefox", "Profiles");
            if (Files.isDirectory(firefoxProfiles)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(firefoxProfiles)) {
                    for (Path profile : ds) {
                        firefoxDirs.add(profile.resolve("cache2"));
                        firefoxDirs.add(profile.resolve("thumbnails"));
                        firefoxDirs.add(profile.resolve("offlinecache"));
                    }
                } catch (Exception ignored) {
                }
            }
        }
        profiles.add(new BrowserProfile("Firefox", firefoxDirs));

        // Brave - multi-profile
        if (localAppData != null) {
            Path braveUserData = Paths.get(localAppData, "BraveSoftware", "Brave-Browser", "User Data");
            profiles.addAll(getChromiumProfiles("Brave", braveUserData));
        }

        // Opera - single profile location
        List<Path> operaDirs = new ArrayList<>();
        if (appData != null) {
            Path opera = Paths.get(appData, "Opera Software", "Opera Stable");
            operaDirs.add(opera.resolve("Cache"));
            operaDirs.add(opera.resolve("Code Cache"));
        }
        profiles.add(new BrowserProfile("Opera", operaDirs));

        // Opera GX - single profile location
        List<Path> operaGxDirs = new ArrayList<>();
        if (appData != null) {
            Path operaGX = Paths.get(appData, "Opera Software", "Opera GX Stable");
            operaGxDirs.add(operaGX.resolve("Cache"));
            operaGxDirs.add(operaGX.resolve("Code Cache"));
        }
        profiles.add(new BrowserProfile("Opera GX", operaGxDirs));

        // Vivaldi - multi-profile
        if (localAppData != null) {
            Path vivaldiUserData = Paths.get(localAppData, "Vivaldi", "User Data");
            profiles.addAll(getChromiumProfiles("Vivaldi", vivaldiUserData));
        }

        // Chromium - multi-profile
        if (localAppData != null) {
            Path chromiumUserData = Paths.get(localAppData, "Chromium", "User Data");
            profiles.addAll(getChromiumProfiles("Chromium", chromiumUserData));
        }

        // Yandex Browser - multi-profile
        if (localAppData != null) {
            Path yandexUserData = Paths.get(localAppData, "Yandex", "YandexBrowser", "User Data");
            profiles.addAll(getChromiumProfiles("Yandex Browser", yandexUserData));
        }

        return profiles;
    }

    private List<BrowserProfile> getChromiumProfiles(String browserName, Path userDataDir) {
        List<BrowserProfile> profiles = new ArrayList<>();
        if (!Files.isDirectory(userDataDir))
            return profiles;

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(userDataDir)) {
            for (Path entry : ds) {
                if (Files.isDirectory(entry)) {
                    String dirName = entry.getFileName().toString();
                    if (dirName.equals("Default") || dirName.startsWith("Profile ")) {
                        List<Path> cacheDirs = new ArrayList<>();
                        cacheDirs.add(entry.resolve("Cache"));
                        cacheDirs.add(entry.resolve("Code Cache"));
                        cacheDirs.add(entry.resolve("Network"));
                        String profileLabel = dirName.equals("Default") ? browserName
                                : browserName + " (" + dirName + ")";
                        profiles.add(new BrowserProfile(profileLabel, cacheDirs));
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (profiles.isEmpty()) {
            List<Path> cacheDirs = new ArrayList<>();
            cacheDirs.add(userDataDir.resolve("Default").resolve("Cache"));
            cacheDirs.add(userDataDir.resolve("Default").resolve("Code Cache"));
            cacheDirs.add(userDataDir.resolve("Default").resolve("Network"));
            profiles.add(new BrowserProfile(browserName, cacheDirs));
        }

        return profiles;
    }

    private void scanBrowserTraces(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        for (BrowserProfile profile : getBrowserProfiles()) {
            for (Path dir : profile.cacheDirs()) {
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> walk = Files.walk(dir)) {
                        var stats = walk.filter(Files::isRegularFile)
                                .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                        totalSize += stats.getSum();
                        itemCount += (int) stats.getCount();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(itemCount + " item" + (itemCount == 1 ? "" : "s") + " / " + formatBytes(totalSize));
    }

    private long cleanBrowserTraces() {
        long cleaned = 0;
        for (BrowserProfile profile : getBrowserProfiles()) {
            for (Path dir : profile.cacheDirs()) {
                if (Files.isDirectory(dir)) {
                    cleaned += deleteDirectoryContents(dir);
                }
            }
            String localAppData = safeEnv("LOCALAPPDATA");
            String appData = safeEnv("APPDATA");
            List<Path> extraFiles = new ArrayList<>();
            String name = profile.name();
            if (name.startsWith("Chrome")) {
                if (localAppData != null) {
                    Path base = dirForProfile(localAppData, "Google", "Chrome", "User Data", name);
                    if (base != null)
                        addBrowserDbFiles(extraFiles, base, true);
                }
            } else if (name.startsWith("Edge")) {
                if (localAppData != null) {
                    Path base = dirForProfile(localAppData, "Microsoft", "Edge", "User Data", name);
                    if (base != null)
                        addBrowserDbFiles(extraFiles, base, true);
                }
            } else if (name.equals("Firefox")) {
                if (appData != null) {
                    Path profilesDir = Paths.get(appData, "Mozilla", "Firefox", "Profiles");
                    if (Files.isDirectory(profilesDir)) {
                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(profilesDir)) {
                            for (Path firefoxProfile : ds) {
                                extraFiles.add(firefoxProfile.resolve("cookies.sqlite"));
                                extraFiles.add(firefoxProfile.resolve("cookies.sqlite-wal"));
                                extraFiles.add(firefoxProfile.resolve("places.sqlite"));
                                extraFiles.add(firefoxProfile.resolve("places.sqlite-wal"));
                                extraFiles.add(firefoxProfile.resolve("formhistory.sqlite"));
                                extraFiles.add(firefoxProfile.resolve("favicons.sqlite"));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            } else if (name.startsWith("Brave")) {
                if (localAppData != null) {
                    Path base = dirForProfile(localAppData, "BraveSoftware", "Brave-Browser", "User Data", name);
                    if (base != null) {
                        extraFiles.add(base.resolve("Cookies"));
                        extraFiles.add(base.resolve("History"));
                    }
                }
            } else if (name.startsWith("Vivaldi")) {
                if (localAppData != null) {
                    Path base = dirForProfile(localAppData, "Vivaldi", "User Data", name);
                    if (base != null) {
                        extraFiles.add(base.resolve("Cookies"));
                        extraFiles.add(base.resolve("History"));
                    }
                }
            } else if (name.equals("Opera")) {
                if (appData != null) {
                    Path base = Paths.get(appData, "Opera Software", "Opera Stable");
                    extraFiles.add(base.resolve("Cookies"));
                    extraFiles.add(base.resolve("History"));
                }
            } else if (name.equals("Opera GX")) {
                if (appData != null) {
                    Path base = Paths.get(appData, "Opera Software", "Opera GX Stable");
                    extraFiles.add(base.resolve("Cookies"));
                    extraFiles.add(base.resolve("History"));
                }
            } else if (name.startsWith("Chromium")) {
                if (localAppData != null) {
                    Path base = dirForProfile(localAppData, "Chromium", "User Data", name);
                    if (base != null)
                        addBrowserDbFiles(extraFiles, base, true);
                }
            } else if (name.startsWith("Yandex Browser")) {
                if (localAppData != null) {
                    Path base = dirForProfile(localAppData, "Yandex", "YandexBrowser", "User Data", name);
                    if (base != null)
                        addBrowserDbFiles(extraFiles, base, true);
                }
            }
            for (Path f : extraFiles) {
                if (Files.isRegularFile(f)) {
                    try {
                        long size = Files.size(f);
                        deletePermanently(f);
                        cleaned += size;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return cleaned;
    }

    private Path dirForProfile(String localAppData, String... pathParts) {
        String profileLabel = pathParts[pathParts.length - 1];
        String dirName = profileLabel.contains("(")
                ? profileLabel.substring(profileLabel.indexOf('(') + 1, profileLabel.indexOf(')'))
                : "Default";
        Path userData = Paths.get(localAppData, java.util.Arrays.copyOf(pathParts, pathParts.length - 1));
        Path profileDir = userData.resolve(dirName);
        return Files.isDirectory(profileDir) ? profileDir : null;
    }

    private void addBrowserDbFiles(List<Path> extraFiles, Path base, boolean hasJournals) {
        extraFiles.add(base.resolve("Cookies"));
        extraFiles.add(base.resolve("History"));
        extraFiles.add(base.resolve("Login Data"));
        if (hasJournals) {
            extraFiles.add(base.resolve("Cookies-journal"));
            extraFiles.add(base.resolve("History-journal"));
        }
    }

    // ── Cache ─────────────────────────────────────────────────────────────

    private List<Path> getCacheDirs() {
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "Windows", "INetCache");
        addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "Windows", "INetCookies");
        return dirs;
    }

    private void scanCache(CleanupRow row) {
        List<Path> dirs = getCacheDirs();
        long totalSize = 0;
        int itemCount = 0;
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                try {
                                    return !Files.isHidden(p);
                                } catch (Exception e) {
                                    return true;
                                }
                            })
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    // ── Installer Files ───────────────────────────────────────────────────

    private void scanInstallerFiles(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        List<Path> dirs = new ArrayList<>();

        Path winInstaller = safeEnvPath("WINDIR", "Installer");
        if (winInstaller != null && Files.isDirectory(winInstaller)) {
            dirs.add(winInstaller);
        }

        Path tempDir = safeEnvPath("TEMP");
        if (tempDir != null && Files.isDirectory(tempDir)) {
            try (Stream<Path> files = Files.list(tempDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f)) {
                        String name = f.getFileName().toString().toLowerCase();
                        if (name.endsWith(".msi") || name.endsWith(".exe")) {
                            long size = Files.size(f);
                            if (size > 10 * 1024 * 1024) {
                                totalSize += size;
                                itemCount++;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        for (Path dir : dirs) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                String name = p.getFileName().toString().toLowerCase();
                                return name.endsWith(".msi") || name.contains(".cab");
                            })
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }

        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanInstallerFiles() {
        long cleaned = 0;

        String windir = safeEnv("WINDIR");
        if (windir != null) {
            Path winInstaller = Paths.get(windir, "Installer");
            if (Files.isDirectory(winInstaller)) {
                try (Stream<Path> walk = Files.walk(winInstaller)) {
                    List<Path> toDelete = walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                String name = p.getFileName().toString().toLowerCase();
                                return name.endsWith(".msi") || name.contains(".cab");
                            })
                            .toList();
                    for (Path f : toDelete) {
                        long size = f.toFile().length();
                        deletePermanently(f);
                        cleaned += size;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        Path tempDir = safeEnvPath("TEMP");
        if (tempDir != null && Files.isDirectory(tempDir)) {
            try (Stream<Path> files = Files.list(tempDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f)) {
                        String name = f.getFileName().toString().toLowerCase();
                        if (name.endsWith(".msi") || name.endsWith(".exe")) {
                            long size = Files.size(f);
                            if (size > 10 * 1024 * 1024) {
                                deletePermanently(f);
                                cleaned += size;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return cleaned;
    }

    // ── Temporary System Files ────────────────────────────────────────────

    private List<Path> getTempSystemDirs() {
        List<Path> dirs = new ArrayList<>();
        String windir = safeEnv("WINDIR");
        String sysdrive = safeEnv("SYSTEMDRIVE");
        if (windir != null) {
            addPath(dirs, windir + "\\Prefetch");
        }
        if (sysdrive != null) {
            Path btDir = Paths.get(sysdrive + "\\$Windows.~BT");
            Path wsDir = Paths.get(sysdrive + "\\$Windows.~WS");
            Path resetDir = Paths.get(sysdrive + "\\$SysReset");
            if (Files.exists(btDir) && !isUpgradeInProgress()) {
                dirs.add(btDir);
            }
            if (Files.exists(wsDir) && !isUpgradeInProgress()) {
                dirs.add(wsDir);
            }
            if (Files.exists(resetDir) && !isUpgradeInProgress()) {
                dirs.add(resetDir);
            }
        }
        return dirs;
    }

    private boolean isUpgradeInProgress() {
        try {
            String keyPath = "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Setup\\State";
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_LOCAL_MACHINE, keyPath)) {
                String state = Advapi32Util.registryGetStringValue(
                        WinReg.HKEY_LOCAL_MACHINE, keyPath, "ImageState");
                if (state != null && !state.isEmpty()) {
                    state = state.toLowerCase();
                    return !state.contains("complete") && !state.contains("finalize");
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private void scanTempSystemFiles(CleanupRow row) {
        scanDirectorySizes(row, getTempSystemDirs());
    }

    // ── Memory Dumps ──────────────────────────────────────────────────────

    private void scanMemoryDumps(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "WINDIR", "Minidump");
        String windir = safeEnv("WINDIR");
        if (windir != null) {
            addPath(dirs, windir);
        }
        long totalSize = 0;
        int itemCount = 0;
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    var matched = files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".dmp"))
                            .toList();
                    for (Path f : matched) {
                        totalSize += f.toFile().length();
                        itemCount++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        // Also check root for memory.dmp
        String sysdrive = safeEnv("SYSTEMDRIVE");
        if (sysdrive != null) {
            Path rootDump = Paths.get(sysdrive, "memory.dmp");
            if (Files.isRegularFile(rootDump)) {
                totalSize += rootDump.toFile().length();
                itemCount++;
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanMemoryDumps() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "WINDIR", "Minidump");
        String windir = safeEnv("WINDIR");
        String sysdrive = safeEnv("SYSTEMDRIVE");
        if (sysdrive != null) {
            Path rootDump = Paths.get(sysdrive, "memory.dmp");
            if (Files.isRegularFile(rootDump)) {
                long size = rootDump.toFile().length();
                deletePermanently(rootDump);
                cleaned += size;
            }
            Path swaDump = Paths.get(sysdrive, "SWA.DMP");
            if (Files.isRegularFile(swaDump)) {
                long size = swaDump.toFile().length();
                deletePermanently(swaDump);
                cleaned += size;
            }
        }
        if (windir != null) {
            Path windirPath = Paths.get(windir);
            if (Files.isDirectory(windirPath)) {
                try (Stream<Path> files = Files.list(windirPath)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        if (Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".dmp")) {
                            long size = Files.size(f);
                            deletePermanently(f);
                            cleaned += size;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        if (Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".dmp")) {
                            long size = Files.size(f);
                            deletePermanently(f);
                            cleaned += size;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return cleaned;
    }

    // ── Windows Error Reporting ───────────────────────────────────────────

    private void scanWindowsErrorReporting(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "Windows", "WER");
        addEnvPath(dirs, "PROGRAMDATA", "Microsoft", "Windows", "WER");
        scanDirectorySizes(row, dirs, 4);
    }

    private long cleanWindowsErrorReporting() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "Windows", "WER");
        addEnvPath(dirs, "PROGRAMDATA", "Microsoft", "Windows", "WER");
        cleaned += cleanDirectoryPattern(dirs);
        return cleaned;
    }

    // ── Windows Update Cleanup ────────────────────────────────────────────

    private void scanWindowsUpdateCleanup(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        // Try fast DISM query with short timeout
        try {
            ProcessBuilder pb = new ProcessBuilder("dism", "/Online", "/Cleanup-Image",
                    "/AnalyzeComponentStore");
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                String output = new String(p.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (String line : output.split("\\n")) {
                    if (line.contains("Size of superseded components")) {
                        String[] parts = line.split(":");
                        if (parts.length >= 2) {
                            String sizeStr = parts[1].replaceAll("[^0-9]", "").trim();
                            if (!sizeStr.isEmpty()) {
                                totalSize = Long.parseLong(sizeStr) * 1024L * 1024L;
                            }
                        }
                    }
                }
            } else {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {}
        // Fallback: scan SoftwareDistribution\Download
        if (totalSize == 0) {
            String windir = safeEnv("WINDIR");
            if (windir != null) {
                List<Path> dirs = new ArrayList<>();
                addPath(dirs, windir + "\\SoftwareDistribution\\Download");
                try {
                    for (Path dir : dirs) {
                        if (dir != null && Files.isDirectory(dir)) {
                            try (Stream<Path> walk = Files.walk(dir)) {
                                var stats = walk.filter(Files::isRegularFile)
                                        .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                                totalSize += stats.getSum();
                                itemCount += (int) stats.getCount();
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanWindowsUpdateCleanup() {
        long cleaned = 0;
        try {
            ProcessBuilder pb = new ProcessBuilder("dism", "/Online", "/Cleanup-Image",
                    "/StartComponentCleanup");
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                AppLogger.info("DISM component cleanup completed successfully");
            } else {
                AppLogger.warning("DISM cleanup timed out after 300 seconds");
                p.destroyForcibly();
            }
        } catch (Exception e) {
            AppLogger.warning("DISM cleanup failed: " + e.getMessage());
        }
        // Also clean SoftwareDistribution\Download
        String windir = safeEnv("WINDIR");
        if (windir != null) {
            Path sd = Paths.get(windir, "SoftwareDistribution", "Download");
            if (Files.isDirectory(sd)) {
                cleaned += deleteDirectoryContents(sd);
            }
        }
        return cleaned;
    }

    // ── Thumbnail Cache ───────────────────────────────────────────────────

    private void scanThumbnailCache(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String localAppData = safeEnv("LOCALAPPDATA");
        if (localAppData != null) {
            Path explorerDir = Paths.get(localAppData, "Microsoft", "Windows", "Explorer");
            if (Files.isDirectory(explorerDir)) {
                try (Stream<Path> files = Files.list(explorerDir)) {
                    var matched = files.filter(Files::isRegularFile)
                            .filter(p -> {
                                String name = p.getFileName().toString().toLowerCase();
                                return name.startsWith("thumbcache_") || name.startsWith("iconcache_");
                            })
                            .toList();
                    for (Path f : matched) {
                        totalSize += f.toFile().length();
                        itemCount++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanThumbnailCache() {
        long cleaned = 0;
        String localAppData = safeEnv("LOCALAPPDATA");
        if (localAppData != null) {
            Path explorerDir = Paths.get(localAppData, "Microsoft", "Windows", "Explorer");
            if (Files.isDirectory(explorerDir)) {
                try (Stream<Path> files = Files.list(explorerDir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        if (Files.isRegularFile(f)) {
                            String name = f.getFileName().toString().toLowerCase();
                            if (name.startsWith("thumbcache_") || name.startsWith("iconcache_")) {
                                long size = Files.size(f);
                                deletePermanently(f);
                                cleaned += size;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return cleaned;
    }

    // ── Empty Folders ─────────────────────────────────────────────────────

    private void scanEmptyFolders(CleanupRow row) {
        int count = 0;
        List<Path> roots = new ArrayList<>();
        String userHome = safeEnv("USERPROFILE");
        if (userHome != null) {
            addPath(roots, userHome + "\\Desktop");
            addPath(roots, userHome + "\\Documents");
            addPath(roots, userHome + "\\Downloads");
            addPath(roots, userHome + "\\Pictures");
            addPath(roots, userHome + "\\Music");
            addPath(roots, userHome + "\\Videos");
        }
        addEnvPath(roots, "TEMP");
        for (Path root : roots) {
            if (root != null && Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root, 3)) {
                    count += (int) walk.filter(Files::isDirectory)
                            .filter(this::isEmptyDirectory)
                            .count();
                } catch (Exception ignored) {
                }
            }
        }
        row.setItemCount(count);
        row.setSizeOrCountText(count + " empty folder" + (count == 1 ? "" : "s"));
    }

    private boolean isEmptyDirectory(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            return !ds.iterator().hasNext();
        } catch (Exception e) {
            return false;
        }
    }

    private long cleanEmptyFolders() {
        long deletedCount = 0;
        List<Path> roots = new ArrayList<>();
        String userHome = safeEnv("USERPROFILE");
        if (userHome != null) {
            addPath(roots, userHome + "\\Desktop");
            addPath(roots, userHome + "\\Documents");
            addPath(roots, userHome + "\\Downloads");
            addPath(roots, userHome + "\\Pictures");
            addPath(roots, userHome + "\\Music");
            addPath(roots, userHome + "\\Videos");
        }
        addEnvPath(roots, "TEMP");
        for (Path root : roots) {
            if (root != null && Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root, 3)) {
                    List<Path> emptyDirs = walk.filter(Files::isDirectory)
                            .filter(this::isEmptyDirectory)
                            .sorted(Comparator.reverseOrder())
                            .toList();
                    for (Path dir : emptyDirs) {
                        try {
                            Files.deleteIfExists(dir);
                            deletedCount++;
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return deletedCount;
    }

    // ── Notification History ──────────────────────────────────────────────

    private void scanNotificationHistory(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "Windows", "Notifications");
        scanDirectorySizes(row, dirs);
    }

    private long cleanNotificationHistory() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "Windows", "Notifications");
        cleaned += cleanDirectoryPattern(dirs);
        return cleaned;
    }

    // ── Font Cache ────────────────────────────────────────────────────────

    private void scanFontCache(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "WINDIR", "ServiceProfiles", "LocalService", "AppData", "Local", "FontCache");
        scanDirectorySizes(row, dirs);
    }

    private long cleanFontCache() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "WINDIR", "ServiceProfiles", "LocalService", "AppData", "Local", "FontCache");
        cleaned += cleanDirectoryPattern(dirs);
        return cleaned;
    }

    // ── Taskbar Jump Lists ────────────────────────────────────────────────

    private void scanTaskbarJumpLists(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "APPDATA", "Microsoft", "Windows", "Recent", "AutomaticDestinations");
        addEnvPath(dirs, "APPDATA", "Microsoft", "Windows", "Recent", "CustomDestinations");
        scanDirectorySizes(row, dirs);
    }

    private long cleanTaskbarJumpLists() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "APPDATA", "Microsoft", "Windows", "Recent", "AutomaticDestinations");
        addEnvPath(dirs, "APPDATA", "Microsoft", "Windows", "Recent", "CustomDestinations");
        cleaned += cleanDirectoryPattern(dirs);
        return cleaned;
    }

    // ── Office Document Cache ─────────────────────────────────────────────

    private void scanOfficeDocumentCache(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String localAppData = safeEnv("LOCALAPPDATA");
        if (localAppData != null) {
            Path officeParent = Paths.get(localAppData, "Microsoft", "Office");
            if (Files.isDirectory(officeParent)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(officeParent)) {
                    for (Path versionDir : ds) {
                        if (Files.isDirectory(versionDir)) {
                            Path fileCache = versionDir.resolve("OfficeFileCache");
                            if (Files.isDirectory(fileCache)) {
                                try (Stream<Path> walk = Files.walk(fileCache)) {
                                    var stats = walk.filter(Files::isRegularFile)
                                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                                    totalSize += stats.getSum();
                                    itemCount += (int) stats.getCount();
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanOfficeDocumentCache() {
        long cleaned = 0;
        String localAppData = safeEnv("LOCALAPPDATA");
        if (localAppData != null) {
            Path officeParent = Paths.get(localAppData, "Microsoft", "Office");
            if (Files.isDirectory(officeParent)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(officeParent)) {
                    for (Path versionDir : ds) {
                        if (Files.isDirectory(versionDir)) {
                            Path fileCache = versionDir.resolve("OfficeFileCache");
                            if (Files.isDirectory(fileCache)) {
                                cleaned += deleteDirectoryContents(fileCache);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return cleaned;
    }

    // ── Windows Defender Cache ────────────────────────────────────────────

    private void scanWindowsDefenderCache(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "PROGRAMDATA", "Microsoft", "Windows Defender", "Scans", "History");
        scanDirectorySizes(row, dirs, 4);
    }

    private long cleanWindowsDefenderCache() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "PROGRAMDATA", "Microsoft", "Windows Defender", "Scans", "History");
        cleaned += cleanDirectoryPattern(dirs);
        return cleaned;
    }

    // ── Windows Log Files ─────────────────────────────────────────────────

    private void scanWindowsLogFiles(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String windir = safeEnv("WINDIR");
        if (windir != null) {
            Path logsDir = Paths.get(windir, "Logs");
            if (Files.isDirectory(logsDir)) {
                try (Stream<Path> walk = Files.walk(logsDir, 2)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                String name = p.getFileName().toString().toLowerCase();
                                return name.endsWith(".log") || name.endsWith(".etl");
                            })
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanWindowsLogFiles() {
        long cleaned = 0;
        String windir = safeEnv("WINDIR");
        if (windir != null) {
            Path logsDir = Paths.get(windir, "Logs");
            if (Files.isDirectory(logsDir)) {
                try (Stream<Path> walk = Files.walk(logsDir, 2)) {
                    List<Path> toDelete = walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                String name = p.getFileName().toString().toLowerCase();
                                return name.endsWith(".log") || name.endsWith(".etl");
                            })
                            .toList();
                    for (Path f : toDelete) {
                        long size = Files.size(f);
                        deletePermanently(f);
                        cleaned += size;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return cleaned;
    }

    // ── Windows Store Cache ───────────────────────────────────────────────

    private void scanWindowsStoreCache(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String localAppData = safeEnv("LOCALAPPDATA");
        if (localAppData != null) {
            Path packagesDir = Paths.get(localAppData, "Packages");
            if (Files.isDirectory(packagesDir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(packagesDir)) {
                    for (Path pkg : ds) {
                        if (Files.isDirectory(pkg)) {
                            Path localState = pkg.resolve("LocalState");
                            if (Files.isDirectory(localState)) {
                                try (Stream<Path> walk = Files.walk(localState, 1)) {
                                    var stats = walk.filter(Files::isRegularFile)
                                            .filter(f -> {
                                                try {
                                                    return !Files.isHidden(f);
                                                } catch (Exception e) {
                                                    return true;
                                                }
                                            })
                                            .collect(java.util.stream.Collectors.summarizingLong(f -> f.toFile().length()));
                                    totalSize += stats.getSum();
                                    itemCount += (int) stats.getCount();
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanWindowsStoreCache() {
        long cleaned = 0;
        String localAppData = safeEnv("LOCALAPPDATA");
        if (localAppData != null) {
            Path packagesDir = Paths.get(localAppData, "Packages");
            if (Files.isDirectory(packagesDir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(packagesDir)) {
                    for (Path pkg : ds) {
                        if (Files.isDirectory(pkg)) {
                            Path localState = pkg.resolve("LocalState");
                            if (Files.isDirectory(localState)) {
                                try (Stream<Path> walk = Files.walk(localState, 1)) {
                                    for (Path f : (Iterable<Path>) walk::iterator) {
                                        if (f.equals(localState))
                                            continue;
                                        if (Files.isRegularFile(f)) {
                                            try {
                                                if (!Files.isHidden(f)) {
                                                    long size = Files.size(f);
                                                    deletePermanently(f);
                                                    cleaned += size;
                                                }
                                            } catch (Exception ignored) {
                                            }
                                        }
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return cleaned;
    }

    // ── Discord Cache ────────────────────────────────────────────────────

    private void scanDiscordCache(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String appData = safeEnv("APPDATA");
        if (appData != null) {
            Path discord = Paths.get(appData, "discord");
            List<Path> dirs = new ArrayList<>();
            Path cache = discord.resolve("Cache");
            if (Files.isDirectory(cache))
                dirs.add(cache);
            Path codeCache = discord.resolve("Code Cache");
            if (Files.isDirectory(codeCache))
                dirs.add(codeCache);
            Path gpuCache = discord.resolve("GPUCache");
            if (Files.isDirectory(gpuCache))
                dirs.add(gpuCache);
            for (Path dir : dirs) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanDiscordCache() {
        long cleaned = 0;
        String appData = safeEnv("APPDATA");
        if (appData != null) {
            Path discord = Paths.get(appData, "discord");
            List<Path> dirs = new ArrayList<>();
            Path cache = discord.resolve("Cache");
            if (Files.isDirectory(cache))
                dirs.add(cache);
            Path codeCache = discord.resolve("Code Cache");
            if (Files.isDirectory(codeCache))
                dirs.add(codeCache);
            Path gpuCache = discord.resolve("GPUCache");
            if (Files.isDirectory(gpuCache))
                dirs.add(gpuCache);
            for (Path dir : dirs) {
                if (Files.isDirectory(dir)) {
                    cleaned += deleteDirectoryContents(dir);
                }
            }
        }
        return cleaned;
    }

    // ── VS Code Cache ─────────────────────────────────────────────────────

    private void scanVscodeCache(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String appData = safeEnv("APPDATA");
        if (appData != null) {
            Path code = Paths.get(appData, "Code");
            List<Path> dirs = new ArrayList<>();
            Path cache = code.resolve("Cache");
            if (Files.isDirectory(cache))
                dirs.add(cache);
            Path cachedData = code.resolve("CachedData");
            if (Files.isDirectory(cachedData))
                dirs.add(cachedData);
            Path cachedExtensions = code.resolve("CachedExtensions");
            if (Files.isDirectory(cachedExtensions))
                dirs.add(cachedExtensions);
            for (Path dir : dirs) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanVscodeCache() {
        long cleaned = 0;
        String appData = safeEnv("APPDATA");
        if (appData != null) {
            Path code = Paths.get(appData, "Code");
            List<Path> dirs = new ArrayList<>();
            Path cache = code.resolve("Cache");
            if (Files.isDirectory(cache))
                dirs.add(cache);
            Path cachedData = code.resolve("CachedData");
            if (Files.isDirectory(cachedData))
                dirs.add(cachedData);
            Path cachedExtensions = code.resolve("CachedExtensions");
            if (Files.isDirectory(cachedExtensions))
                dirs.add(cachedExtensions);
            for (Path dir : dirs) {
                if (Files.isDirectory(dir)) {
                    cleaned += deleteDirectoryContents(dir);
                }
            }
        }
        return cleaned;
    }

    // ── Adobe Cache ───────────────────────────────────────────────────────

    private void scanAdobeCache(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String appData = safeEnv("APPDATA");
        String localAppData = safeEnv("LOCALAPPDATA");

        // APPDATA Adobe cache
        if (appData != null) {
            Path adobeCommon = Paths.get(appData, "Adobe", "Common");
            List<Path> dirs = new ArrayList<>();
            Path mediaCache = adobeCommon.resolve("Media Cache");
            if (Files.isDirectory(mediaCache))
                dirs.add(mediaCache);
            Path mediaCacheFiles = adobeCommon.resolve("Media Cache Files");
            if (Files.isDirectory(mediaCacheFiles))
                dirs.add(mediaCacheFiles);
            for (Path dir : dirs) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }

        // LOCALAPPDATA Adobe cache (Camera Raw, etc.)
        if (localAppData != null) {
            Path adobeLocal = Paths.get(localAppData, "Adobe");
            if (Files.isDirectory(adobeLocal)) {
                List<Path> localDirs = new ArrayList<>();
                Path cameraRaw = adobeLocal.resolve("CameraRaw").resolve("Cache");
                if (Files.isDirectory(cameraRaw))
                    localDirs.add(cameraRaw);
                Path cameraRawDb = adobeLocal.resolve("CameraRaw").resolve("CameraRawDatabase");
                if (Files.isDirectory(cameraRawDb))
                    localDirs.add(cameraRawDb);
                Path flashPlayer = adobeLocal.resolve("Flash Player").resolve("SharedAssets");
                if (Files.isDirectory(flashPlayer))
                    localDirs.add(flashPlayer);
                Path colorSync = adobeLocal.resolve("Color").resolve("CachedProfiles");
                if (Files.isDirectory(colorSync))
                    localDirs.add(colorSync);
                for (Path dir : localDirs) {
                    try (Stream<Path> walk = Files.walk(dir)) {
                        var stats = walk.filter(Files::isRegularFile)
                                .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                        totalSize += stats.getSum();
                        itemCount += (int) stats.getCount();
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanAdobeCache() {
        long cleaned = 0;
        String appData = safeEnv("APPDATA");
        String localAppData = safeEnv("LOCALAPPDATA");

        // APPDATA Adobe cache
        if (appData != null) {
            Path adobeCommon = Paths.get(appData, "Adobe", "Common");
            List<Path> dirs = new ArrayList<>();
            Path mediaCache = adobeCommon.resolve("Media Cache");
            if (Files.isDirectory(mediaCache))
                dirs.add(mediaCache);
            Path mediaCacheFiles = adobeCommon.resolve("Media Cache Files");
            if (Files.isDirectory(mediaCacheFiles))
                dirs.add(mediaCacheFiles);
            for (Path dir : dirs) {
                if (Files.isDirectory(dir)) {
                    cleaned += deleteDirectoryContents(dir);
                }
            }
        }

        // LOCALAPPDATA Adobe cache
        if (localAppData != null) {
            Path adobeLocal = Paths.get(localAppData, "Adobe");
            if (Files.isDirectory(adobeLocal)) {
                List<Path> localDirs = new ArrayList<>();
                Path cameraRaw = adobeLocal.resolve("CameraRaw").resolve("Cache");
                if (Files.isDirectory(cameraRaw))
                    localDirs.add(cameraRaw);
                Path cameraRawDb = adobeLocal.resolve("CameraRaw").resolve("CameraRawDatabase");
                if (Files.isDirectory(cameraRawDb))
                    localDirs.add(cameraRawDb);
                Path flashPlayer = adobeLocal.resolve("Flash Player").resolve("SharedAssets");
                if (Files.isDirectory(flashPlayer))
                    localDirs.add(flashPlayer);
                Path colorSync = adobeLocal.resolve("Color").resolve("CachedProfiles");
                if (Files.isDirectory(colorSync))
                    localDirs.add(colorSync);
                for (Path dir : localDirs) {
                    if (Files.isDirectory(dir)) {
                        cleaned += deleteDirectoryContents(dir);
                    }
                }
            }
        }

        return cleaned;
    }

    // ── Steam Cache ───────────────────────────────────────────────────────

    private void scanSteamCache(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        Path steamDir = findSteamDir();
        if (steamDir != null) {
            List<Path> dirs = new ArrayList<>();
            Path appcache = steamDir.resolve("appcache");
            if (Files.isDirectory(appcache))
                dirs.add(appcache);
            Path logs = steamDir.resolve("logs");
            if (Files.isDirectory(logs))
                dirs.add(logs);
            Path downloading = steamDir.resolve("steamapps").resolve("downloading");
            if (Files.isDirectory(downloading))
                dirs.add(downloading);
            for (Path dir : dirs) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanSteamCache() {
        long cleaned = 0;
        Path steamDir = findSteamDir();
        if (steamDir != null) {
            List<Path> dirs = new ArrayList<>();
            Path appcache = steamDir.resolve("appcache");
            if (Files.isDirectory(appcache))
                dirs.add(appcache);
            Path logs = steamDir.resolve("logs");
            if (Files.isDirectory(logs))
                dirs.add(logs);
            Path downloading = steamDir.resolve("steamapps").resolve("downloading");
            if (Files.isDirectory(downloading))
                dirs.add(downloading);
            for (Path dir : dirs) {
                if (Files.isDirectory(dir)) {
                    cleaned += deleteDirectoryContents(dir);
                }
            }
        }
        return cleaned;
    }

    private Path findSteamDir() {
        String progFilesX86 = safeEnv("PROGRAMFILES(X86)");
        if (progFilesX86 != null) {
            Path steam = Paths.get(progFilesX86, "Steam");
            if (Files.isDirectory(steam))
                return steam;
        }
        String progFiles = safeEnv("PROGRAMFILES");
        if (progFiles != null) {
            Path steam = Paths.get(progFiles, "Steam");
            if (Files.isDirectory(steam))
                return steam;
        }
        return null;
    }

    // ── Slack Cache ───────────────────────────────────────────────────────

    private void scanSlackCache(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String appData = safeEnv("APPDATA");
        if (appData != null) {
            Path slack = Paths.get(appData, "Slack");
            List<Path> dirs = new ArrayList<>();
            Path cache = slack.resolve("Cache");
            if (Files.isDirectory(cache))
                dirs.add(cache);
            Path codeCache = slack.resolve("Code Cache");
            if (Files.isDirectory(codeCache))
                dirs.add(codeCache);
            Path gpuCache = slack.resolve("GPUCache");
            if (Files.isDirectory(gpuCache))
                dirs.add(gpuCache);
            for (Path dir : dirs) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanSlackCache() {
        long cleaned = 0;
        String appData = safeEnv("APPDATA");
        if (appData != null) {
            Path slack = Paths.get(appData, "Slack");
            List<Path> dirs = new ArrayList<>();
            Path cache = slack.resolve("Cache");
            if (Files.isDirectory(cache))
                dirs.add(cache);
            Path codeCache = slack.resolve("Code Cache");
            if (Files.isDirectory(codeCache))
                dirs.add(codeCache);
            Path gpuCache = slack.resolve("GPUCache");
            if (Files.isDirectory(gpuCache))
                dirs.add(gpuCache);
            for (Path dir : dirs) {
                if (Files.isDirectory(dir)) {
                    cleaned += deleteDirectoryContents(dir);
                }
            }
        }
        return cleaned;
    }

    // ── Zoom Cache ────────────────────────────────────────────────────────

    private void scanZoomCache(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String appData = safeEnv("APPDATA");
        if (appData != null) {
            Path zoomData = Paths.get(appData, "Zoom", "data");
            if (Files.isDirectory(zoomData)) {
                try (Stream<Path> walk = Files.walk(zoomData, 1)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanZoomCache() {
        long cleaned = 0;
        String appData = safeEnv("APPDATA");
        if (appData != null) {
            Path zoomData = Paths.get(appData, "Zoom", "data");
            if (Files.isDirectory(zoomData)) {
                try (Stream<Path> files = Files.list(zoomData)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        if (Files.isRegularFile(f)) {
                            long size = Files.size(f);
                            deletePermanently(f);
                            cleaned += size;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return cleaned;
    }

    // ── Teams Cache ───────────────────────────────────────────────────────

    private void scanTeamsCache(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String appData = safeEnv("APPDATA");
        String localAppData = safeEnv("LOCALAPPDATA");

        // Old Teams path
        List<Path> oldTeamsDirs = getTeamsDirs(appData != null ? Paths.get(appData, "Microsoft", "Teams") : null);
        for (Path dir : oldTeamsDirs) {
            try (Stream<Path> walk = Files.walk(dir)) {
                var stats = walk.filter(Files::isRegularFile)
                        .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                totalSize += stats.getSum();
                itemCount += (int) stats.getCount();
            } catch (Exception ignored) {
            }
        }

        // New Teams (Teams classic) path
        List<Path> newTeamsDirs = getTeamsDirs(
                appData != null ? Paths.get(appData, "Microsoft", "Teams classic") : null);
        for (Path dir : newTeamsDirs) {
            try (Stream<Path> walk = Files.walk(dir)) {
                var stats = walk.filter(Files::isRegularFile)
                        .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                totalSize += stats.getSum();
                itemCount += (int) stats.getCount();
            } catch (Exception ignored) {
            }
        }

        // New Teams (Microsoft Teams) in Packages
        if (localAppData != null) {
            Path teamsPackage = Paths.get(localAppData, "Packages");
            if (Files.isDirectory(teamsPackage)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(teamsPackage)) {
                    for (Path pkg : ds) {
                        String pkgName = pkg.getFileName().toString();
                        if (pkgName.contains("MicrosoftTeams") || pkgName.contains("MSTeams")) {
                            Path ac = pkg.resolve("AC");
                            if (Files.isDirectory(ac)) {
                                try (Stream<Path> walk = Files.walk(ac)) {
                                    var stats = walk.filter(Files::isRegularFile)
                                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                                    totalSize += stats.getSum();
                                    itemCount += (int) stats.getCount();
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private List<Path> getTeamsDirs(Path teamsBase) {
        List<Path> dirs = new ArrayList<>();
        if (teamsBase != null && Files.isDirectory(teamsBase)) {
            Path cache = teamsBase.resolve("Cache");
            if (Files.isDirectory(cache))
                dirs.add(cache);
            Path codeCache = teamsBase.resolve("Code Cache");
            if (Files.isDirectory(codeCache))
                dirs.add(codeCache);
            Path appCache = teamsBase.resolve("Application Cache");
            if (Files.isDirectory(appCache))
                dirs.add(appCache);
        }
        return dirs;
    }

    private long cleanTeamsCache() {
        long cleaned = 0;
        String appData = safeEnv("APPDATA");
        String localAppData = safeEnv("LOCALAPPDATA");

        // Old Teams path
        cleaned += cleanTeamsDirs(appData != null ? Paths.get(appData, "Microsoft", "Teams") : null);

        // New Teams (Teams classic) path
        cleaned += cleanTeamsDirs(appData != null ? Paths.get(appData, "Microsoft", "Teams classic") : null);

        // New Teams (Microsoft Teams) in Packages
        if (localAppData != null) {
            Path teamsPackage = Paths.get(localAppData, "Packages");
            if (Files.isDirectory(teamsPackage)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(teamsPackage)) {
                    for (Path pkg : ds) {
                        String pkgName = pkg.getFileName().toString();
                        if (pkgName.contains("MicrosoftTeams") || pkgName.contains("MSTeams")) {
                            Path ac = pkg.resolve("AC");
                            if (Files.isDirectory(ac)) {
                                cleaned += deleteDirectoryContents(ac);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return cleaned;
    }

    private long cleanTeamsDirs(Path teamsBase) {
        long cleaned = 0;
        if (teamsBase != null && Files.isDirectory(teamsBase)) {
            for (String sub : List.of("Cache", "Code Cache", "Application Cache")) {
                Path dir = teamsBase.resolve(sub);
                if (Files.isDirectory(dir)) {
                    cleaned += deleteDirectoryContents(dir);
                }
            }
        }
        return cleaned;
    }

    // ── Other Programs Cache (combined) ───────────────────────────────────

    private void scanOtherProgramsCache(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;

        CleanupRow temp = row;
        scanDiscordCache(temp);
        totalSize += temp.getTotalBytes();
        itemCount += temp.getItemCount();

        scanVscodeCache(temp);
        totalSize += temp.getTotalBytes();
        itemCount += temp.getItemCount();

        scanAdobeCache(temp);
        totalSize += temp.getTotalBytes();
        itemCount += temp.getItemCount();

        scanSteamCache(temp);
        totalSize += temp.getTotalBytes();
        itemCount += temp.getItemCount();

        scanSlackCache(temp);
        totalSize += temp.getTotalBytes();
        itemCount += temp.getItemCount();

        scanZoomCache(temp);
        totalSize += temp.getTotalBytes();
        itemCount += temp.getItemCount();

        scanTeamsCache(temp);
        totalSize += temp.getTotalBytes();
        itemCount += temp.getItemCount();

        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanOtherProgramsCache() {
        long cleaned = 0;
        cleaned += cleanDiscordCache();
        cleaned += cleanVscodeCache();
        cleaned += cleanAdobeCache();
        cleaned += cleanSteamCache();
        cleaned += cleanSlackCache();
        cleaned += cleanZoomCache();
        cleaned += cleanTeamsCache();
        return cleaned;
    }

    // ── NVIDIA/AMD Shader Cache ──────────────────────────────────────────

    private void scanShaderCache(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        List<Path> dirs = new ArrayList<>();
        String localAppData = safeEnv("LOCALAPPDATA");

        if (localAppData != null) {
            addPath(dirs, localAppData + "\\NVIDIA\\DXCache");
            addPath(dirs, localAppData + "\\NVIDIA\\GLCache");
            addPath(dirs, localAppData + "\\NVIDIA\\NvShaderCache");
            addPath(dirs, localAppData + "\\AMD\\D3DSCache");
            addPath(dirs, localAppData + "\\AMD\\VkCache");
        }

        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanShaderCache() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        String localAppData = safeEnv("LOCALAPPDATA");

        if (localAppData != null) {
            addPath(dirs, localAppData + "\\NVIDIA\\DXCache");
            addPath(dirs, localAppData + "\\NVIDIA\\GLCache");
            addPath(dirs, localAppData + "\\NVIDIA\\NvShaderCache");
            addPath(dirs, localAppData + "\\AMD\\D3DSCache");
            addPath(dirs, localAppData + "\\AMD\\VkCache");
        }

        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                cleaned += deleteDirectoryContents(dir);
            }
        }
        return cleaned;
    }

    // ── Software Distribution Cache ──────────────────────────────────────

    private void scanSoftwareDistributionCache(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String windir = safeEnv("WINDIR");
        if (windir != null) {
            List<Path> dirs = new ArrayList<>();
            addPath(dirs, windir + "\\SoftwareDistribution\\DataStore");
            addPath(dirs, windir + "\\SoftwareDistribution\\DataStore\\Logs");

            for (Path dir : dirs) {
                if (dir != null && Files.isDirectory(dir)) {
                    try (Stream<Path> walk = Files.walk(dir)) {
                        var stats = walk.filter(Files::isRegularFile)
                                .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                        totalSize += stats.getSum();
                        itemCount += (int) stats.getCount();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanSoftwareDistributionCache() {
        long cleaned = 0;
        String windir = safeEnv("WINDIR");
        if (windir != null) {
            List<Path> dirs = new ArrayList<>();
            addPath(dirs, windir + "\\SoftwareDistribution\\DataStore");
            addPath(dirs, windir + "\\SoftwareDistribution\\DataStore\\Logs");

            for (Path dir : dirs) {
                if (dir != null && Files.isDirectory(dir)) {
                    cleaned += deleteDirectoryContents(dir);
                }
            }
        }
        return cleaned;
    }

    // ── Diagnostics Cache ────────────────────────────────────────────────

    private void scanDiagnosticsCache(CleanupRow row) {
        long totalSize = 0;
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "Windows", "Diagnosis");
        addEnvPath(dirs, "PROGRAMDATA", "Microsoft", "Windows", "Diagnosis");
        addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "Windows", "PowerShell", "Diagnosis");
        scanDirectorySizes(row, dirs, 4);
    }

    private long cleanDiagnosticsCache() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "Windows", "Diagnosis");
        addEnvPath(dirs, "PROGRAMDATA", "Microsoft", "Windows", "Diagnosis");
        addEnvPath(dirs, "LOCALAPPDATA", "Microsoft", "Windows", "PowerShell", "Diagnosis");

        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                cleaned += deleteDirectoryContents(dir);
            }
        }
        return cleaned;
    }

    // ── Previous Windows Installation ────────────────────────────────────

    private void scanOldWindowsInstall(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String sysdrive = safeEnv("SYSTEMDRIVE");
        if (sysdrive != null) {
            Path windowsOld = Paths.get(sysdrive, "Windows.old");
            if (Files.isDirectory(windowsOld)) {
                try (Stream<Path> walk = Files.walk(windowsOld)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        if (totalSize > 0) {
            row.setSizeOrCountText(formatBytes(totalSize) + " (" + itemCount + " files)");
        } else {
            row.setSizeOrCountText("Not found");
        }
    }

    private long cleanOldWindowsInstall() {
        long cleaned = 0;
        String sysdrive = safeEnv("SYSTEMDRIVE");
        if (sysdrive != null) {
            Path windowsOld = Paths.get(sysdrive, "Windows.old");
            if (Files.isDirectory(windowsOld)) {
                try (Stream<Path> walk = Files.walk(windowsOld)) {
                    cleaned = walk.filter(Files::isRegularFile)
                            .mapToLong(p -> p.toFile().length())
                            .sum();
                } catch (Exception ignored) {
                }

                try {
                    ProcessBuilder pbTakeown = new ProcessBuilder("cmd", "/c",
                            "takeown /F \"" + windowsOld.toString() + "\\*\" /R /A");
                    pbTakeown.redirectErrorStream(true);
                    Process pTakeown = ProcessManager.start(pbTakeown);
                    pTakeown.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);

                    ProcessBuilder pbIcacls = new ProcessBuilder("cmd", "/c",
                            "icacls \"" + windowsOld.toString() + "\\*\" /grant administrators:F /T");
                    pbIcacls.redirectErrorStream(true);
                    Process pIcacls = ProcessManager.start(pbIcacls);
                    pIcacls.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);

                    ProcessBuilder pb = new ProcessBuilder("cmd", "/c",
                            "rd", "/s", "/q", windowsOld.toString());
                    pb.redirectErrorStream(true);
                    Process p = ProcessManager.start(pb);
                    boolean finished = p.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
                    if (!finished) {
                        p.destroyForcibly();
                        AppLogger.warning("Windows.old deletion timed out");
                    }
                } catch (Exception ex) {
                    AppLogger.warning("Failed to remove Windows.old: " + ex.getMessage());
                }

                if (cleaned == 0 && !Files.isDirectory(windowsOld)) {
                    cleaned = 1;
                }
            }
        }
        return cleaned;
    }

    // ── Shared helpers ────────────────────────────────────────────────────

    private static String safeEnv(String name) {
        String val = System.getenv(name);
        return (val != null && !val.isBlank()) ? val : null;
    }

    private static Path safeEnvPath(String envName, String... subPath) {
        String base = safeEnv(envName);
        if (base == null)
            return null;
        if (subPath.length == 0)
            return Paths.get(base);
        return Paths.get(base, subPath);
    }

    private void addEnvPath(List<Path> list, String envName, String... subPath) {
        Path p = safeEnvPath(envName, subPath);
        if (p != null && Files.exists(p)) {
            list.add(p);
        }
    }

    private void addPath(List<Path> list, String pathStr) {
        if (pathStr != null && !pathStr.isBlank() && !pathStr.startsWith("null")) {
            Path p = Paths.get(pathStr);
            if (Files.exists(p)) {
                list.add(p);
            }
        }
    }

    private void scanDirectorySizes(CleanupRow row, List<Path> dirs) {
        scanDirectorySizes(row, dirs, -1);
    }

    private void scanDirectorySizesOlderThan(CleanupRow row, List<Path> dirs, java.time.Duration maxAge) {
        long totalSize = 0;
        int itemCount = 0;
        long cutoff = System.currentTimeMillis() - maxAge.toMillis();
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                try {
                                    if (Files.isHidden(p))
                                        return false;
                                    long lastModified = p.toFile().lastModified();
                                    return lastModified <= 0 || lastModified < cutoff;
                                } catch (Exception e) {
                                    return true;
                                }
                            })
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private void scanDirectorySizes(CleanupRow row, List<Path> dirs, int maxDepth) {
        long totalSize = 0;
        int itemCount = 0;
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> walk = maxDepth > 0 ? Files.walk(dir, maxDepth) : Files.walk(dir)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                try {
                                    return !Files.isHidden(p);
                                } catch (Exception e) {
                                    return true;
                                }
                            })
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    private long cleanDirectoryPattern(List<Path> dirs) {
        long cleaned = 0;
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                cleaned += deleteDirectoryContents(dir);
            }
        }
        return cleaned;
    }

    private long cleanDirectoryPatternOlderThan(List<Path> dirs, java.time.Duration maxAge) {
        long cleaned = 0;
        long cutoff = System.currentTimeMillis() - maxAge.toMillis();
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                cleaned += deleteDirectoryContentsOlderThan(dir, cutoff);
            }
        }
        return cleaned;
    }

    private long deleteDirectoryContentsOlderThan(Path dir, long cutoffMillis) {
        long cleaned = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> sorted = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path f : sorted) {
                if (f.equals(dir))
                    continue;
                try {
                    long lastModified = f.toFile().lastModified();
                    if (lastModified > 0 && lastModified >= cutoffMillis)
                        continue;
                    if (Files.isRegularFile(f) || Files.isSymbolicLink(f)) {
                        long size = Files.size(f);
                        deletePermanently(f);
                        cleaned += size;
                    } else if (Files.isDirectory(f)) {
                        deletePermanently(f);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return cleaned;
    }

    private long deleteDirectoryContents(Path dir) {
        long cleaned = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> sorted = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path f : sorted) {
                if (f.equals(dir))
                    continue;
                try {
                    if (Files.isRegularFile(f) || Files.isSymbolicLink(f)) {
                        long size = Files.size(f);
                        deletePermanently(f);
                        cleaned += size;
                    } else if (Files.isDirectory(f)) {
                        deletePermanently(f);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return cleaned;
    }

    private void deletePermanently(Path source) {
        try {
            Files.deleteIfExists(source);
        } catch (IOException e) {
            AppLogger.warning("Could not delete " + source + ": " + e.getMessage());
            try {
                Thread.sleep(100);
                Files.deleteIfExists(source);
            } catch (Exception ignored) {
            }
        }
    }

    public static String formatBytes(long bytes) {
        return FormatUtils.formatBytes(bytes);
    }
}
