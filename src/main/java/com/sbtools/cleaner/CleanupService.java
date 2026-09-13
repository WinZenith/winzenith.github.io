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
                scanCategory(row, token);
                if (onProgress != null) onProgress.run();
            }, executor).exceptionally(ex -> {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof java.util.concurrent.TimeoutException || ex instanceof java.util.concurrent.TimeoutException) {
                    row.setSizeOrCountText("Timed out");
                    row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                    row.setErrorMessage("Scan timed out");
                } else {
                    AppLogger.warning("Scan task failed for " + row.getCategory().getDisplayName() + ": " + cause.getMessage());
                    row.setSizeOrCountText("Error");
                    row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                    row.setErrorMessage(cause.getMessage() != null && !cause.getMessage().isBlank() ? cause.getMessage() : cause.toString());
                }
                return null;
            });
        }
        // Interruptible outer wait: CompletableFuture.join() ignores thread
        // interrupts, which previously left Dashboard Stop / per-task timeouts
        // stuck until the 300s budget. Poll instead so cancel(true) unblocks
        // the caller promptly; per-category file walks are cancelled best-effort.
        java.util.concurrent.CompletableFuture<Void> all = CompletableFuture.allOf(futures);
        long deadlineNanos = System.nanoTime()
                + java.util.concurrent.TimeUnit.SECONDS.toNanos(SCAN_OVERALL_TIMEOUT_SECONDS);
        boolean timedOut = false;
        boolean cancelled = false;
        try {
            while (!all.isDone()) {
                if (Thread.currentThread().isInterrupted()) {
                    cancelled = true;
                    Thread.currentThread().interrupt();
                    break;
                }
                if (token != null && token.isCancelled()) {
                    cancelled = true;
                    break;
                }
                if (System.nanoTime() > deadlineNanos) {
                    timedOut = true;
                    break;
                }
                try {
                    all.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    // poll again
                }
            }
        } catch (java.util.concurrent.CancellationException ce) {
            cancelled = true;
            AppLogger.warning("Synchronous scan canceled: " + ce.getMessage());
        } catch (InterruptedException ie) {
            cancelled = true;
            Thread.currentThread().interrupt();
            AppLogger.warning("Synchronous scan interrupted");
        } catch (java.util.concurrent.ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof java.util.concurrent.TimeoutException) {
                timedOut = true;
            } else {
                AppLogger.warning("Synchronous scan failed: "
                        + (cause != null ? cause.getMessage() : ee.getMessage()));
            }
        } catch (Exception e) {
            AppLogger.warning("Synchronous scan failed: " + e.getMessage());
        }
        if (timedOut) {
            AppLogger.warning("Synchronous scan timed out after " + SCAN_OVERALL_TIMEOUT_SECONDS + "s");
            if (token != null) token.cancel();
            for (CompletableFuture<?> f : futures) { try { f.cancel(true); } catch (Exception ignored) {} }
            for (CleanupRow r : rows) {
                if (r.getScanStatus() == CleanupRow.ScanStatus.PENDING || r.getScanStatus() == CleanupRow.ScanStatus.SCANNING) {
                    r.setSizeOrCountText("Timed out");
                    r.setScanStatus(CleanupRow.ScanStatus.ERROR);
                    r.setErrorMessage("Scan timed out");
                }
            }
        } else if (cancelled) {
            if (token != null) token.cancel();
            for (CompletableFuture<?> f : futures) { try { f.cancel(true); } catch (Exception ignored) {} }
            AppLogger.info("Synchronous scan cancelled; partial results kept");
        }
        return List.of(rows);
    }

    private void scanCategory(CleanupRow row) {
        scanCategory(row, CancellationToken.NONE);
    }

    private void scanCategory(CleanupRow row, CancellationToken token) {
        try {
            if (token != null && token.isCancelled()) {
                row.setSizeOrCountText("Canceled");
                row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                row.setErrorMessage("Scan canceled by user");
                return;
            }
            CleanerExtension c = CleanerRegistry.get(row.getCategory());
            if (c != null) {
                c.scan(row, token);
            } else {
                AppLogger.warning("No cleaner registered for " + row.getCategory().getDisplayName());
                row.setSizeOrCountText("Not supported");
            }
            if (token != null && token.isCancelled()
                    && row.getScanStatus() != CleanupRow.ScanStatus.ERROR) {
                row.setSizeOrCountText("Canceled");
                row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                row.setErrorMessage("Scan canceled by user");
                return;
            }
            if (row.getScanStatus() != CleanupRow.ScanStatus.ERROR) {
                row.setScanStatus(CleanupRow.ScanStatus.DONE);
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.toString();
            AppLogger.warning("Scan failed for " + row.getCategory().getDisplayName() + ": " + msg);
            row.setSizeOrCountText("Error");
            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
            row.setErrorMessage(msg);
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
            backupRoot = newUniqueBackupRoot();
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
                    if (scannedBytes == 0 && scannedItems > 0) {
                        totalItems += scannedItems;
                    } else {
                        totalItems += 0;
                    }
                } else if (scannedBytes > 0 && scannedItems > 0 && cleaned < scannedBytes) {
                    totalItems += (int) Math.round(scannedItems * ((double) cleaned / scannedBytes));
                } else {
                    totalItems += scannedItems;
                }
                perCategory.put(row.getCategory(), cleaned);
                if (onProgress != null) onProgress.run();
            } catch (Exception e) {
                String msg = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.toString();
                String errorMsg = row.getCategory().getDisplayName() + ": " + msg;
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

        // Isolated pool per scan (same pattern as Dashboard's cleanup scan):
        // Cancel/timeout can shutdownNow() to interrupt lingering walks
        // without starving unrelated work on the shared clean pool, and the
        // pool is always shut down when this scan settles (no thread leak).
        ExecutorService executor = newScanExecutor("cleanup-scan", 4);
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
                    scanCategory(row, token);
                } catch (Exception e) {
                    String msg = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.toString();
                    AppLogger.warning("Scan failed for " + row.getCategory().getDisplayName() + ": " + msg);
                    row.setSizeOrCountText("Error");
                    row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                    row.setErrorMessage(msg);
                } finally {
                    // Per-row bookkeeping must never fail the whole scan: a
                    // single row's duration/status update or progress callback
                    // throwing (e.g. toolkit teardown) completes only this row.
                    try {
                        long elapsed = System.currentTimeMillis() - startMs;
                        row.setScanDurationMs(elapsed);
                    } catch (Exception ignored) {}
                    try {
                        if (row.getScanStatus() != CleanupRow.ScanStatus.ERROR) {
                            row.setScanStatus(CleanupRow.ScanStatus.DONE);
                        }
                    } catch (Exception ignored) {}
                    try {
                        if (onProgress != null) onProgress.run();
                    } catch (Exception ignored) {}
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
                    try { executor.shutdownNow(); } catch (Exception ignored) {}
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
                futures, executor, true);
        result.completeFrom(finalFuture);
        // No thread leak: the dedicated pool dies with this scan. Cancel()
        // already shutdownNow()s via ownExecutor; normal completion shuts down
        // gracefully here (tasks are done when finalFuture settles).
        result.whenComplete((r, ex) -> {
            try { executor.shutdown(); } catch (Exception ignored) {}
        });
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

        // Isolated pool, same rationale as scanAsync (cancel interrupts only
        // this rescan's workers; the pool is shut down when it settles).
        ExecutorService executor = newScanExecutor("cleanup-rescan",
                Math.min(4, Math.max(1, categories.size())));
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
                    scanCategory(row, token);
                } catch (Exception e) {
                    String msg = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.toString();
                    AppLogger.warning("Rescan failed for " + row.getCategory().getDisplayName() + ": " + msg);
                    row.setSizeOrCountText("Error");
                    row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                    row.setErrorMessage(msg);
                } finally {
                    // Same fail-safe as scanAsync: bookkeeping/progress must
                    // never fail the whole rescan because of one row.
                    try {
                        row.setScanDurationMs(System.currentTimeMillis() - startMs);
                    } catch (Exception ignored) {}
                    try {
                        if (row.getScanStatus() != CleanupRow.ScanStatus.ERROR) {
                            row.setScanStatus(CleanupRow.ScanStatus.DONE);
                        }
                    } catch (Exception ignored) {}
                    try {
                        if (onProgress != null) onProgress.run();
                    } catch (Exception ignored) {}
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
                    try { executor.shutdownNow(); } catch (Exception ignored) {}
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
                futures, executor, true);
        result.completeFrom(finalFuture);
        result.whenComplete((r, ex) -> {
            try { executor.shutdown(); } catch (Exception ignored) {}
        });
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
                ? newUniqueBackupRoot()
                : null;

        ExecutorService executor = newScanExecutor("cleanup-clean", 1);
        // Sequential execution: categories may overlap on disk (%TEMP%, servicing
        // state) and must not run concurrently (DISM vs SoftwareDistribution,
        // concurrent walk+delete double-counts). One worker iterates in order.
        java.util.Map<Integer, Long> cleanedByIndex = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.Map<Integer, String> taskErrorMap = new java.util.concurrent.ConcurrentHashMap<>();

        CompletableFuture<CleanSummary> finalFuture = CompletableFuture.supplyAsync(() -> {
            boolean wasCanceled = false;
            for (int idx = 0; idx < tasks.size(); idx++) {
                final CleanupRow taskRow = tasks.get(idx);
                if (token.isCancelled()) {
                    wasCanceled = true;
                    cleanedByIndex.put(idx, 0L);
                    safeProgress(onProgress);
                    continue;
                }
                try {
                    long cleaned = cleanCategory(taskRow.getCategory(), backupRoot, token);
                    cleanedByIndex.put(idx, cleaned);
                } catch (java.util.concurrent.CancellationException ce) {
                    // Cooperative cancel — not an error
                    wasCanceled = true;
                    cleanedByIndex.put(idx, 0L);
                } catch (Exception e) {
                    String msg = e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.toString();
                    String err = taskRow.getCategory().getDisplayName() + ": " + msg;
                    taskErrorMap.put(idx, err);
                    AppLogger.warning("Clean failed for " + err);
                    cleanedByIndex.put(idx, 0L);
                } finally {
                    safeProgress(onProgress);
                }
            }
            long totalBytes = 0;
            int totalItems = 0;
            java.util.Map<CleanupCategory, Long> perCategory = new java.util.HashMap<>();
            java.util.List<String> errors = new java.util.ArrayList<>();
            boolean canceled = wasCanceled || token.isCancelled();
            for (int i = 0; i < tasks.size(); i++) {
                long cleaned = cleanedByIndex.getOrDefault(i, 0L);
                CleanupRow r = tasks.get(i);
                String taskErr = taskErrorMap.get(i);
                if (taskErr != null) {
                    errors.add(taskErr);
                } else if (cleaned == 0 && r.getTotalBytes() > 0 && !canceled) {
                    // Only generic if no specific error captured
                    errors.add(r.getCategory().getDisplayName() + ": nothing was cleaned (files may be locked or in use)");
                }
                totalBytes += cleaned;
                int scannedItems = r.getItemCount();
                long scannedBytes = r.getTotalBytes();
                if (cleaned == 0) {
                    // Zero-byte categories (registry entries, empty folders) report
                    // item counts with no bytes — credit scanned items on success.
                    if (taskErr == null && !canceled && scannedBytes == 0 && scannedItems > 0) {
                        totalItems += scannedItems;
                    } else {
                        totalItems += 0;
                    }
                } else if (scannedBytes > 0 && scannedItems > 0 && cleaned < scannedBytes) {
                    totalItems += (int) Math.round(scannedItems * ((double) cleaned / scannedBytes));
                } else {
                    totalItems += scannedItems;
                }
                perCategory.put(r.getCategory(), cleaned);
            }
            return new CleanSummary(totalBytes, totalItems, perCategory, errors);
        }, executor);

        long timeoutSumTmp = 0;
        for (CleanupRow tr : tasks) {
            try {
                CleanerExtension ext = CleanerRegistry.get(tr.getCategory());
                timeoutSumTmp += (ext != null ? ext.getCleanTimeoutSeconds() : 120);
            } catch (Exception ignored) { timeoutSumTmp += 120; }
        }
        // Sequential wall-clock ~= sum of per-cleaner budgets; keep a sane cap.
        final long effectiveTimeout = Math.min(Math.max(CLEAN_OVERALL_TIMEOUT_SECONDS, timeoutSumTmp + 60), 5400);
        CompletableFuture<CleanSummary> timedFuture = finalFuture
                .orTimeout(effectiveTimeout, java.util.concurrent.TimeUnit.SECONDS);
        timedFuture.whenComplete((r, ex) -> {
            if (ex != null) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                if (cause instanceof java.util.concurrent.TimeoutException || ex instanceof java.util.concurrent.TimeoutException) {
                    if (token != null) token.cancel();
                    finalFuture.cancel(true);
                    try { executor.shutdownNow(); } catch (Exception ignored) {}
                    AppLogger.warning("Clean timed out after " + effectiveTimeout + "s for " + tasks.size() + " categories");
                }
            }
        });

        CancelableCompletableFuture<CleanSummary> result = new CancelableCompletableFuture<>(
                java.util.List.of(finalFuture, timedFuture), executor, true);
        result.completeFrom(timedFuture);
        result.whenComplete((r, ex) -> {
            try { executor.shutdown(); } catch (Exception ignored) {}
        });
        return result;
    }

    /**
     * Progress callbacks cross into UI code; a throwing callback must never
     * fail the in-flight scan/clean operation.
     */
    private static void safeProgress(Runnable onProgress) {
        if (onProgress == null) return;
        try {
            onProgress.run();
        } catch (Exception ignored) {}
    }

    /**
     * Dedicated daemon pool per scan/clean operation so Cancel/timeout can
     * interrupt only that operation's workers (via shutdownNow) without
     * disturbing the shared pools, and lingering walks can never starve the
     * next scan. Always paired with a shutdown on settle — see call sites.
     */
    private static ExecutorService newScanExecutor(String name, int threads) {
        int n = Math.max(1, threads);
        return java.util.concurrent.Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, name + "-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
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

    /**
     * Settings-aware, collision-proof backup root: honors the custom backup
     * directory (like driver backups do) and appends millis + random suffix
     * so two cleans in the same second never share one directory.
     */
    private static Path newUniqueBackupRoot() {
        Path parent;
        try {
            com.sbtools.settings.AppSettings s = new com.sbtools.settings.SettingsStore().load();
            parent = AppPaths.backupsRoot(s).resolve("cleanup-backups");
        } catch (Exception ignored) {
            parent = AppPaths.backupsRoot().resolve("cleanup-backups");
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        String rand = String.format("%04x", java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000));
        return parent.resolve(stamp + "-" + rand);
    }
}
