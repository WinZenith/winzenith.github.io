package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class DockerCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.DOCKER_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        String progData = CleanerUtils.safeEnv("PROGRAMDATA");
        if (progData != null) {
            Path dockerDir = Path.of(progData, "Docker");
            if (Files.isDirectory(dockerDir)) {
                Path images = dockerDir.resolve("images");
                Path containers = dockerDir.resolve("containers");
                Path volumes = dockerDir.resolve("volumes");
                for (Path dir : new Path[]{images, containers, volumes}) {
                    if (Files.isDirectory(dir)) {
                        try (Stream<Path> walk = Files.walk(dir, 4)) {
                            var stats = walk.filter(Files::isRegularFile)
                                    .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                            totalSize += stats.getSum();
                            itemCount += (int) stats.getCount();
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : " (requires Docker)"));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        long cleaned = 0;
        String progData = CleanerUtils.safeEnv("PROGRAMDATA");
        if (progData == null) return 0;
        Path dockerDir = Path.of(progData, "Docker");
        if (!Files.isDirectory(dockerDir)) return 0;
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "system", "prune", "-af", "--volumes");
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
            boolean finished = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
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
            } else { p.destroyForcibly(); }
        } catch (Exception e) { AppLogger.warning("Docker prune failed: " + e.getMessage()); }
        return cleaned;
    }
}
