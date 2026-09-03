package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessManager;
import com.sbtools.util.WindowsServicingSafety;
import com.sbtools.util.WindowsVersionUtil;


public class WindowsUpdateCleanupCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.WINDOWS_UPDATE_CLEANUP; }

    @Override
    public boolean requiresAdmin() { return true; }

    @Override
    public void scan(CleanupRow row) {
        if (WindowsVersionUtil.isNewerThanKnownSafeBuild()) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Skipped (not supported on this Windows version)");
            return;
        }
        if (WindowsServicingSafety.isServicingPending()) {
            String reasons = String.join("; ", WindowsServicingSafety.getPendingReasons());
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Skipped (pending system restart: " + reasons + ")");
            return;
        }
        long totalSize = 0;
        int itemCount = 0;
        try {
            ProcessBuilder pb = new ProcessBuilder("dism", "/Online", "/Cleanup-Image", "/AnalyzeComponentStore");
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = p.waitFor(25, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                for (String line : output.split("\\n")) {
                    String lower = line.toLowerCase();
                    if (lower.contains("superseded")) {
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
            } else { p.destroyForcibly(); }
        } catch (Exception ignored) {}
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
        if (WindowsVersionUtil.isNewerThanKnownSafeBuild()) {
            AppLogger.info("Skipping DISM component cleanup on newer Windows version (Build "
                    + WindowsVersionUtil.getBuildNumber() + ")");
            return 0;
        }
        if (WindowsServicingSafety.isServicingPending()) {
            AppLogger.info("Skipping DISM component cleanup: pending system restart ("
                    + String.join("; ", WindowsServicingSafety.getPendingReasons()) + ")");
            return 0;
        }
        long cleaned = 0;
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("dism", "/Online", "/Cleanup-Image", "/StartComponentCleanup");
            pb.redirectErrorStream(true);
            p = ProcessManager.start(pb);
            boolean finished = false;
            long deadline = System.currentTimeMillis() + 900_000L;
            while (System.currentTimeMillis() < deadline) {
                if (token != null && token.isCancelled()) {
                    AppLogger.info("DISM component cleanup canceled by user");
                    p.destroyForcibly();
                    throw new java.util.concurrent.CancellationException("DISM cleanup canceled");
                }
                if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) { finished = true; break; }
            }
            if (finished) {
                if (token != null && token.isCancelled()) {
                    AppLogger.info("DISM cleanup canceled after process finished");
                    return 0L;
                }
                int exitCode = p.exitValue();
                AppLogger.info("DISM component cleanup completed with exit code " + exitCode);
                if (exitCode == 0) {
                    String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    cleaned = parseCleanedBytes(output);
                    // No fallback to scan estimate: report only measured bytes.
                }
            } else {
                AppLogger.warning("DISM cleanup timed out after ~15 minutes");
                p.destroyForcibly();
            }
        } catch (java.util.concurrent.CancellationException ce) {
            if (p != null) try { p.destroyForcibly(); } catch (Exception ignored) {}
            throw ce;
        } catch (Exception e) {
            if (e instanceof java.util.concurrent.CancellationException) throw (java.util.concurrent.CancellationException) e;
            AppLogger.warning("DISM cleanup failed: " + e.getMessage());
        }
        return cleaned;
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
