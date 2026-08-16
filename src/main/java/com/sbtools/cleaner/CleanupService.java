package com.sbtools.cleaner;

import com.sbtools.util.AppExecutors;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.CancelableCompletableFuture;
import com.sbtools.util.CancellationToken;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class CleanupService {

    private static final long SCAN_OVERALL_TIMEOUT_SECONDS = 300;
    private static final long CLEAN_OVERALL_TIMEOUT_SECONDS = 600;

    public static final class CleanSummary {
        private final long totalBytes;
        private final int totalItems;
        private final Map<CleanupCategory, Long> perCategory;
        private final List<String> errors;

        public CleanSummary(long totalBytes, int totalItems, Map<CleanupCategory, Long> perCategory, List<String> errors) {
            this.totalBytes = totalBytes;
            this.totalItems = totalItems;
            this.perCategory = perCategory;
            this.errors = errors != null ? errors : java.util.Collections.emptyList();
        }

        public CleanSummary(long totalBytes, int totalItems, Map<CleanupCategory, Long> perCategory) {
            this(totalBytes, totalItems, perCategory, java.util.Collections.emptyList());
        }

        public long getTotalBytes() { return totalBytes; }
        public int getTotalItems() { return totalItems; }
        public Map<CleanupCategory, Long> getPerCategory() { return perCategory; }
        public List<String> getErrors() { return errors; }
        public boolean hasErrors() { return !errors.isEmpty(); }
    }

    public CleanupService() {
    }

    public List<CleanupRow> scan(Runnable onProgress) {
        CleanupCategory[] categories = CleanupCategory.values();
        CleanupRow[] rows = new CleanupRow[categories.length];
        for (int i = 0; i < categories.length; i++) {
            rows[i] = new CleanupRow(categories[i]);
        }
        ExecutorService executor = AppExecutors.scanPool();
        return scanWithExecutor(rows, categories, onProgress, executor, CancellationToken.NONE);
    }

    public List<CleanupRow> scan(Runnable onProgress, ExecutorService sharedExecutor) {
        CleanupCategory[] categories = CleanupCategory.values();
        CleanupRow[] rows = new CleanupRow[categories.length];
        for (int i = 0; i < categories.length; i++) {
            rows[i] = new CleanupRow(categories[i]);
        }
        return scanWithExecutor(rows, categories, onProgress, sharedExecutor, CancellationToken.NONE);
    }

    private List<CleanupRow> scanWithExecutor(CleanupRow[] rows, CleanupCategory[] categories,
                                               Runnable onProgress, ExecutorService executor, CancellationToken token) {
        CompletableFuture<?>[] futures = new CompletableFuture[categories.length];
        for (int i = 0; i < categories.length; i++) {
            final CleanupRow row = rows[i];
            futures[i] = CompletableFuture.runAsync(() -> {
                if (token.isCancelled()) {
                    row.setSizeOrCountText("Canceled");
                    row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                    row.setErrorMessage("Scan canceled");
                    return;
                }
                scanCategory(row);
                if (onProgress != null) onProgress.run();
            }, executor).exceptionally(ex -> {
                if (ex instanceof java.util.concurrent.TimeoutException) {
                    row.setSizeOrCountText("Timed out");
                    row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                    row.setErrorMessage("Scan timed out");
                }
                return null;
            });
        }
        try {
            CompletableFuture.allOf(futures).join();
        } catch (Exception e) {
            AppLogger.warning("Synchronous scan timed out or failed: " + e.getMessage());
        }
        return List.of(rows);
    }

    private void scanCategory(CleanupRow row) {
        try {
            CleanerExtension c = CleanerRegistry.get(row.getCategory());
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
        return clean(selectedRows, registryBackup, onProgress, CancellationToken.NONE);
    }

    public CleanSummary clean(List<CleanupRow> selectedRows, boolean registryBackup, Runnable onProgress, CancellationToken token) {
        long totalBytes = 0;
        int totalItems = 0;
        Map<CleanupCategory, Long> perCategory = new HashMap<>();
        List<String> errors = new java.util.ArrayList<>();

        Path backupRoot = null;
        if (registryBackup) {
            backupRoot = AppPaths.backupsRoot().resolve("cleanup-backups")
                    .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        }

        for (CleanupRow row : selectedRows) {
            if (!row.isSelected()) continue;
            if (token.isCancelled()) {
                errors.add(row.getCategory().getDisplayName() + ": canceled by user");
                continue;
            }
            try {
                long scannedBytes = row.getTotalBytes();
                int scannedItems = row.getItemCount();
                long cleaned = cleanCategory(row.getCategory(), registryBackup ? backupRoot : null);
                totalBytes += cleaned;
                if (cleaned == 0) {
                    totalItems += 0;
                } else if (scannedBytes > 0 && scannedItems > 0 && cleaned < scannedBytes) {
                    totalItems += (int) Math.round(scannedItems * ((double) cleaned / scannedBytes));
                } else {
                    totalItems += scannedItems;
                }
                perCategory.put(row.getCategory(), cleaned);
                if (onProgress != null) onProgress.run();
            } catch (Exception e) {
                String errorMsg = row.getCategory().getDisplayName() + ": " + e.getMessage();
                errors.add(errorMsg);
                AppLogger.warning("Clean failed for " + errorMsg);
            }
        }
        return new CleanSummary(totalBytes, totalItems, perCategory, errors);
    }

    public CancelableCompletableFuture<java.util.List<CleanupRow>> scanAsync(Runnable onProgress) {
        return scanAsync(onProgress, CancellationToken.NONE);
    }

    public CancelableCompletableFuture<java.util.List<CleanupRow>> scanAsync(Runnable onProgress, CancellationToken token) {
        CleanupCategory[] categories = CleanupCategory.values();
        CleanupRow[] rows = new CleanupRow[categories.length];
        for (int i = 0; i < categories.length; i++) {
            rows[i] = new CleanupRow(categories[i]);
        }

        ExecutorService executor = AppExecutors.scanPool();
        java.util.List<CompletableFuture<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < categories.length; i++) {
            final CleanupRow row = rows[i];
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                long startMs = System.currentTimeMillis();
                try {
                    if (token.isCancelled()) {
                        row.setSizeOrCountText("Canceled");
                        row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                        row.setErrorMessage("Scan canceled by user");
                        return;
                    }
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
                    if (onProgress != null) onProgress.run();
                }
            }, executor);

            futures.add(f);
        }

        CompletableFuture<java.util.List<CleanupRow>> finalFuture = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(SCAN_OVERALL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .thenApply(v -> java.util.List.of(rows));
        finalFuture.whenComplete((r, ex) -> {});

        CancelableCompletableFuture<java.util.List<CleanupRow>> result = new CancelableCompletableFuture<>(
                futures, executor, false);
        result.completeFrom(finalFuture);
        return result;
    }

    public CancelableCompletableFuture<java.util.List<CleanupRow>> scanCategoriesAsync(
            java.util.List<CleanupCategory> categories, Runnable onProgress) {
        return scanCategoriesAsync(categories, onProgress, CancellationToken.NONE);
    }

    public CancelableCompletableFuture<java.util.List<CleanupRow>> scanCategoriesAsync(
            java.util.List<CleanupCategory> categories, Runnable onProgress, CancellationToken token) {
        CleanupRow[] rows = new CleanupRow[categories.size()];
        for (int i = 0; i < categories.size(); i++) {
            rows[i] = new CleanupRow(categories.get(i));
        }

        ExecutorService executor = AppExecutors.scanPool();
        java.util.List<CompletableFuture<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < categories.size(); i++) {
            final CleanupRow row = rows[i];
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                long startMs = System.currentTimeMillis();
                try {
                    if (token.isCancelled()) {
                        row.setSizeOrCountText("Canceled");
                        row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                        row.setErrorMessage("Scan canceled by user");
                        return;
                    }
                    scanCategory(row);
                } catch (Exception e) {
                    AppLogger.warning("Rescan failed for " + row.getCategory().getDisplayName() + ": " + e.getMessage());
                    row.setSizeOrCountText("Error");
                    row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                    row.setErrorMessage(e.getMessage());
                } finally {
                    row.setScanDurationMs(System.currentTimeMillis() - startMs);
                    if (row.getScanStatus() != CleanupRow.ScanStatus.ERROR) {
                        row.setScanStatus(CleanupRow.ScanStatus.DONE);
                    }
                    if (onProgress != null) onProgress.run();
                }
            }, executor);

            futures.add(f);
        }

        CompletableFuture<java.util.List<CleanupRow>> finalFuture = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(SCAN_OVERALL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .thenApply(v -> java.util.List.of(rows));
        finalFuture.whenComplete((r, ex) -> {});

        CancelableCompletableFuture<java.util.List<CleanupRow>> result = new CancelableCompletableFuture<>(
                futures, executor, false);
        result.completeFrom(finalFuture);
        return result;
    }

    public CancelableCompletableFuture<CleanSummary> cleanAsync(java.util.List<CleanupRow> selectedRows,
            boolean registryBackup, Runnable onProgress) {
        return cleanAsync(selectedRows, registryBackup, onProgress, CancellationToken.NONE);
    }

    public CancelableCompletableFuture<CleanSummary> cleanAsync(java.util.List<CleanupRow> selectedRows,
            boolean registryBackup, Runnable onProgress, CancellationToken token) {
        java.util.List<CleanupRow> tasks = selectedRows.stream().filter(CleanupRow::isSelected).toList();
        if (tasks.isEmpty()) {
            CompletableFuture<CleanSummary> done = CompletableFuture.completedFuture(new CleanSummary(0, 0, new java.util.HashMap<>()));
            CancelableCompletableFuture<CleanSummary> cf = new CancelableCompletableFuture<>(java.util.Collections.emptyList(), null);
            cf.completeFrom(done);
            return cf;
        }

        final Path backupRoot = registryBackup
                ? AppPaths.backupsRoot().resolve("cleanup-backups")
                        .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
                : null;

        ExecutorService executor = AppExecutors.cleanPool();
        java.util.List<CompletableFuture<Long>> futures = new java.util.ArrayList<>();
        for (CleanupRow row : tasks) {
            final CleanupRow taskRow = row;
            CompletableFuture<Long> f = CompletableFuture.supplyAsync(() -> {
                if (token.isCancelled()) {
                    return 0L;
                }
                try {
                    return cleanCategory(taskRow.getCategory(), backupRoot);
                } catch (Exception e) {
                    AppLogger.warning("Clean failed for " + taskRow.getCategory().getDisplayName() + ": " + e.getMessage());
                    return 0L;
                } finally {
                    if (onProgress != null) onProgress.run();
                }
            }, executor);
            futures.add(f);
        }

        CompletableFuture<CleanSummary> finalFuture = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(CLEAN_OVERALL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                .thenApply(v -> {
                    long totalBytes = 0;
                    int totalItems = 0;
                    java.util.Map<CleanupCategory, Long> perCategory = new java.util.HashMap<>();
                    java.util.List<String> errors = new java.util.ArrayList<>();
                    boolean wasCanceled = token.isCancelled();
                    for (int i = 0; i < tasks.size(); i++) {
                        long cleaned = futures.get(i).join();
                        CleanupRow r = tasks.get(i);
                        totalBytes += cleaned;
                        int scannedItems = r.getItemCount();
                        long scannedBytes = r.getTotalBytes();
                        if (cleaned == 0 && scannedBytes > 0 && !wasCanceled) {
                            errors.add(r.getCategory().getDisplayName() + ": nothing was cleaned (files may be locked or in use)");
                        }
                        if (cleaned == 0) {
                            totalItems += 0;
                        } else if (scannedBytes > 0 && scannedItems > 0 && cleaned < scannedBytes) {
                            totalItems += (int) Math.round(scannedItems * ((double) cleaned / scannedBytes));
                        } else {
                            totalItems += scannedItems;
                        }
                        perCategory.put(r.getCategory(), cleaned);
                    }
                    return new CleanSummary(totalBytes, totalItems, perCategory, errors);
                });
        finalFuture.whenComplete((r, ex) -> {});

        CancelableCompletableFuture<CleanSummary> result = new CancelableCompletableFuture<>(
                new java.util.ArrayList<>(futures), executor, false);
        result.completeFrom(finalFuture);
        return result;
    }

    private long cleanCategory(CleanupCategory category, Path backupRootOrNull) throws Exception {
        CleanerExtension c = CleanerRegistry.get(category);
        if (c != null) return c.clean(backupRootOrNull);
        throw new UnsupportedOperationException("No cleaner registered for " + category);
    }

    public static String formatBytes(long bytes) {
        return com.sbtools.util.FormatUtils.formatBytes(bytes);
    }
}
