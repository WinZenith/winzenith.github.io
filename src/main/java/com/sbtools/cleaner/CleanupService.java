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
    private static final long CLEAN_OVERALL_TIMEOUT_SECONDS = 960;

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
        ExecutorService executor = AppExecutors.cleanPool();
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

    public List<CleanupRow> scan(Runnable onProgress, ExecutorService sharedExecutor, CancellationToken token) {
        CleanupCategory[] categories = CleanupCategory.values();
        CleanupRow[] rows = new CleanupRow[categories.length];
        for (int i = 0; i < categories.length; i++) {
            rows[i] = new CleanupRow(categories[i]);
        }
        return scanWithExecutor(rows, categories, onProgress, sharedExecutor, token != null ? token : CancellationToken.NONE);
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
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof java.util.concurrent.TimeoutException || ex instanceof java.util.concurrent.TimeoutException) {
                    row.setSizeOrCountText("Timed out");
                    row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                    row.setErrorMessage("Scan timed out");
                } else {
                    AppLogger.warning("Scan task failed for " + row.getCategory().getDisplayName() + ": " + cause.getMessage());
                }
                return null;
            });
        }
        try {
            CompletableFuture.allOf(futures)
                    .orTimeout(SCAN_OVERALL_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                    .join();
        } catch (java.util.concurrent.CompletionException ce) {
            Throwable cause = ce.getCause();
            if (cause instanceof java.util.concurrent.TimeoutException) {
                AppLogger.warning("Synchronous scan timed out after " + SCAN_OVERALL_TIMEOUT_SECONDS + "s");
                for (CleanupRow r : rows) {
                    if (r.getScanStatus() == CleanupRow.ScanStatus.PENDING || r.getScanStatus() == CleanupRow.ScanStatus.SCANNING) {
                        r.setSizeOrCountText("Timed out");
                        r.setScanStatus(CleanupRow.ScanStatus.ERROR);
                        r.setErrorMessage("Scan timed out");
                    }
                }
            } else {
                AppLogger.warning("Synchronous scan failed: " + (cause != null ? cause.getMessage() : ce.getMessage()));
            }
        } catch (java.util.concurrent.CancellationException ce) {
            AppLogger.warning("Synchronous scan canceled: " + ce.getMessage());
        } catch (Exception e) {
            AppLogger.warning("Synchronous scan failed: " + e.getMessage());
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
                long cleaned = cleanCategory(row.getCategory(), registryBackup ? backupRoot : null, token);
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

        ExecutorService executor = AppExecutors.cleanPool();
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
        finalFuture.whenComplete((r, ex) -> {
            if (ex != null) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof java.util.concurrent.TimeoutException) {
                    if (token != null) token.cancel();
                    for (CompletableFuture<?> f : futures) { try { f.cancel(true); } catch (Exception ignored) {} }
                    for (CleanupRow row : rows) {
                        if (row.getScanStatus() == CleanupRow.ScanStatus.PENDING || row.getScanStatus() == CleanupRow.ScanStatus.SCANNING) {
                            row.setSizeOrCountText("Timed out");
                            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                            row.setErrorMessage("Scan timed out");
                        }
                    }
                }
            }
        });

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

        ExecutorService executor = AppExecutors.cleanPool();
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
        finalFuture.whenComplete((r, ex) -> {
            if (ex != null) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof java.util.concurrent.TimeoutException) {
                    if (token != null) token.cancel();
                    for (CompletableFuture<?> f : futures) { try { f.cancel(true); } catch (Exception ignored) {} }
                    for (CleanupRow row : rows) {
                        if (row.getScanStatus() == CleanupRow.ScanStatus.PENDING || row.getScanStatus() == CleanupRow.ScanStatus.SCANNING) {
                            row.setSizeOrCountText("Timed out");
                            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                            row.setErrorMessage("Rescan timed out");
                        }
                    }
                }
            }
        });

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
        java.util.Map<Integer, String> taskErrorMap = new java.util.concurrent.ConcurrentHashMap<>();
        for (int idx = 0; idx < tasks.size(); idx++) {
            final int taskIdx = idx;
            final CleanupRow taskRow = tasks.get(idx);
            CompletableFuture<Long> f = CompletableFuture.supplyAsync(() -> {
                if (token.isCancelled()) {
                    if (onProgress != null) onProgress.run();
                    return 0L;
                }
                try {
                    return cleanCategory(taskRow.getCategory(), backupRoot, token);
                } catch (java.util.concurrent.CancellationException ce) {
                    // Cooperative cancel — not an error
                    return 0L;
                } catch (Exception e) {
                    String msg = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.toString();
                    String err = taskRow.getCategory().getDisplayName() + ": " + msg;
                    taskErrorMap.put(taskIdx, err);
                    AppLogger.warning("Clean failed for " + err);
                    return 0L;
                } finally {
                    if (onProgress != null) onProgress.run();
                }
            }, executor);
            futures.add(f);
        }

        long effectiveTimeoutTmp = CLEAN_OVERALL_TIMEOUT_SECONDS;
        for (CleanupRow tr : tasks) {
            try {
                CleanerExtension ext = CleanerRegistry.get(tr.getCategory());
                if (ext != null) effectiveTimeoutTmp = Math.max(effectiveTimeoutTmp, ext.getCleanTimeoutSeconds());
            } catch (Exception ignored) {}
        }
        final long effectiveTimeout = effectiveTimeoutTmp;
        CompletableFuture<CleanSummary> finalFuture = CompletableFuture
                .allOf(futures.toArray(new CompletableFuture[0]))
                .orTimeout(effectiveTimeout, java.util.concurrent.TimeUnit.SECONDS)
                .thenApply(v -> {
                    long totalBytes = 0;
                    int totalItems = 0;
                    java.util.Map<CleanupCategory, Long> perCategory = new java.util.HashMap<>();
                    java.util.List<String> errors = new java.util.ArrayList<>();
                    boolean wasCanceled = token.isCancelled();
                    for (int i = 0; i < tasks.size(); i++) {
                        long cleaned = 0;
                        try {
                            cleaned = futures.get(i).join();
                        } catch (java.util.concurrent.CompletionException ce) {
                            String msg = ce.getCause() != null ? ce.getCause().getMessage() : ce.getMessage();
                            errors.add(tasks.get(i).getCategory().getDisplayName() + ": " + (msg != null ? msg : "unknown error"));
                            continue;
                        }
                        CleanupRow r = tasks.get(i);
                        String taskErr = taskErrorMap.get(i);
                        if (taskErr != null) {
                            errors.add(taskErr);
                        } else if (cleaned == 0 && r.getTotalBytes() > 0 && !wasCanceled) {
                            // Only generic if no specific error captured
                            errors.add(r.getCategory().getDisplayName() + ": nothing was cleaned (files may be locked or in use)");
                        }
                        totalBytes += cleaned;
                        int scannedItems = r.getItemCount();
                        long scannedBytes = r.getTotalBytes();
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
        finalFuture.whenComplete((r, ex) -> {
            if (ex != null) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof java.util.concurrent.TimeoutException || ex instanceof java.util.concurrent.TimeoutException) {
                    if (token != null) token.cancel();
                    for (CompletableFuture<Long> f : futures) { try { f.cancel(true); } catch (Exception ignored) {} }
                    AppLogger.warning("Clean timed out after " + effectiveTimeout + "s for " + tasks.size() + " categories");
                }
            }
        });

        CancelableCompletableFuture<CleanSummary> result = new CancelableCompletableFuture<>(
                new java.util.ArrayList<>(futures), executor, false);
        result.completeFrom(finalFuture);
        return result;
    }

    private long cleanCategory(CleanupCategory category, Path backupRootOrNull) throws Exception {
        return cleanCategory(category, backupRootOrNull, CancellationToken.NONE);
    }

    private long cleanCategory(CleanupCategory category, Path backupRootOrNull, CancellationToken token) throws Exception {
        if (token != null && token.isCancelled()) {
            throw new java.util.concurrent.CancellationException("Clean canceled");
        }
        CleanerExtension c = CleanerRegistry.get(category);
        if (c != null) return c.clean(backupRootOrNull, token);
        throw new UnsupportedOperationException("No cleaner registered for " + category);
    }

    public static String formatBytes(long bytes) {
        return com.sbtools.util.FormatUtils.formatBytes(bytes);
    }
}
