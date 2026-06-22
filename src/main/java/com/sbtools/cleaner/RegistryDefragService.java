package com.sbtools.cleaner;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.ProcessManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class RegistryDefragService {

    private static final List<String> COMPACTABLE_HIVES = List.of(
            "HKCU\\Software",
            "HKCU\\Control Panel",
            "HKCU\\Environment",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion"
    );

    public static DefragResult defrag() {
        return defrag(null);
    }

    public static DefragResult defrag(Consumer<String> progressCallback) {
        List<String> defragged = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Path backupDir = AppPaths.backupsRoot().resolve("registry-defrag")
                .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));

        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            return new DefragResult(0, List.of(), List.of("Failed to create backup directory: " + e.getMessage()));
        }

        int total = COMPACTABLE_HIVES.size();
        for (int i = 0; i < total; i++) {
            String hive = COMPACTABLE_HIVES.get(i);
            int current = i + 1;

            if (progressCallback != null) {
                progressCallback.accept("Exporting " + hive + "... (" + current + "/" + total + ")");
            }

            try {
                String safeName = hive.replace("\\", "_").replace(":", "");
                Path exportFile = backupDir.resolve(safeName + ".reg");

                ProcessBuilder exportPb = new ProcessBuilder("reg", "export", hive,
                        exportFile.toString(), "/y");
                exportPb.redirectErrorStream(true);
                Process exportProcess = ProcessManager.start(exportPb);
                boolean exportOk = exportProcess.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);

                if (!exportOk || !Files.exists(exportFile) || Files.size(exportFile) == 0) {
                    errors.add("Failed to export " + hive);
                    continue;
                }

                byte[] content = Files.readAllBytes(exportFile);
                String header = new String(content, 0, Math.min(100, content.length), StandardCharsets.UTF_8);
                if (!header.contains("Windows Registry Editor Version 5.00") && !header.contains("REGEDIT4")) {
                    errors.add("Exported file for " + hive + " has invalid header, skipping delete for safety");
                    Files.deleteIfExists(exportFile);
                    continue;
                }

                if (progressCallback != null) {
                    progressCallback.accept("Deleting " + hive + "... (" + current + "/" + total + ")");
                }

                ProcessBuilder deletePb = new ProcessBuilder("reg", "delete", hive, "/f");
                deletePb.redirectErrorStream(true);
                Process deleteProcess = ProcessManager.start(deletePb);
                deleteProcess.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);

                if (progressCallback != null) {
                    progressCallback.accept("Importing " + hive + "... (" + current + "/" + total + ")");
                }

                ProcessBuilder importPb = new ProcessBuilder("reg", "import", exportFile.toString());
                importPb.redirectErrorStream(true);
                Process importProcess = ProcessManager.start(importPb);
                boolean importOk = importProcess.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);

                if (importOk) {
                    defragged.add(hive);
                    AppLogger.info("Registry defrag succeeded for " + hive);
                } else {
                    errors.add("Failed to re-import " + hive + ", attempting restore from backup...");
                    attemptRestore(backupDir, safeName, hive, errors);
                }
            } catch (Exception e) {
                AppLogger.warning("Registry defrag failed for " + hive + ": " + e.getMessage());
                errors.add(hive + ": " + e.getMessage());
            }
        }

        return new DefragResult(defragged.size(), defragged, errors);
    }

    private static void attemptRestore(Path backupDir, String safeName, String hive, List<String> errors) {
        try {
            Path exportFile = backupDir.resolve(safeName + ".reg");
            if (!Files.exists(exportFile)) {
                errors.add("Cannot restore " + hive + ": backup file not found");
                return;
            }
            ProcessBuilder restorePb = new ProcessBuilder("reg", "import", exportFile.toString());
            restorePb.redirectErrorStream(true);
            Process restoreProcess = ProcessManager.start(restorePb);
            boolean restoreOk = restoreProcess.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (restoreOk) {
                AppLogger.info("Registry restored for " + hive + " from backup");
                errors.remove(errors.size() - 1);
                errors.add("Import failed for " + hive + " but restored from backup");
            } else {
                errors.add("CRITICAL: Import and restore both failed for " + hive);
                AppLogger.error("Registry import and restore failed for " + hive);
            }
        } catch (Exception e) {
            errors.add("CRITICAL: Restore also failed for " + hive + ": " + e.getMessage());
            AppLogger.error("Registry restore failed for " + hive, e);
        }
    }

    public static long estimateSize() {
        long totalBytes = 0;
        for (String hive : COMPACTABLE_HIVES) {
            try {
                ProcessBuilder pb = new ProcessBuilder("reg", "query", hive, "/s");
                pb.redirectErrorStream(true);
                Process p = ProcessManager.start(pb);

                long[] valueCount = {0};
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                Thread reader = new Thread(() -> {
                    try (java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (line.contains("REG_")) valueCount[0]++;
                        }
                    } catch (Exception ignored) {}
                }, "reg-query-reader");
                reader.setDaemon(true);
                reader.start();

                boolean finished = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                }
                reader.join(2000);

                totalBytes += valueCount[0] * 512;
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