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
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class SoftwareUpdateService {

    private final ProcessRunner runner = new ProcessRunner(600);
    private final WingetRunner winget = new WingetRunner(runner);
    private volatile String lastWindowsUpdateError = null;
    private volatile String lastWingetError = null;
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

    public String getLastWindowsUpdateError() { return lastWindowsUpdateError; }
    public String getLastWingetError() { return lastWingetError; }
    public void clearLastErrors() { lastWindowsUpdateError = null; lastWingetError = null; }

    public List<SoftwareUpdateEntry> scanForUpdates(AtomicBoolean cancelled) throws IOException, InterruptedException {
        List<SoftwareUpdateEntry> results = new ArrayList<>();
        lastWingetError = null;

        if (cancelled != null && cancelled.get()) return results;

        if (winget.supportsJsonOutput()) {
            ProcessResult r;
            try {
                r = winget.runWithFallback(120, cancelled,
                        "upgrade", "--source", "winget", "--accept-source-agreements",
                        "--accept-package-agreements", "--output", "json");
            } catch (RuntimeException re) {
                if (re.getCause() instanceof java.util.concurrent.CancellationException || cancelled != null && cancelled.get()) {
                    AppLogger.info("winget JSON scan cancelled");
                    return results;
                }
                lastWingetError = re.getMessage();
                throw re;
            }
            if (r == null) {
                lastWingetError = "winget returned no result";
            } else if (!r.success() && (r.stdout() == null || r.stdout().isBlank()) && (r.stderr() == null || r.stderr().isBlank())) {
                lastWingetError = "winget failed with exit " + r.exitCode();
            }
            if (r != null) {
                String stdout = r.stdout();
                if (stdout != null && !stdout.isBlank()) {
                    List<SoftwareUpdateEntry> jsonResults = parseJsonOutput(stdout);
                    if (!jsonResults.isEmpty()) {
                        results.addAll(jsonResults);
                    } else {
                        String trimmed = stdout.trim();
                        boolean looksJson = trimmed.startsWith("{") || trimmed.startsWith("[");
                        if (!looksJson) {
                            List<SoftwareUpdateEntry> fallback = parseTextOutput(stdout);
                            if (!fallback.isEmpty()) {
                                AppLogger.info("winget JSON parse yielded 0, text fallback yielded " + fallback.size());
                                results.addAll(fallback);
                            }
                        } else {
                            // JSON output but parser found 0 – may be schema change or "No applicable" message.
                            // Don't silently return empty; try text parse as safety net before declaring no updates.
                            if (trimmed.contains("No applicable") || trimmed.contains("No installed package")
                                    || trimmed.contains("No package found")) {
                                return results;
                            }
                            List<SoftwareUpdateEntry> fallback = parseTextOutput(stdout);
                            if (!fallback.isEmpty()) {
                                AppLogger.warning("winget JSON parse yielded 0 despite JSON-looking output; text fallback recovered " + fallback.size() + " entries");
                                results.addAll(fallback);
                            } else {
                                AppLogger.warning("winget JSON parse yielded 0 and text fallback also empty; treating as no updates. Raw output head: " + trimmed.substring(0, Math.min(300, trimmed.length())));
                            }
                        }
                    }
                }
            }
        } else {
            ProcessResult textResult;
            try {
                textResult = winget.runWithFallback(120, cancelled,
                        "upgrade", "--source", "winget");
            } catch (RuntimeException re) {
                if (re.getCause() instanceof java.util.concurrent.CancellationException || cancelled != null && cancelled.get()) {
                    AppLogger.info("winget text scan cancelled");
                    return results;
                }
                lastWingetError = re.getMessage();
                throw re;
            }
            if (textResult != null) {
                String stdout = textResult.stdout();
                if (stdout != null && !stdout.isBlank()) {
                    results.addAll(parseTextOutput(stdout));
                } else if (!textResult.success()) {
                    lastWingetError = "winget text scan exit " + textResult.exitCode() + ": " + textResult.combinedOutput();
                }
            }
            if (results.isEmpty() && textResult == null) {
                lastWingetError = "winget text scan returned null";
                AppLogger.warning("winget text scan failed");
            }
        }

        return results;
    }

    public List<SoftwareUpdateEntry> scanForUpdates(java.util.function.BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (cancelled == null) return scanForUpdates((AtomicBoolean) null);
        // Bridge supplier -> AtomicBoolean with polling monitor
        AtomicBoolean ab = new AtomicBoolean(cancelled.getAsBoolean());
        Thread monitor = new Thread(() -> {
            while (!ab.get() && !Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                try { if (cancelled.getAsBoolean()) ab.set(true); } catch (Exception ignored) {}
            }
        }, "scan-cancel-monitor");
        monitor.setDaemon(true);
        monitor.start();
        try {
            return scanForUpdates(ab);
        } finally {
            monitor.interrupt();
        }
    }

    public List<SoftwareUpdateEntry> scanForUpdates() throws IOException, InterruptedException {
        return scanForUpdates((AtomicBoolean) null);
    }

    List<SoftwareUpdateEntry> parseTextOutput(String stdout) {
        List<SoftwareUpdateEntry> out = new ArrayList<>();
        String trimmed = stdout == null ? "" : stdout.trim();
        if (trimmed.isEmpty()) return out;
        String lowerTrimmed = trimmed.toLowerCase();
        if (lowerTrimmed.contains("no applicable upgrades") || lowerTrimmed.contains("no installed package")
                || lowerTrimmed.contains("no applicable upgrade") || lowerTrimmed.contains("no package found")) {
            return out;
        }
        String[] lines = stdout.split("\\r?\\n");
        int headerIdx = -1;
        String headerLine = null;
        int separatorIdx = -1;
        String separatorLine = null;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            String lower = l.toLowerCase();
            boolean hasName = lower.contains("name");
            boolean hasVersion = lower.contains("version") || lower.contains("installed");
            boolean hasId = lower.contains("id") || lower.contains("identifier") || lower.contains("package");
            if (hasName && (hasVersion || hasId)) {
                headerIdx = i;
                headerLine = l;
                // Look ahead for separator line consisting of dashes/spaces
                if (i + 1 < lines.length) {
                    String next = lines[i + 1].trim();
                    if (next.startsWith("---") || next.matches("[-\\s]+") && next.contains("-")) {
                        separatorIdx = i + 1;
                        separatorLine = lines[i + 1];
                    }
                }
                break;
            }
        }
        // If no header found via keywords, try to find separator line and infer header above it
        if (headerIdx < 0) {
            for (int i = 0; i < lines.length; i++) {
                String t = lines[i].trim();
                if (t.startsWith("---") && t.replace("-", "").replace(" ", "").isEmpty() == false) {
                    // heuristic separator is mostly dashes
                    if (t.chars().filter(c -> c == '-').count() > 5) {
                        separatorIdx = i;
                        separatorLine = lines[i];
                        if (i > 0) {
                            headerIdx = i - 1;
                            headerLine = lines[i - 1];
                        }
                        break;
                    }
                }
            }
        }
        int start = headerIdx >= 0 ? headerIdx + 1 : 0;
        // If we found a separator, data starts after it
        if (separatorIdx >= 0) start = separatorIdx + 1;

        // Prefer separator-based column boundaries (robust to localized headers)
        int idxName = -1, idxId = -1, idxVersion = -1, idxAvailable = -1, idxSource = -1;
        int[] colStartsFromSep = null;
        if (separatorLine != null) {
            colStartsFromSep = parseSeparatorColumns(separatorLine);
        }
        if (headerLine != null) {
            String lowerHeader = headerLine.toLowerCase();
            // If we have separator columns, use header token positions mapped to separator segments for accuracy
            if (colStartsFromSep != null && colStartsFromSep.length >= 2) {
                // Map semantic columns to separator segments via header token search
                idxName = findColumnStart(lowerHeader, "name");
                idxId = findColumnStart(lowerHeader, "id", "identifier", "packageidentifier", "package id");
                idxVersion = findColumnStart(lowerHeader, "version", "installedversion", "installed", "current");
                idxAvailable = findColumnStart(lowerHeader, "available", "availableversion", "new", "upgradable");
                idxSource = findColumnStart(lowerHeader, "source");
                // Snap idx to nearest separator start to handle spacing variations
                if (idxName >= 0) idxName = snapToSeparator(idxName, colStartsFromSep, headerLine.length());
                if (idxId >= 0) idxId = snapToSeparator(idxId, colStartsFromSep, headerLine.length());
                if (idxVersion >= 0) idxVersion = snapToSeparator(idxVersion, colStartsFromSep, headerLine.length());
                if (idxAvailable >= 0) idxAvailable = snapToSeparator(idxAvailable, colStartsFromSep, headerLine.length());
                if (idxSource >= 0) idxSource = snapToSeparator(idxSource, colStartsFromSep, headerLine.length());
            } else {
                idxName = findColumnStart(lowerHeader, "name");
                idxId = findColumnStart(lowerHeader, "id", "identifier", "packageidentifier", "package id");
                idxVersion = findColumnStart(lowerHeader, "version", "installedversion", "installed", "current");
                idxAvailable = findColumnStart(lowerHeader, "available", "availableversion", "new", "upgradable");
                idxSource = findColumnStart(lowerHeader, "source");
            }
        }

        int[] colStarts = {idxName, idxId, idxVersion, idxAvailable, idxSource};
        int headerLen = headerLine != null ? headerLine.length() : 0;
        // If separator available, use its length as authoritative width
        int boundaryLen = separatorLine != null ? separatorLine.length() : headerLen;
        if (boundaryLen == 0 && headerLine != null) boundaryLen = headerLine.length();

        for (int i = start; i < lines.length; i++) {
            String l = lines[i];
            if (l.isBlank()) continue;
            String trimmedLine = l.trim();
            if (trimmedLine.startsWith("---")) continue;
            // Skip summary/footer lines in any locale that contain dashes or upgrade summary
            String lower = trimmedLine.toLowerCase();
            if (lower.contains("upgrades available") || lower.contains("package(s) have version")
                    || lower.startsWith("---") || lower.contains("winget upgrade")) continue;
            // Heuristic: skip lines that are clearly not data (e.g., "The upgrade ...")
            if (lower.startsWith("failed") || lower.startsWith("a newer")) continue;

            try {
                String name = null, id = null, version = null, available = null, source = null;
                if (headerLine != null && boundaryLen > 0) {
                    if (idxName >= 0) name = extractColumnAt(l, idxName, colStarts, 0, boundaryLen).trim();
                    if (idxId >= 0) id = extractColumnAt(l, idxId, colStarts, 1, boundaryLen).trim();
                    if (idxVersion >= 0) version = extractColumnAt(l, idxVersion, colStarts, 2, boundaryLen).trim();
                    if (idxAvailable >= 0) available = extractColumnAt(l, idxAvailable, colStarts, 3, boundaryLen).trim();
                    if (idxSource >= 0) source = extractColumnAt(l, idxSource, colStarts, 4, boundaryLen).trim();
                }
                // Fallback token split if column extraction failed to produce name
                if (name == null || name.isBlank()) {
                    String[] tokens = trimmedLine.split("\\s{2,}");
                    if (tokens.length < 3) {
                        // Last resort: split on single spaces but try to preserve name with spaces by assuming last 4 tokens are id/version/available/source
                        String[] singleTokens = trimmedLine.split("\\s+");
                        if (singleTokens.length >= 4) {
                            // Heuristic: id is typically dotted, version contains dots/digits
                            // Join leading tokens as name
                            int nameTokens = singleTokens.length - 4;
                            if (nameTokens < 1) nameTokens = 1;
                            name = String.join(" ", java.util.Arrays.copyOfRange(singleTokens, 0, nameTokens));
                            id = singleTokens[nameTokens];
                            version = singleTokens[nameTokens + 1];
                            available = singleTokens[nameTokens + 2];
                            if (singleTokens.length > nameTokens + 3) source = singleTokens[singleTokens.length - 1];
                        } else {
                            continue;
                        }
                    } else {
                        name = tokens[0];
                        if (tokens.length > 1) id = tokens[1];
                        if (tokens.length > 2) version = tokens[2];
                        if (tokens.length > 3) available = tokens[3];
                        if (tokens.length > 4) source = tokens[4];
                    }
                }

                // Normalize “unknown” available as blank
                if (available != null && "unknown".equalsIgnoreCase(available.trim())) available = "";

                // Source filter: only skip if source explicitly non-winget; blank means winget (since we passed --source winget)
                if (source != null && !source.isBlank() && !source.equalsIgnoreCase("winget")) {
                    // Allow empty source to pass (winget default), but skip msstore etc.
                    continue;
                }
                if (available == null || available.isBlank()) continue;
                if (available.equalsIgnoreCase("unknown")) continue;
                if (version == null) version = "";
                // Guard against NPE in downstream install (List.of throws on null) — skip entries with blank id
                if (id == null || id.isBlank()) {
                    AppLogger.warning("Skipping winget entry with missing id: name=" + name + " available=" + available);
                    continue;
                }
                if (name == null || name.isBlank()) name = id;
                if (!available.equals(version)) {
                    out.add(new SoftwareUpdateEntry(id, name, version, available));
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static int[] parseSeparatorColumns(String sep) {
        java.util.List<Integer> starts = new java.util.ArrayList<>();
        boolean inDash = false;
        for (int i = 0; i < sep.length(); i++) {
            char c = sep.charAt(i);
            if (c == '-') {
                if (!inDash) {
                    starts.add(i);
                    inDash = true;
                }
            } else {
                inDash = false;
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int snapToSeparator(int idx, int[] sepStarts, int headerLen) {
        if (sepStarts == null || sepStarts.length == 0) return idx;
        int best = idx;
        int bestDist = Integer.MAX_VALUE;
        for (int s : sepStarts) {
            int dist = Math.abs(s - idx);
            if (dist < bestDist) {
                bestDist = dist;
                best = s;
            }
        }
        // Only snap if within reasonable distance (e.g., 5 chars)
        return bestDist <= 5 ? best : idx;
    }

    private static int findColumnStart(String lowerHeader, String... keys) {
        for (String key : keys) {
            int idx = lowerHeader.indexOf(key.toLowerCase());
            if (idx >= 0) return idx;
        }
        return -1;
    }

    private static String extractColumnAt(String line, int startCol, int[] colStarts, int colIndex, int headerLen) {
        if (startCol >= line.length()) return "";
        int endCol = headerLen;
        for (int j = colIndex + 1; j < colStarts.length; j++) {
            if (colStarts[j] > startCol) {
                endCol = colStarts[j];
                break;
            }
        }
        if (startCol >= line.length()) return "";
        int end = Math.min(endCol, line.length());
        return line.substring(startCol, end).trim();
    }

    List<SoftwareUpdateEntry> parseJsonOutput(String stdout) {
        List<SoftwareUpdateEntry> results = new ArrayList<>();
        try {
            JsonNode root = JsonMapper.parseTree(stdout);
            JsonNode arrayNode = null;
            if (root.isArray()) {
                arrayNode = root;
            } else if (root.isObject()) {
                String[] knownArrayNames = {"Data", "Packages", "Upgrades", "Updates", "Results", "CatalogPackages", "Sources"};
                for (String name : knownArrayNames) {
                    JsonNode child = findCaseInsensitive(root, name);
                    if (child != null && child.isArray()) {
                        arrayNode = child;
                        break;
                    }
                    // Also handle case where value is object containing array (e.g., {"Data": {"Packages": [...]}})
                    if (child != null && child.isObject()) {
                        // Search one level deep
                        Iterator<String> subFields = child.fieldNames();
                        while (subFields.hasNext()) {
                            JsonNode sub = child.get(subFields.next());
                            if (sub != null && sub.isArray() && sub.size() > 0) {
                                arrayNode = sub;
                                break;
                            }
                        }
                        if (arrayNode != null) break;
                    }
                }
                // Fallback: any array field
                if (arrayNode == null) {
                    Iterator<String> fields = root.fieldNames();
                    while (fields.hasNext()) {
                        JsonNode child = root.get(fields.next());
                        if (child != null && child.isArray() && child.size() > 0) {
                            // Heuristic: array elements should be objects with Id/Name
                            JsonNode first = child.get(0);
                            if (first != null && first.isObject() && (first.has("Id") || first.has("PackageIdentifier") || first.has("Name"))) {
                                arrayNode = child;
                                break;
                            }
                        }
                        if (child != null && child.isObject()) {
                            // Deep search one more level
                            Iterator<String> subIt = child.fieldNames();
                            while (subIt.hasNext()) {
                                JsonNode sub = child.get(subIt.next());
                                if (sub != null && sub.isArray() && sub.size() > 0) {
                                    JsonNode first = sub.get(0);
                                    if (first != null && first.isObject() && (first.has("Id") || first.has("PackageIdentifier") || first.has("Name"))) {
                                        arrayNode = sub;
                                        break;
                                    }
                                }
                            }
                            if (arrayNode != null) break;
                        }
                    }
                }
                // Last resort: if root itself has Id/Name, treat as single element
                if (arrayNode == null && (root.has("Id") || root.has("PackageIdentifier") || findCaseInsensitive(root, "PackageIdentifier") != null)) {
                    arrayNode = JsonMapper.mapper().createArrayNode().add(root);
                }
            }
            if (arrayNode != null) {
                for (JsonNode el0 : arrayNode) {
                    JsonNode el = el0;
                    // Unwrap if element is wrapper like {"Package": {...}, "Version": ...}
                    // Some winget JSON nests actual data under "Package" or "Data"
                    if (el != null && el.isObject()) {
                        JsonNode nested = findCaseInsensitive(el, "Package");
                        if (nested != null && nested.isObject() && (findText(nested, "PackageIdentifier", "Id") != null)) {
                            el = nested;
                        }
                    }
                    String id = findText(el, "Id", "PackageIdentifier", "Moniker", "PackageId");
                    String name = findText(el, "Name", "PackageName", "Moniker");
                    String version = findText(el, "Version", "InstalledVersion", "CurrentVersion");
                    String available = findText(el, "AvailableVersion", "Available", "LatestVersion", "UpdateVersion");
                    String source = findText(el, "Source", "SourceName", "Repository");
                    // Fallback: if version still blank, check nested Version object
                    if ((version == null || version.isBlank()) && el != null) {
                        JsonNode verNode = findCaseInsensitive(el, "Version");
                        if (verNode != null && verNode.isObject()) {
                            version = findText(verNode, "Version", "InstalledVersion");
                        }
                    }
                    long sizeBytes = 0;
                    JsonNode sizeNode = findCaseInsensitive(el, "Size");
                    if (sizeNode == null) sizeNode = findCaseInsensitive(el, "PackageSize");
                    if (sizeNode == null) sizeNode = findCaseInsensitive(el, "DownloadSize");
                    if (sizeNode != null && !sizeNode.isNull()) {
                        if (sizeNode.isNumber()) sizeBytes = sizeNode.asLong(0);
                        else {
                            try { sizeBytes = Long.parseLong(sizeNode.asText().replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
                        }
                    }
                    // If id still null, try to extract from Available field structure
                    if ((id == null || id.isBlank()) && el != null) {
                        JsonNode idNode = findCaseInsensitive(el, "PackageIdentifier");
                        if (idNode != null) id = idNode.asText();
                    }
                    if ((name == null || name.isBlank()) && id != null) {
                        // Use id tail as name fallback
                        name = id.contains(".") ? id.substring(id.lastIndexOf('.') + 1) : id;
                    }
                    if (source != null && !source.isBlank() && !source.equalsIgnoreCase("winget")) {
                        // winget --source winget should still sometimes return source=winget, blank means winget
                        // Keep strictly winget, but allow blank/null to pass
                        continue;
                    }
                    if (available == null || available.isBlank() || "unknown".equalsIgnoreCase(available)) {
                        continue;
                    }
                    if (version == null) {
                        version = "";
                    }
                    if (id == null || id.isBlank()) {
                        AppLogger.warning("Skipping JSON entry with missing id: name=" + name);
                        continue;
                    }
                    if (name == null || name.isBlank()) name = id;
                    // Some winget JSON returns Available == Version when no update; filter
                    if (!available.equals(version)) {
                        results.add(new SoftwareUpdateEntry(id, name, version, available, "winget", null, sizeBytes));
                    }
                }
            } else {
                AppLogger.warning("parseJsonOutput: no array node found in JSON: " + stdout.substring(0, Math.min(500, stdout.length())));
            }
        } catch (Exception ex) {
            AppLogger.warning("parseJsonOutput failed: " + ex.getMessage());
        }
        return results;
    }

    private static JsonNode findCaseInsensitive(JsonNode node, String key) {
        if (node == null || key == null) return null;
        JsonNode direct = node.get(key);
        if (direct != null) return direct;
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String f = it.next();
            if (f.equalsIgnoreCase(key)) return node.get(f);
        }
        return null;
    }

    public List<Path> findCandidateInstallersForPackage(SoftwareUpdateEntry pkg, Instant since) {
        List<Path> candidates = new ArrayList<>();
        Set<String> exts = Set.of(".exe", ".msi", ".msix", ".msixbundle", ".zip", ".msu");
        String idToken = pkg.id() == null ? "" : pkg.id().toLowerCase().replace("-", "").replace("_", "");
        String name = pkg.getName() == null ? "" : pkg.getName().toLowerCase();
        // Collect roots: Downloads (recursive depth 2) + Winget cache
        List<Path> roots = new ArrayList<>();
        Path downloads = Paths.get(System.getProperty("user.home"), "Downloads");
        if (Files.isDirectory(downloads)) roots.add(downloads);
        String localApp = System.getenv("LOCALAPPDATA");
        if (localApp != null && !localApp.isBlank()) {
            Path wingetCache = Paths.get(localApp, "Packages", "Microsoft.DesktopAppInstaller_8wekyb3d8bbwe", "LocalState", "Downloads");
            if (Files.isDirectory(wingetCache)) roots.add(wingetCache);
            Path genericWinget = Paths.get(localApp, "Microsoft", "WinGet", "Packages");
            if (Files.isDirectory(genericWinget)) roots.add(genericWinget);
        }
        if (roots.isEmpty()) return candidates;
        for (Path root : roots) {
            try (var stream = Files.walk(root, 2)) {
                var it = stream.filter(Files::isRegularFile).iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    try {
                        String fileNameRaw = p.getFileName().toString().toLowerCase();
                        String fileName = fileNameRaw.replace("-", "").replace("_", "").replace(" ", "");
                        boolean extOk = exts.stream().anyMatch(fileNameRaw::endsWith);
                        if (!extOk) continue;
                        FileTime ft = Files.getLastModifiedTime(p);
                        Instant modified = ft.toInstant();
                        if (modified.isBefore(since)) continue;
                        boolean containsToken = false;
                        // Stricter heuristic to avoid deleting unrelated user files (data loss).
                        // Require longer tokens and at least 2 matching signals for name-based match.
                        if (!idToken.isBlank()) {
                            String idBase = idToken.contains(".") ? idToken.substring(idToken.lastIndexOf('.') + 1) : idToken;
                            String normBase = idBase.replace(".", "");
                            String normId = idToken.replace(".", "");
                            // Only consider id-based match if token is long enough to be distinctive
                            if (normBase.length() >= 5 && fileName.contains(normBase)) {
                                containsToken = true;
                            } else if (normId.length() >= 6 && fileName.contains(normId)) {
                                containsToken = true;
                            } else if (pkg.id() != null && pkg.id().length() >= 6 && fileNameRaw.contains(pkg.id().toLowerCase())) {
                                containsToken = true;
                            }
                        }
                        if (!containsToken && !name.isBlank()) {
                            String[] nameWords = name.split("\\s+");
                            // Only count meaningful words (4+ chars) and require higher bar
                            long matchedWords = java.util.Arrays.stream(nameWords)
                                    .map(w -> w.replace("-", "").replace("_", ""))
                                    .filter(w -> w.length() >= 4 && fileName.contains(w))
                                    .count();
                            int required = nameWords.length == 1 ? 1 : 2;
                            // For single-word packages, require longer name to be distinctive
                            if (nameWords.length == 1) {
                                if (nameWords[0].length() >= 6 && fileName.contains(nameWords[0].replace("-", "").replace("_", ""))) {
                                    // Still require idToken hint for single-word to reduce false positives
                                    // If idToken not matched, be more conservative: need 6+ char word
                                    containsToken = true;
                                    // If name is very generic (e.g., "Code", "Zoom", "Slack" len 4-5), require additional signal:
                                    // check that fileName also contains version-like pattern or is under winget cache (already ensured)
                                    // For Downloads root, we already require 6+ chars, so "code" (4) won't match.
                                }
                            } else {
                                if (matchedWords >= required) {
                                    containsToken = true;
                                }
                            }
                        }
                        if (!containsToken) continue;
                        // Additional safety: skip tiny files that are unlikely to be installers (<100KB) to reduce false positives
                        try {
                            long fsize = Files.size(p);
                            if (fsize < 100 * 1024) continue;
                        } catch (Exception ignored) {}
                        candidates.add(p);
                    } catch (Exception e) {
                        AppLogger.warning("Could not evaluate candidate installer: " + p + " -> " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                AppLogger.warning("Failed to enumerate candidates in " + root + ": " + e.getMessage());
            }
        }
        return candidates;
    }

    public java.util.Map<SoftwareUpdateEntry, List<Path>> findCandidateInstallersForPackages(
            List<SoftwareUpdateEntry> packages, Instant since) {
        java.util.Map<SoftwareUpdateEntry, List<Path>> result = new java.util.LinkedHashMap<>();
        for (SoftwareUpdateEntry pkg : packages) {
            List<Path> candidates = findCandidateInstallersForPackage(pkg, since);
            if (!candidates.isEmpty()) {
                result.put(pkg, candidates);
            }
        }
        return result;
    }

    public List<Path> deleteInstallerFiles(List<Path> files) {
        List<Path> deleted = new ArrayList<>();
        // Allowed roots for safety – never delete outside these
        Set<Path> allowedRoots = new java.util.HashSet<>();
        try {
            Path dl = Paths.get(System.getProperty("user.home"), "Downloads");
            if (Files.isDirectory(dl)) allowedRoots.add(dl.toRealPath());
        } catch (Exception ignored) {}
        String localApp = System.getenv("LOCALAPPDATA");
        if (localApp != null && !localApp.isBlank()) {
            try {
                Path wc = Paths.get(localApp, "Packages", "Microsoft.DesktopAppInstaller_8wekyb3d8bbwe", "LocalState", "Downloads");
                if (Files.isDirectory(wc)) allowedRoots.add(wc.toRealPath());
                Path gw = Paths.get(localApp, "Microsoft", "WinGet", "Packages");
                if (Files.isDirectory(gw)) allowedRoots.add(gw.toRealPath());
            } catch (Exception ignored) {}
        }
        for (Path p : files) {
            try {
                // Safety: ensure file is still under allowed roots
                Path real = p.toRealPath();
                boolean underAllowed = allowedRoots.stream().anyMatch(root -> real.startsWith(root));
                if (!underAllowed) {
                    AppLogger.warning("Skipping delete outside allowed roots: " + p);
                    continue;
                }
                // Double-check extension and size
                String fn = p.getFileName().toString().toLowerCase();
                boolean extOk = Set.of(".exe", ".msi", ".msix", ".msixbundle", ".zip", ".msu").stream().anyMatch(fn::endsWith);
                if (!extOk) {
                    AppLogger.warning("Skipping delete with unexpected extension: " + p);
                    continue;
                }
                long sz = Files.size(p);
                if (sz < 100 * 1024) {
                    AppLogger.warning("Skipping delete of tiny file (not installer): " + p + " size=" + sz);
                    continue;
                }
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

    public List<SoftwareUpdateEntry> scanForWindowsUpdates(AtomicBoolean cancelled) {
        List<SoftwareUpdateEntry> results = new ArrayList<>();
        lastWindowsUpdateError = null;
        if (!com.sbtools.util.AppPaths.isWindows()) {
            return results;
        }
        if (cancelled != null && cancelled.get()) return results;
        try {
            Path script = PowerShellScripts.resolve("wu-search-updates.ps1");
            ProcessResult result;
            try {
                result = runner.run(ProcessRunner.powershellScript(script.toString()), 120, cancelled);
            } catch (java.util.concurrent.CancellationException ce) {
                AppLogger.info("Windows Update scan cancelled");
                return results;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                AppLogger.info("Windows Update scan interrupted");
                return results;
            }
            if (cancelled != null && cancelled.get()) return results;
            if (!result.success()) {
                String out = result.combinedOutput();
                // Distinguish real failure from cancellation/timeout vs genuine 0 updates
                if (out != null && (out.toLowerCase().contains("timed out") || out.toLowerCase().contains("cancelled"))) {
                    AppLogger.info("Windows Update search cancelled/timed out: " + out);
                    // Not an error – treat as empty, not failure
                } else {
                    lastWindowsUpdateError = "Windows Update search failed (exit " + result.exitCode() + "): " + out;
                    AppLogger.warning(lastWindowsUpdateError);
                }
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
        } catch (java.util.concurrent.CancellationException ce) {
            AppLogger.info("Windows Update scan cancelled");
        } catch (Exception e) {
            if (cancelled != null && cancelled.get()) {
                AppLogger.info("Windows Update scan cancelled (exception): " + e.getMessage());
            } else {
                lastWindowsUpdateError = "Windows Update scan failed: " + e.getMessage();
                AppLogger.warning(lastWindowsUpdateError);
            }
        }
        return results;
    }

    public List<SoftwareUpdateEntry> scanForWindowsUpdates(java.util.function.BooleanSupplier cancelled) {
        if (cancelled == null) return scanForWindowsUpdates((AtomicBoolean) null);
        AtomicBoolean ab = new AtomicBoolean(cancelled.getAsBoolean());
        Thread monitor = new Thread(() -> {
            while (!ab.get() && !Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                try { if (cancelled.getAsBoolean()) ab.set(true); } catch (Exception ignored) {}
            }
        }, "wu-cancel-monitor");
        monitor.setDaemon(true);
        monitor.start();
        try {
            return scanForWindowsUpdates(ab);
        } finally {
            monitor.interrupt();
        }
    }

    public List<SoftwareUpdateEntry> scanForWindowsUpdates() {
        return scanForWindowsUpdates((AtomicBoolean) null);
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
        String kbArticle = findText(n, "kbArticle");

        String displayVersion = kbArticle != null && !kbArticle.isBlank()
                ? "KB" + kbArticle
                : (version != null ? version : "");
        String name = title != null ? title : (description != null ? description : "Windows Update");
        if (name == null || name.isBlank()) name = "Windows Update";
        // updateId may be null if WU COM returned malformed JSON; use title as fallback id for display but mark as non-installable
        String effectiveId = (updateId == null || updateId.isBlank()) ? null : updateId;
        if (effectiveId == null) {
            AppLogger.warning("WU entry missing updateId: title=" + title + " kb=" + kbArticle);
            // Still create entry but install will be blocked by validation; use title hash as placeholder to avoid NPE in table
            effectiveId = "WU-" + Math.abs((title != null ? title : "unknown").hashCode());
        }
        return new SoftwareUpdateEntry(
                effectiveId, name, "", displayVersion,
                "WindowsUpdate", updateId, sizeBytes);
    }

    public ProcessResult installWindowsUpdate(String updateId, long timeoutSeconds) throws IOException, InterruptedException {
        return installWindowsUpdate(updateId, timeoutSeconds, null);
    }

    public ProcessResult installWindowsUpdate(String updateId, long timeoutSeconds, AtomicBoolean cancelled)
            throws IOException, InterruptedException, CancellationException {
        if (updateId == null || updateId.isBlank()) {
            throw new IOException("Missing Windows Update identifier; cannot install");
        }
        Path script = PowerShellScripts.resolve("wu-install.ps1");
        if (cancelled == null) {
            return runner.run(ProcessRunner.powershellScript(script.toString(), updateId), timeoutSeconds);
        }
        return runner.runStreaming(
                ProcessRunner.powershellScript(script.toString(), updateId),
                line -> {},
                pct -> {},
                cancelled,
                timeoutSeconds
        );
    }

    /**
     * Returns true if the given process result indicates a restart is required to finish
     * the installation. Handles the JSON output of wu-install.ps1 (field "rebootRequired")
     * and falls back to a case-insensitive text search for robustness.
     */
    public static boolean isRebootRequired(ProcessResult result) {
        if (result == null) return false;
        String output = result.combinedOutput();
        if (output == null || output.isBlank()) return false;
        try {
            JsonNode root = JsonMapper.parseTree(output);
            JsonNode reboot = root.has("rebootRequired") ? root.get("rebootRequired") : null;
            if (reboot != null) {
                if (reboot.isBoolean()) return reboot.asBoolean(false);
                if (reboot.isTextual()) return Boolean.parseBoolean(reboot.asText());
            }
        } catch (Exception ignored) {
        }
        return output.toLowerCase().contains("rebootrequired");
    }

    /**
     * Runs winget and Windows Update scans concurrently.
     * Supports cancellation via cancelled supplier and enforces an overall timeout.
     * Returns combined results; partial results are returned if one scan completes before cancellation.
     *
     * @param cancelled  supplier checked for cancellation; called repeatedly during scan (may be null)
     * @param onWingetDone  optional callback with winget result count (called on scan thread)
     * @param onWuDone      optional callback with WU result count (called on scan thread)
     */
    public List<SoftwareUpdateEntry> scanAllConcurrent(java.util.function.BooleanSupplier cancelled,
                                                        java.util.function.IntConsumer onWingetDone,
                                                        java.util.function.IntConsumer onWuDone) {
        return scanAllConcurrent(cancelled, onWingetDone, onWuDone, 150);
    }

    public List<SoftwareUpdateEntry> scanAllConcurrent(java.util.function.BooleanSupplier cancelled,
                                                        java.util.function.IntConsumer onWingetDone,
                                                        java.util.function.IntConsumer onWuDone,
                                                        long overallTimeoutSeconds) {
        if (cancelled == null) return scanAllConcurrent((AtomicBoolean) null, onWingetDone, onWuDone, overallTimeoutSeconds);
        // Bridge supplier -> AtomicBoolean
        AtomicBoolean ab = new AtomicBoolean(cancelled.getAsBoolean());
        Thread monitor = new Thread(() -> {
            while (!ab.get() && !Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                try { if (cancelled.getAsBoolean()) ab.set(true); } catch (Exception ignored) {}
            }
        }, "scanAll-cancel-monitor");
        monitor.setDaemon(true);
        monitor.start();
        try {
            return scanAllConcurrent(ab, onWingetDone, onWuDone, overallTimeoutSeconds);
        } finally {
            monitor.interrupt();
        }
    }

    public List<SoftwareUpdateEntry> scanAllConcurrent(AtomicBoolean cancelled,
                                                        java.util.function.IntConsumer onWingetDone,
                                                        java.util.function.IntConsumer onWuDone) {
        return scanAllConcurrent(cancelled, onWingetDone, onWuDone, 150);
    }

    public List<SoftwareUpdateEntry> scanAllConcurrent(AtomicBoolean cancelled,
                                                        java.util.function.IntConsumer onWingetDone,
                                                        java.util.function.IntConsumer onWuDone,
                                                        long overallTimeoutSeconds) {
        List<SoftwareUpdateEntry> allUpdates = new ArrayList<>();
        // Internal flag: carries user cancel + timeout signal to ProcessRunner without polluting caller's flag (B1 fix)
        AtomicBoolean internalCancelled = new AtomicBoolean(cancelled != null && cancelled.get());
        Thread cancelMonitor = null;
        if (cancelled != null) {
            cancelMonitor = new Thread(() -> {
                while (!internalCancelled.get() && !Thread.currentThread().isInterrupted()) {
                    try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    try { if (cancelled.get()) internalCancelled.set(true); } catch (Exception ignored) {}
                }
            }, "scanAll-cancel-monitor");
            cancelMonitor.setDaemon(true);
            cancelMonitor.start();
        }
        final AtomicBoolean safeCancelled = internalCancelled;

        CompletableFuture<List<SoftwareUpdateEntry>> wingetFuture = CompletableFuture.supplyAsync(() -> {
            if (!winget.isAvailable()) {
                if (onWingetDone != null) onWingetDone.accept(0);
                return List.<SoftwareUpdateEntry>of();
            }
            if (safeCancelled.get()) {
                if (onWingetDone != null) onWingetDone.accept(0);
                return List.<SoftwareUpdateEntry>of();
            }
            try {
                List<SoftwareUpdateEntry> result = scanForUpdates(safeCancelled);
                if (!safeCancelled.get() && onWingetDone != null) onWingetDone.accept(result.size());
                else if (onWingetDone != null && safeCancelled.get()) onWingetDone.accept(result.size());
                return result;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                AppLogger.info("winget scan interrupted");
                if (onWingetDone != null) onWingetDone.accept(0);
                return List.<SoftwareUpdateEntry>of();
            } catch (Exception ex) {
                // Check if cancellation caused the exception
                if (safeCancelled.get()) {
                    AppLogger.info("winget scan cancelled: " + ex.getMessage());
                    if (onWingetDone != null) onWingetDone.accept(0);
                    return List.<SoftwareUpdateEntry>of();
                }
                AppLogger.warning("winget scan failed: " + ex.getMessage());
                if (onWingetDone != null) onWingetDone.accept(0);
                return List.<SoftwareUpdateEntry>of();
            }
        }, scanExecutor);

        CompletableFuture<List<SoftwareUpdateEntry>> wuFuture = CompletableFuture.supplyAsync(() -> {
            if (safeCancelled.get()) {
                if (onWuDone != null) onWuDone.accept(0);
                return List.<SoftwareUpdateEntry>of();
            }
            try {
                List<SoftwareUpdateEntry> result = scanForWindowsUpdates(safeCancelled);
                if (onWuDone != null) onWuDone.accept(result.size());
                return result;
            } catch (Exception ex) {
                if (safeCancelled.get()) {
                    AppLogger.info("Windows Update scan cancelled");
                    if (onWuDone != null) onWuDone.accept(0);
                    return List.<SoftwareUpdateEntry>of();
                }
                AppLogger.warning("Windows Update scan failed: " + ex.getMessage());
                if (onWuDone != null) onWuDone.accept(0);
                return List.<SoftwareUpdateEntry>of();
            }
        }, scanExecutor);

        CompletableFuture<Void> all = CompletableFuture.allOf(wingetFuture, wuFuture);
        try {
            long timeout = Math.max(30, overallTimeoutSeconds);
            long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeout);
            while (!all.isDone()) {
                if (cancelled != null && cancelled.get()) {
                    AppLogger.info("Parallel scan cancelled by user");
                    internalCancelled.set(true);
                    wingetFuture.cancel(true);
                    wuFuture.cancel(true);
                    break;
                }
                if (System.nanoTime() > deadlineNanos) {
                    AppLogger.warning("Parallel scan timed out after " + timeout + "s");
                    // Signal runner to kill orphan winget/WU processes without polluting caller's flag (B1)
                    internalCancelled.set(true);
                    wingetFuture.cancel(true);
                    wuFuture.cancel(true);
                    break;
                }
                try {
                    all.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    // poll again
                }
            }
            // Collect whatever completed (non-blocking)
            if (wingetFuture.isDone() && !wingetFuture.isCancelled() && !wingetFuture.isCompletedExceptionally()) {
                try {
                    List<SoftwareUpdateEntry> r = wingetFuture.getNow(List.of());
                    if (r != null) allUpdates.addAll(r);
                } catch (Exception ignored) {}
            } else if (wingetFuture.isCompletedExceptionally()) {
                try { wingetFuture.join(); } catch (Exception ignored) {}
            }
            if (wuFuture.isDone() && !wuFuture.isCancelled() && !wuFuture.isCompletedExceptionally()) {
                try {
                    List<SoftwareUpdateEntry> r = wuFuture.getNow(List.of());
                    if (r != null) allUpdates.addAll(r);
                } catch (Exception ignored) {}
            } else if (wuFuture.isCompletedExceptionally()) {
                try { wuFuture.join(); } catch (Exception ignored) {}
            }
        } catch (java.util.concurrent.CancellationException ex) {
            AppLogger.info("Parallel scan cancelled");
        } catch (Exception ex) {
            AppLogger.warning("Parallel scan failed: " + ex.getMessage());
        } finally {
            if (cancelMonitor != null) cancelMonitor.interrupt();
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
        if (packageId == null || packageId.isBlank()) {
            throw new IOException("Missing package identifier; cannot run winget upgrade");
        }
        List<String> args = new ArrayList<>(List.of(
                "upgrade", "--id", packageId, "--source", "winget",
                "--accept-source-agreements", "--accept-package-agreements",
                "--force"));
        if (silent) args.add("--silent");

        try {
            AtomicLong lastStatusUpdate = new AtomicLong(0);
            ProcessResult r = winget.runWithFallbackStreaming(
                    line -> {
                        long now = System.currentTimeMillis();
                        if (now - lastStatusUpdate.get() >= 100 || line == null) {
                            lastStatusUpdate.set(now);
                            Platform.runLater(() -> {
                                try {
                                    if (entry != null) entry.setStatus(line == null ? "" : line);
                                } catch (Exception ignored) {}
                            });
                        }
                    },
                    pct -> Platform.runLater(() -> {
                        try {
                            if (entry != null) entry.setProgress(pct);
                        } catch (Exception ignored) {}
                    }),
                    cancelled,
                    timeoutSeconds,
                    args.toArray(new String[0])
            );
            AppLogger.info("winget upgrade result for " + packageId + ": exitCode=" + r.exitCode()
                    + " output=" + (r.stdout() != null ? r.stdout().substring(0, Math.min(500, r.stdout().length())) : "null"));
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
