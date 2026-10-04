package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;
import com.sbtools.util.WindowsServicingSafety;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class WindowsUpdateCleanupCleaner implements CleanerExtension {

    /** Live /StartComponentCleanup. Never killed; Cancel and the clean timeout wait for it. */
    private static final AtomicReference<Process> ACTIVE_COMPONENT_CLEANUP = new AtomicReference<>();
    /** Opened when DISM starts, released after the service records this category's bytes. */
    private static final AtomicReference<CountDownLatch> RECORDED = new AtomicReference<>();
    /** Exit 0 with no parseable size. DISM often prints no byte count. */
    private static final java.util.concurrent.atomic.AtomicBoolean SUCCEEDED_WITHOUT_SIZE = new java.util.concurrent.atomic.AtomicBoolean();

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.WINDOWS_UPDATE_CLEANUP; }

    @Override
    public boolean requiresAdmin() { return true; }

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
        if (WindowsServicingSafety.isServicingPending()) {
            String reasons = String.join("; ", WindowsServicingSafety.getPendingReasons());
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Skipped (pending system restart: " + reasons + ")");
            return;
        }
        if (CleanerUtils.isWindowsUpdateBusy()) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Skipped (Windows Update / DISM already active)");
            return;
        }
        long totalSize = 0;
        int itemCount = 0;
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("dism", "/Online", "/Cleanup-Image", "/AnalyzeComponentStore");
            pb.redirectErrorStream(true);
            p = startDismUntracked(pb);
            ACTIVE_COMPONENT_CLEANUP.set(p);
            // Never kill DISM. Returns wait in finally so dism.exe is gone
            // before the scan result is delivered.
            boolean finished = false;
            long deadline = System.currentTimeMillis() + 120_000L;
            while (System.currentTimeMillis() < deadline) {
                if (token != null && token.isCancelled()) {
                    AppLogger.info("DISM analyze cancel requested — not killing");
                    row.setTotalBytes(0);
                    row.setItemCount(0);
                    row.setSizeOrCountText("Canceled");
                    row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                    row.setErrorMessage("Scan canceled by user");
                    return;
                }
                try {
                    if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) { finished = true; break; }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    AppLogger.info("DISM analyze interrupted — not killing");
                    row.setSizeOrCountText("Canceled");
                    row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                    row.setErrorMessage("Scan canceled by user");
                    return;
                }
            }
            if (!finished) {
                AppLogger.warning("DISM analyze timed out — not killing");
                row.setTotalBytes(0);
                row.setItemCount(0);
                row.setSizeOrCountText("Timed out");
                row.setScanStatus(CleanupRow.ScanStatus.ERROR);
                row.setErrorMessage("Scan timed out");
                return;
            }
            String output;
            try {
                output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                output = "";
            }
            for (String line : output.split("\\n")) {
                if (token != null && token.isCancelled()) break;
                String lower = line.toLowerCase();
                if (lower.contains("superseded") || lower.contains("reclaimable")) {
                    String sizePart = parseSizeFromLine(line);
                    if (sizePart != null) {
                        long bytes = parseBytesFromSizeString(sizePart);
                        if (bytes > 0) {
                            totalSize += bytes;
                            itemCount = Math.max(itemCount, 1);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            AppLogger.warning("DISM analyze failed; waiting for the process if it started");
        } finally {
            if (p != null) {
                waitForProcessNoKill(p);
                ACTIVE_COMPONENT_CLEANUP.compareAndSet(p, null);
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalSize) + (itemCount > 0 ? " (superseded components)" : " (none found)"));
    }

    @Override
    public long getCleanTimeoutSeconds() {
        return 900;
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        if (WindowsServicingSafety.isServicingPending()) {
            AppLogger.info("Skipping DISM component cleanup: pending system restart ("
                    + String.join("; ", WindowsServicingSafety.getPendingReasons()) + ")");
            return 0;
        }
        if (CleanerUtils.isWindowsUpdateBusy()) {
            AppLogger.info("Skipping DISM component cleanup: Windows Update / DISM already active");
            return 0;
        }
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("dism", "/Online", "/Cleanup-Image", "/StartComponentCleanup");
            pb.redirectErrorStream(true);
            p = startDismUntracked(pb);
            RECORDED.set(new CountDownLatch(1));
            ACTIVE_COMPONENT_CLEANUP.set(p);
            boolean finished = false;
            long deadline = System.currentTimeMillis() + 900_000L;
            while (System.currentTimeMillis() < deadline) {
                if (token != null && token.isCancelled()) {
                    AppLogger.info("DISM component cleanup cancel requested — waiting for process to finish (not killing)");
                    break;
                }
                try {
                    if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) { finished = true; break; }
                } catch (InterruptedException ie) {
                    Thread.interrupted();
                    AppLogger.info("DISM component cleanup interrupted — waiting for process to finish (not killing)");
                    break;
                }
            }
            if (!finished) {
                AppLogger.warning("DISM component cleanup still running — waiting for it to finish (not killing)");
            }
            // Cancel does not discard a finished cleanup. Later categories stop
            // because the token is already cancelled; this category's bytes stay.
            return readDismResult(p);
        } catch (Exception e) {
            AppLogger.warning("DISM cleanup failed: " + e.getMessage());
            return 0L;
        } finally {
            if (p != null) {
                waitForProcessNoKill(p);
                ACTIVE_COMPONENT_CLEANUP.compareAndSet(p, null);
            }
        }
    }

    /** True when DISM is running or its bytes are not stored on the summary yet. */
    public static boolean componentCleanupNeedsRecord() {
        Process p = ACTIVE_COMPONENT_CLEANUP.get();
        return (p != null && p.isAlive()) || RECORDED.get() != null;
    }

    /**
     * Block until the in-flight component cleanup exits. Does not kill DISM.
     */
    public static void awaitComponentCleanup() {
        Process p = ACTIVE_COMPONENT_CLEANUP.get();
        if (p != null) waitForProcessNoKill(p);
    }

    /**
     * Wait until DISM exits and {@link #markComponentCleanupRecorded()} runs.
     * Does not kill the process.
     */
    public static boolean awaitComponentCleanupRecorded(long waitMs) {
        awaitComponentCleanup();
        CountDownLatch latch = RECORDED.get();
        if (latch == null) return true;
        try {
            return latch.await(Math.max(0L, waitMs), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Call after this category's bytes are stored. No-op when DISM did not start. */
    public static void markComponentCleanupRecorded() {
        CountDownLatch latch = RECORDED.getAndSet(null);
        if (latch != null) latch.countDown();
    }

    /**
     * True once when the last component cleanup exited 0 without a parseable size.
     * The service must not report that as "nothing was cleaned".
     */
    public static boolean consumeSucceededWithoutSize() {
        return SUCCEEDED_WITHOUT_SIZE.getAndSet(false);
    }

    /**
     * Exit 0 reports parsed reclaimed bytes. A cancel or timeout does not
     * zero a cleanup that already completed.
     */
    static long cleanedBytesForExit(int exitCode, long parsedBytes) {
        if (exitCode != 0) return 0L;
        return Math.max(0L, parsedBytes);
    }

    private long readDismResult(Process p) {
        waitForProcessNoKill(p);
        try {
            int exitCode = p.exitValue();
            AppLogger.info("DISM component cleanup completed with exit code " + exitCode);
            if (exitCode != 0) return 0L;
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            long parsed = parseCleanedBytes(output);
            if (parsed <= 0) {
                SUCCEEDED_WITHOUT_SIZE.set(true);
                AppLogger.info("DISM component cleanup succeeded; output had no reclaimed size");
            }
            return cleanedBytesForExit(exitCode, parsed);
        } catch (Exception e) {
            AppLogger.warning("DISM cleanup result unreadable: " + e.getMessage());
            return 0L;
        }
    }

    /**
     * DISM must not be registered with ProcessManager: app exit / shutdown
     * would destroyForcibly it and can corrupt CBS.
     */
    private static Process startDismUntracked(ProcessBuilder pb) throws java.io.IOException {
        return pb.start();
    }

    private static void waitForProcessNoKill(Process p) {
        if (p == null) return;
        while (p.isAlive()) {
            try {
                p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.interrupted();
            }
        }
    }

    private String parseSizeFromLine(String line) {
        String[] parts = line.split(":");
        if (parts.length >= 2) {
            String afterColon = parts[1].trim();
            int unitIdx = -1;
            String upper = afterColon.toUpperCase();
            for (String unit : new String[]{"GB", "MB", "KB"}) {
                int idx = upper.indexOf(unit);
                if (idx >= 0) { unitIdx = idx; break; }
            }
            if (unitIdx > 0) {
                return afterColon.substring(0, unitIdx + 2).trim();
            }
        }
        return null;
    }

    private long parseBytesFromSizeString(String sizePart) {
        String numStr = sizePart.replaceAll("[^0-9.]", "").trim();
        if (numStr.isEmpty()) return 0;
        try {
            double numericValue = Double.parseDouble(numStr);
            String upper = sizePart.toUpperCase();
            if (upper.contains("GB")) return (long) (numericValue * 1024L * 1024L * 1024L);
            if (upper.contains("MB")) return (long) (numericValue * 1024L * 1024L);
            if (upper.contains("KB")) return (long) (numericValue * 1024L);
            return (long) (numericValue * 1024L * 1024L);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private long parseCleanedBytes(String output) {
        for (String line : output.split("\\n")) {
            String lower = line.toLowerCase();
            if (lower.contains("successfully") && lower.contains("freed")) {
                String sizePart = parseSizeFromLine(line);
                if (sizePart != null) {
                    return parseBytesFromSizeString(sizePart);
                }
            }
        }
        return 0;
    }
}
