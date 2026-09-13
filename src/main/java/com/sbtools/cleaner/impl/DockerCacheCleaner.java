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

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.DOCKER_CACHE; }

    @Override
    public java.util.List<String> describeTargets() {
        return java.util.List.of(
                "Dangling Docker build cache / unused networks via 'docker system prune -f'",
                "Tagged images, stopped containers and volumes are preserved");
    }

    @Override
    public void scan(CleanupRow row) {
        // Scan and clean measure different things by design: clean runs
        // 'docker system prune -f' (dangling cache only), while the on-disk
        // image store holds tagged images prune preserves. Reporting the
        // store size as cleanable fabricated GBs that clean could never free
        // (and the service then logged a bogus "nothing was cleaned" error),
        // and post-clean rescans never cleared. Report presence only; the
        // actual reclaimed bytes are parsed from prune output during clean.
        String progData = CleanerUtils.safeEnv("PROGRAMDATA");
        boolean present = false;
        if (progData != null) {
            present = Files.isDirectory(Path.of(progData, "Docker"));
        }
        row.setTotalBytes(0);
        row.setItemCount(0);
        row.setSizeOrCountText(present
                ? "Docker detected - reclaimable shown after clean"
                : CleanerUtils.formatBytes(0) + " (requires Docker)");
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long cleaned = 0;
        String progData = CleanerUtils.safeEnv("PROGRAMDATA");
        if (progData == null) return 0;
        Path dockerDir = Path.of(progData, "Docker");
        if (!Files.isDirectory(dockerDir)) return 0;
        try {
            // Dangling-only prune: removes dangling build cache/networks but
            // preserves tagged unused images and stopped containers. The former
            // '-af' scope destroyed dev assets beyond the advertised "cache"
            // and beyond what the scan measures — never use it here.
            ProcessBuilder pb = new ProcessBuilder("docker", "system", "prune", "-f");
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
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
            }, "docker-prune-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            boolean finished = false;
            long deadline = System.currentTimeMillis() + 120_000L;
            while (System.currentTimeMillis() < deadline) {
                if (token != null && token.isCancelled()) {
                    p.destroyForcibly();
                    throw new java.util.concurrent.CancellationException("Docker prune canceled");
                }
                if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) { finished = true; break; }
            }
            readerThread.join(2000);
            if (finished) {
                String fullOutput = output.toString();
                for (String line : fullOutput.split("\\n")) {
                    if (line.contains("reclaimed")) {
                        String upper = line.toUpperCase();
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                                "(\\d+(?:\\.\\d+)?)\\s*(B|KB|MB|GB|TB)").matcher(upper);
                        if (m.find()) {
                            try {
                                double val = Double.parseDouble(m.group(1));
                                String unit = m.group(2);
                                cleaned = switch (unit) {
                                    case "KB" -> (long) (val * 1024L);
                                    case "MB" -> (long) (val * 1024L * 1024L);
                                    case "GB" -> (long) (val * 1024L * 1024L * 1024L);
                                    case "TB" -> (long) (val * 1024L * 1024L * 1024L * 1024L);
                                    default -> (long) val;
                                };
                            } catch (NumberFormatException ignored) {}
                        }
                        break;
                    }
                }
            } else { p.destroyForcibly(); AppLogger.warning("Docker prune timed out after 120s"); }
        } catch (java.util.concurrent.CancellationException ce) {
            throw ce;
        } catch (Exception e) { AppLogger.warning("Docker prune failed: " + e.getMessage()); }
        return cleaned;
    }
}
