package com.sbtools.cleaner;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class RegistryDefragService {

    private static final List<String> COMPACTABLE_HIVES = List.of(
            "HKCU\\Software",
            "HKCU\\Control Panel",
            "HKCU\\Environment"
    );

    public static DefragResult defrag() {
        List<String> defragged = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Path backupDir = AppPaths.backupsRoot().resolve("registry-defrag")
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));

        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            return new DefragResult(0, List.of(), List.of("Failed to create backup directory: " + e.getMessage()));
        }

        for (String hive : COMPACTABLE_HIVES) {
            try {
                String safeName = hive.replace("\\", "_").replace(":", "");
                Path exportFile = backupDir.resolve(safeName + ".reg");

                ProcessBuilder exportPb = new ProcessBuilder("reg", "export", hive,
                        exportFile.toString(), "/y");
                exportPb.redirectErrorStream(true);
                Process exportProcess = exportPb.start();
                boolean exportOk = exportProcess.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);

                if (!exportOk || !Files.exists(exportFile) || Files.size(exportFile) == 0) {
                    errors.add("Failed to export " + hive);
                    continue;
                }

                ProcessBuilder deletePb = new ProcessBuilder("reg", "delete", hive, "/f");
                deletePb.redirectErrorStream(true);
                Process deleteProcess = deletePb.start();
                deleteProcess.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);

                ProcessBuilder importPb = new ProcessBuilder("reg", "import", exportFile.toString());
                importPb.redirectErrorStream(true);
                Process importProcess = importPb.start();
                boolean importOk = importProcess.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);

                if (importOk) {
                    defragged.add(hive);
                } else {
                    errors.add("Failed to re-import " + hive);
                }
            } catch (Exception e) {
                AppLogger.warning("Registry defrag failed for " + hive + ": " + e.getMessage());
                errors.add(hive + ": " + e.getMessage());
            }
        }

        return new DefragResult(defragged.size(), defragged, errors);
    }

    public static long estimateSize() {
        long totalBytes = 0;
        for (String hive : COMPACTABLE_HIVES) {
            try {
                String[] parts = hive.split("\\\\", 2);
                if (parts.length < 2) continue;
                String root = parts[0];
                String keyPath = parts[1];

                ProcessBuilder pb = new ProcessBuilder("reg", "query", hive, "/s", "/f", "", "/e");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes());
                totalBytes += output.length();
                p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
        return totalBytes;
    }

    public record DefragResult(int defraggedCount, List<String> defraggedHives, List<String> errors) {
        public boolean hasErrors() {
            return !errors.isEmpty();
        }

        public String getSummary() {
            StringBuilder sb = new StringBuilder();
            sb.append("Defragged ").append(defraggedCount).append(" hive(s).");
            if (hasErrors()) {
                sb.append("\nErrors:\n");
                for (String err : errors) {
                    sb.append("- ").append(err).append("\n");
                }
            }
            return sb.toString();
        }
    }
}