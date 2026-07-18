package com.sbtools.software;

import com.sbtools.util.AppLogger;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public class SoftwareUpdateService {

    private final ProcessRunner runner = new ProcessRunner(600);
    private final WingetRunner winget = new WingetRunner(runner);
    private final java.util.concurrent.ExecutorService scanExecutor =
            java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "SoftwareUpdate-Scan");
                t.setDaemon(true);
                return t;
            });

    public boolean isWingetAvailable() {
        return winget.isAvailable();
    }

    public String getWingetDiagnostics() {
        return winget.getDiagnostics();
    }

    public void shutdown() {
        scanExecutor.shutdownNow();
    }

    public List<SoftwareUpdateEntry> scanForUpdates() throws IOException, InterruptedException {
        List<SoftwareUpdateEntry> results = new ArrayList<>();

        // Try JSON mode first
        ProcessResult r = winget.runWithFallback(120,
                "upgrade", "--source", "winget", "--accept-source-agreements",
                "--accept-package-agreements", "--output", "json");
        if (r != null) {
            String stdout = r.stdout();
            if (stdout != null && !stdout.isBlank()) {
                results.addAll(parseJsonOutput(stdout));
            }
        }

        // If JSON produced no results, try text-mode fallback for older winget
        if (results.isEmpty()) {
            ProcessResult textResult = winget.runWithFallback(120,
                    "upgrade", "--source", "winget");
            if (textResult != null) {
                String stdout = textResult.stdout();
                if (stdout != null && !stdout.isBlank()) {
                    List<SoftwareUpdateEntry> textResults = parseTextOutput(stdout);
                    if (!textResults.isEmpty()) {
                        results.addAll(textResults);
                    }
                }
            }
            if (results.isEmpty() && textResult == null) {
                AppLogger.warning("Fallback winget text scan failed");
            }
        }

        return results;
    }

    List<SoftwareUpdateEntry> parseTextOutput(String stdout) {
        List<SoftwareUpdateEntry> out = new ArrayList<>();
        String trimmed = stdout == null ? "" : stdout.trim();
        if (trimmed.isEmpty()) return out;
        String lowerTrimmed = trimmed.toLowerCase();
        if (lowerTrimmed.contains("no applicable upgrades") || lowerTrimmed.contains("no installed package")) {
            return out;
        }
        String[] lines = stdout.split("\\r?\\n");
        int headerIdx = -1;
        String headerLine = null;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            String lower = l.toLowerCase();
            boolean hasName = lower.contains("name");
            boolean hasVersion = lower.contains("version") || lower.contains("installed");
            boolean hasId = lower.contains("id") || lower.contains("identifier") || lower.contains("package");
            if (hasName && (hasVersion || hasId)) {
                headerIdx = i;
                headerLine = l;
                break;
            }
        }
        int start = headerIdx >= 0 ? headerIdx + 1 : 0;

        int idxName = -1, idxId = -1, idxVersion = -1, idxAvailable = -1, idxSource = -1;
        if (headerLine != null) {
            String lowerHeader = headerLine.toLowerCase();
            idxName = findColumnStart(lowerHeader, "name");
            idxId = findColumnStart(lowerHeader, "id", "identifier", "packageidentifier", "package id");
            idxVersion = findColumnStart(lowerHeader, "version", "installedversion", "installed", "current");
            idxAvailable = findColumnStart(lowerHeader, "available", "availableversion", "new", "upgradable");
            idxSource = findColumnStart(lowerHeader, "source");
        }

        for (int i = start; i < lines.length; i++) {
            String l = lines[i];
            if (l.isBlank()) continue;
            if (l.trim().startsWith("---")) continue;

            try {
                String name = null, id = null, version = null, available = null, source = null;
                if (headerLine != null) {
                    if (idxName >= 0) name = extractColumnAt(l, idxName, headerLine.length()).trim();
                    if (idxId >= 0) id = extractColumnAt(l, idxId, headerLine.length()).trim();
                    if (idxVersion >= 0) version = extractColumnAt(l, idxVersion, headerLine.length()).trim();
                    if (idxAvailable >= 0) available = extractColumnAt(l, idxAvailable, headerLine.length()).trim();
                    if (idxSource >= 0) source = extractColumnAt(l, idxSource, headerLine.length()).trim();
                }
                if (name == null || name.isBlank()) {
                    String[] tokens = l.trim().split("\\s{2,}");
                    if (tokens.length < 3) continue;
                    name = tokens[0];
                    if (tokens.length > 1) id = tokens[1];
                    if (tokens.length > 2) version = tokens[2];
                    if (tokens.length > 3) available = tokens[3];
                    if (tokens.length > 4) source = tokens[4];
                }

                if (source != null && !source.isBlank() && !source.equalsIgnoreCase("winget")) continue;
                if (available == null || available.isBlank()) continue;
                if (version == null) version = "";
                if (!available.equals(version)) {
                    out.add(new SoftwareUpdateEntry(id, name, version, available));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static int findColumnStart(String lowerHeader, String... keys) {
        for (String key : keys) {
            int idx = lowerHeader.indexOf(key.toLowerCase());
            if (idx >= 0) return idx;
        }
        return -1;
    }

    private static String extractColumnAt(String line, int startCol, int headerLen) {
        if (startCol >= line.length()) return "";
        String sub = line.substring(startCol);
        String[] tokens = sub.trim().split("\\s{2,}", 2);
        return tokens.length > 0 ? tokens[0] : "";
    }

    List<SoftwareUpdateEntry> parseJsonOutput(String stdout) {
        List<SoftwareUpdateEntry> results = new ArrayList<>();
        try {
            JsonNode root = JsonMapper.parseTree(stdout);
            JsonNode arrayNode = null;
            if (root.isArray()) {
                arrayNode = root;
            } else if (root.isObject()) {
                Iterator<String> fields = root.fieldNames();
                while (fields.hasNext()) {
                    JsonNode child = root.get(fields.next());
                    if (child.isArray()) {
                        arrayNode = child;
                        break;
                    }
                }
            }
            if (arrayNode != null) {
                for (JsonNode el : arrayNode) {
                    String id = findText(el, "Id", "PackageIdentifier");
                    String name = findText(el, "Name", "PackageName");
                    String version = findText(el, "Version", "InstalledVersion");
                    String available = findText(el, "AvailableVersion", "Available");
                    String source = findText(el, "Source");
                    long sizeBytes = 0;
                    JsonNode sizeNode = el.get("Size");
                    if (sizeNode != null && !sizeNode.isNull()) {
                        sizeBytes = sizeNode.asLong(0);
                    }
                    if (source != null && !source.isBlank() && !source.equalsIgnoreCase("winget")) {
                        continue;
                    }
                    if (available == null || available.isBlank()) {
                        continue;
                    }
                    if (version == null) {
                        version = "";
                    }
                    if (!available.equals(version)) {
                        results.add(new SoftwareUpdateEntry(id, name, version, available, "winget", null, sizeBytes));
                    }
                }
            }
        } catch (Exception ex) {
            AppLogger.warning("parseJsonOutput failed: " + ex.getMessage());
        }
        return results;
    }

    public List<Path> findCandidateInstallersForPackage(SoftwareUpdateEntry pkg, Instant since) {
        List<Path> candidates = new ArrayList<>();
        Path downloads = Paths.get(System.getProperty("user.home"), "Downloads");
        if (!Files.isDirectory(downloads)) return candidates;
        Set<String> exts = Set.of(".exe", ".msi", ".msix", ".msixbundle", ".zip", ".msu");
        String idToken = pkg.id() == null ? "" : pkg.id().toLowerCase();
        String name = pkg.getName() == null ? "" : pkg.getName().toLowerCase();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(downloads)) {
            for (Path p : ds) {
                try {
                    if (!Files.isRegularFile(p)) continue;
                    String fileName = p.getFileName().toString().toLowerCase();
                    boolean extOk = exts.stream().anyMatch(fileName::endsWith);
                    if (!extOk) continue;
                    FileTime ft = Files.getLastModifiedTime(p);
                    Instant modified = ft.toInstant();
                    if (modified.isBefore(since)) continue;
                    boolean containsToken = (!idToken.isBlank() && fileName.contains(idToken)) || (!name.isBlank() && Arrays.stream(name.split("\\s+"))
                            .anyMatch(fileName::contains));
                    if (!containsToken) continue;
                    candidates.add(p);
                } catch (Exception e) {
                    AppLogger.warning("Could not evaluate candidate installer: " + p + " -> " + e.getMessage());
                }
            }
        } catch (IOException e) {
            AppLogger.warning("Failed to enumerate Downloads for candidates: " + e.getMessage());
        }
        return candidates;
    }

    public List<Path> deleteInstallerFiles(List<Path> files) {
        List<Path> deleted = new ArrayList<>();
        for (Path p : files) {
            try {
                if (Files.deleteIfExists(p)) {
                    deleted.add(p);
                    AppLogger.info("Deleted installer: " + p);
                }
            } catch (Exception e) {
                AppLogger.warning("Could not delete installer file: " + p + " -> " + e.getMessage());
            }
        }
        return deleted;
    }

    public List<SoftwareUpdateEntry> scanForWindowsUpdates() {
        List<SoftwareUpdateEntry> results = new ArrayList<>();
        if (!com.sbtools.util.AppPaths.isWindows()) {
            return results;
        }
        try {
            Path script = PowerShellScripts.resolve("wu-search-updates.ps1");
            ProcessResult result = runner.run(
                    ProcessRunner.powershellScript(script.toString()), 120);
            if (!result.success()) {
                AppLogger.warning("Windows Update search failed: " + result.combinedOutput());
                return results;
            }
            String stdout = result.stdout();
            if (stdout == null || stdout.isBlank()) {
                return results;
            }
            JsonNode root = JsonMapper.parseTree(stdout);
            if (root.isArray()) {
                for (JsonNode n : root) {
                    results.add(parseWindowsUpdateEntry(n));
                }
            } else if (root.isObject()) {
                results.add(parseWindowsUpdateEntry(root));
            }
            AppLogger.info("Found " + results.size() + " Windows Update(s)");
        } catch (Exception e) {
            AppLogger.warning("Windows Update scan failed: " + e.getMessage());
        }
        return results;
    }

    private SoftwareUpdateEntry parseWindowsUpdateEntry(JsonNode n) {
        String updateId = findText(n, "updateId");
        String title = findText(n, "title");
        String description = findText(n, "description");
        String version = findText(n, "version");
        long sizeBytes = 0;
        JsonNode sizeNode = n.get("sizeBytes");
        if (sizeNode != null && !sizeNode.isNull()) {
            sizeBytes = sizeNode.asLong(0);
        }
        String severity = findText(n, "severity");
        String kbArticle = findText(n, "kbArticle");

        String displayVersion = kbArticle != null && !kbArticle.isBlank()
                ? "KB" + kbArticle
                : (version != null ? version : "");
        String name = title != null ? title : (description != null ? description : "Windows Update");

        return new SoftwareUpdateEntry(
                updateId, name, "", displayVersion,
                "WindowsUpdate", updateId, sizeBytes);
    }

    public ProcessResult installWindowsUpdate(String updateId, long timeoutSeconds) throws IOException, InterruptedException {
        return installWindowsUpdate(updateId, timeoutSeconds, null);
    }

    public ProcessResult installWindowsUpdate(String updateId, long timeoutSeconds, AtomicBoolean cancelled)
            throws IOException, InterruptedException, CancellationException {
        Path script = PowerShellScripts.resolve("wu-install.ps1");
        if (cancelled == null) {
            return runner.run(ProcessRunner.powershellScript(script.toString(), updateId), timeoutSeconds);
        }
        return runner.runStreaming(
                ProcessRunner.powershellScript(script.toString(), updateId),
                line -> {},
                pct -> {},
                cancelled
        );
    }

    /**
     * Runs winget and Windows Update scans concurrently.
     * Returns combined results. Caller can check cancelled between scans.
     *
     * @param cancelled  supplier checked for cancellation; called repeatedly during scan
     * @param onWingetDone  optional callback with winget result count (called on scan thread)
     * @param onWuDone      optional callback with WU result count (called on scan thread)
     */
    public List<SoftwareUpdateEntry> scanAllConcurrent(java.util.function.BooleanSupplier cancelled,
                                                        java.util.function.IntConsumer onWingetDone,
                                                        java.util.function.IntConsumer onWuDone) {
        List<SoftwareUpdateEntry> allUpdates = new ArrayList<>();

        CompletableFuture<List<SoftwareUpdateEntry>> wingetFuture = CompletableFuture.supplyAsync(() -> {
            if (!winget.isAvailable()) return List.of();
            if (cancelled.getAsBoolean()) return List.of();
            try {
                List<SoftwareUpdateEntry> result = scanForUpdates();
                if (onWingetDone != null) onWingetDone.accept(result.size());
                return result;
            } catch (Exception ex) {
                AppLogger.warning("winget scan failed: " + ex.getMessage());
                if (onWingetDone != null) onWingetDone.accept(0);
                return List.of();
            }
        }, scanExecutor);

        CompletableFuture<List<SoftwareUpdateEntry>> wuFuture = CompletableFuture.supplyAsync(() -> {
            if (cancelled.getAsBoolean()) return List.of();
            try {
                List<SoftwareUpdateEntry> result = scanForWindowsUpdates();
                if (onWuDone != null) onWuDone.accept(result.size());
                return result;
            } catch (Exception ex) {
                AppLogger.warning("Windows Update scan failed: " + ex.getMessage());
                if (onWuDone != null) onWuDone.accept(0);
                return List.of();
            }
        }, scanExecutor);

        try {
            CompletableFuture.allOf(wingetFuture, wuFuture).join();
            allUpdates.addAll(wingetFuture.join());
            allUpdates.addAll(wuFuture.join());
        } catch (java.util.concurrent.CancellationException ex) {
            AppLogger.info("Parallel scan cancelled");
        } catch (Exception ex) {
            AppLogger.warning("Parallel scan failed: " + ex.getMessage());
        }

        return allUpdates;
    }

    private static String findText(JsonNode node, String... keys) {
        if (node == null) return null;
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull()) return v.asText();
        }
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String field = it.next();
            for (String k : keys) {
                if (field.equalsIgnoreCase(k)) {
                    JsonNode v = node.get(field);
                    if (v != null && !v.isNull()) return v.asText();
                }
            }
        }
        return null;
    }

    /**
     * Attempts to upgrade a package while streaming output and progress updates to the provided entry.
     * This will update entry.status and entry.progress as lines/progress are received.
     */
    public ProcessResult updatePackageWithStreaming(String packageId, boolean silent, long timeoutSeconds,
                                                    SoftwareUpdateEntry entry, AtomicBoolean cancelled)
            throws IOException, CancellationException {
        List<String> args = new ArrayList<>(List.of(
                "upgrade", "--id", packageId, "--accept-source-agreements", "--accept-package-agreements"));
        if (silent) args.add("--silent");

        try {
            ProcessResult r = winget.runWithFallbackStreaming(
                    line -> Platform.runLater(() -> {
                        try {
                            if (entry != null) entry.setStatus(line == null ? "" : line);
                        } catch (Exception ignored) {}
                    }),
                    pct -> Platform.runLater(() -> {
                        try {
                            if (entry != null) entry.setProgress(pct);
                        } catch (Exception ignored) {}
                    }),
                    cancelled,
                    args.toArray(new String[0])
            );
            if (r.success()) return r;
            if (isInstallTechnologyMismatch(r)) {
                throw new IOException("INSTALL_TECHNOLOGY_MISMATCH");
            }
            return r;
        } catch (CancellationException cex) {
            throw cex;
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception ex) {
            throw new IOException("Failed to run winget streaming", ex);
        }
    }

    static boolean isInstallTechnologyMismatch(ProcessResult result) {
        String combined = "";
        if (result.stdout() != null) combined += result.stdout();
        if (result.stderr() != null) combined += result.stderr();
        return combined.contains("install technology is different")
                || combined.contains("0x8A150011");
    }
}
