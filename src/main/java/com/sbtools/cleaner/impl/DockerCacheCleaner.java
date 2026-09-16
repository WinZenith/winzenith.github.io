package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessManager;

import java.nio.file.Files;
import java.nio.file.Path;

public class DockerCacheCleaner implements CleanerExtension {

    private static final java.util.regex.Pattern DOCKER_SIZE =
            java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(B|KB|MB|GB|TB)");
    private static final long SCAN_TIMEOUT_MS = 20_000L;
    private static final long CLEAN_TIMEOUT_MS = 120_000L;

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.DOCKER_CACHE; }

    @Override
    public java.util.List<String> describeTargets() {
        return java.util.List.of(
                "Docker build cache via 'docker builder prune -f'",
                "Images, containers and volumes are preserved");
    }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) {
            markCanceled(row);
            return;
        }
        String progData = CleanerUtils.safeEnv("PROGRAMDATA");
        boolean present = progData != null && Files.isDirectory(Path.of(progData, "Docker"));
        if (!present) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText(CleanerUtils.formatBytes(0) + " (requires Docker)");
            return;
        }
        String dfOutput;
        try {
            dfOutput = runDocker(java.util.List.of("docker", "system", "df"), SCAN_TIMEOUT_MS, token);
        } catch (java.util.concurrent.CancellationException ce) {
            markCanceled(row);
            return;
        }
        if (token != null && token.isCancelled()) {
            markCanceled(row);
            return;
        }
        Long reclaimable = parseBuildCacheReclaimable(dfOutput);
        if (reclaimable != null && reclaimable > 0) {
            row.setTotalBytes(reclaimable);
            row.setItemCount(1);
            row.setSizeOrCountText(CleanerUtils.formatBytes(reclaimable) + " (build cache)");
        } else if (reclaimable != null) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText(CleanerUtils.formatBytes(0) + " (build cache empty)");
        } else {
            // Engine/CLI missing or unparseable — do not report 0 bytes; clean still prunes.
            row.setTotalBytes(0);
            row.setItemCount(1);
            row.setSizeOrCountText("Docker detected — reclaimable size unknown");
        }
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        String progData = CleanerUtils.safeEnv("PROGRAMDATA");
        if (progData == null) return 0;
        Path dockerDir = Path.of(progData, "Docker");
        if (!Files.isDirectory(dockerDir)) return 0;
        try {
            // Build-cache only. `docker system prune -f` also deletes stopped
            // containers — never use it here.
            String fullOutput = runDocker(
                    java.util.List.of("docker", "builder", "prune", "-f"), CLEAN_TIMEOUT_MS, token);
            return parseReclaimedFromPrune(fullOutput);
        } catch (java.util.concurrent.CancellationException ce) {
            throw ce;
        }
    }

    private static void markCanceled(CleanupRow row) {
        row.setTotalBytes(0);
        row.setItemCount(0);
        row.setSizeOrCountText("Canceled");
        row.setScanStatus(CleanupRow.ScanStatus.ERROR);
        row.setErrorMessage("Scan canceled by user");
    }

    /** @return stdout, or empty string on timeout/failure */
    private static String runDocker(java.util.List<String> command, long timeoutMs,
            com.sbtools.util.CancellationToken token) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            p = ProcessManager.start(pb);
            java.io.InputStream is = p.getInputStream();
            StringBuilder output = new StringBuilder();
            Thread readerThread = new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        output.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (Exception ignored) {}
            }, "docker-cmd-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            boolean finished = false;
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (token != null && token.isCancelled()) {
                    p.destroyForcibly();
                    throw new java.util.concurrent.CancellationException("Docker command canceled");
                }
                try {
                    if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) { finished = true; break; }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    p.destroyForcibly();
                    throw new java.util.concurrent.CancellationException("Docker command canceled");
                }
            }
            readerThread.join(2000);
            if (!finished) {
                p.destroyForcibly();
                AppLogger.warning("Docker command timed out after " + timeoutMs + "ms: " + command);
                return "";
            }
            return output.toString();
        } catch (java.util.concurrent.CancellationException ce) {
            if (p != null && p.isAlive()) {
                try { p.destroyForcibly(); } catch (Exception ignored) {}
            }
            throw ce;
        } catch (Exception e) {
            AppLogger.warning("Docker command failed: " + e.getMessage());
            return "";
        }
    }

    /** Last size on a Build Cache row is RECLAIMABLE. Null = line missing / unparseable. */
    static Long parseBuildCacheReclaimable(String dfOutput) {
        if (dfOutput == null || dfOutput.isBlank()) return null;
        for (String line : dfOutput.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (!trimmed.toLowerCase().startsWith("build cache")) continue;
            java.util.regex.Matcher m = DOCKER_SIZE.matcher(trimmed.toUpperCase());
            Long last = null;
            while (m.find()) {
                last = toBytes(m.group(1), m.group(2));
            }
            return last != null ? last : 0L;
        }
        return null;
    }

    static long parseReclaimedFromPrune(String pruneOutput) {
        if (pruneOutput == null || pruneOutput.isBlank()) return 0L;
        for (String line : pruneOutput.split("\\r?\\n")) {
            String lower = line.toLowerCase();
            if (!lower.contains("reclaimed") && !lower.contains("total")) continue;
            java.util.regex.Matcher m = DOCKER_SIZE.matcher(line.toUpperCase());
            if (m.find()) {
                return toBytes(m.group(1), m.group(2));
            }
        }
        return 0L;
    }

    private static long toBytes(String number, String unit) {
        try {
            double val = Double.parseDouble(number);
            return switch (unit) {
                case "KB" -> (long) (val * 1024L);
                case "MB" -> (long) (val * 1024L * 1024L);
                case "GB" -> (long) (val * 1024L * 1024L * 1024L);
                case "TB" -> (long) (val * 1024L * 1024L * 1024L * 1024L);
                default -> (long) val;
            };
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
