package com.sbtools.software;

import com.sbtools.util.AppLogger;
import com.sbtools.util.CancelBridge;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;

import java.io.IOException;
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

        // List mode takes NO --output/--accept-* flags. `upgrade --output json` is rejected by
        // current winget (it prints usage text, which the text parser then misread as phantom
        // rows) and --accept-package-agreements flips list mode into a "Multiple installed
        // packages found" Name/Id-only table on winget >= 1.29. List bare instead.
        ProcessResult r;
        try {
            r = winget.runWithFallback(120, cancelled,
                    "upgrade", "--source", "winget");
        } catch (RuntimeException re) {
            if (re.getCause() instanceof java.util.concurrent.CancellationException || cancelled != null && cancelled.get()) {
                AppLogger.info("winget scan cancelled");
                return results;
            }
            lastWingetError = re.getMessage();
            throw re;
        }
        if (r == null) {
            lastWingetError = "winget returned no result";
        } else if (WingetRunner.isLauncherFailure(r)) {
            // The launcher never started winget (missing binary, "not recognized", usage text):
            // surface as an error instead of parsing help text into phantom rows.
            String detail = r.combinedOutput();
            if (detail != null && detail.length() > 300) detail = detail.substring(0, 300) + "...";
            lastWingetError = "winget list failed (exit " + r.exitCode() + "): " + (detail == null ? "" : detail.strip());
        } else {
            String stdout = r.stdout();
            if (stdout != null && !stdout.isBlank()) {
                results.addAll(parseTextOutput(stdout));
            } else if (!r.success()) {
                lastWingetError = "winget text scan exit " + r.exitCode() + ": " + r.combinedOutput();
            }
        }

        return results;
    }

    public List<SoftwareUpdateEntry> scanForUpdates(java.util.function.BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (cancelled == null) return scanForUpdates((AtomicBoolean) null);
        // Bridge supplier -> AtomicBoolean (shared helper; identical 100ms daemon-monitor semantics)
        try (CancelBridge bridge = CancelBridge.bridge(cancelled, "scan-cancel-monitor")) {
            return scanForUpdates(bridge.flag());
        }
    }

    public List<SoftwareUpdateEntry> scanForUpdates() throws IOException, InterruptedException {
        return scanForUpdates((AtomicBoolean) null);
    }

    List<SoftwareUpdateEntry> parseTextOutput(String stdout) {
        List<SoftwareUpdateEntry> out = new ArrayList<>();
        int skippedNonWinget = 0;
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

        // Locale-robust fallback: winget localizes header TEXT (e.g. German "Verfuegbar"/
        // "Quelle") but never reorders columns (Name, Id, Version, Available[, Source]), and
        // titles stay single words — so a 4-word header maps to [N,I,V,A], 5 words to
        // [N,I,V,A,S]. Only fills roles the keyword search missed; anything else keeps
        // the old behavior instead of risking misalignment on multi-word titles.
        if (headerLine != null && (idxAvailable < 0 || idxSource < 0 || idxVersion < 0 || idxId < 0 || idxName < 0)) {
            int[] wordStarts = parseHeaderWordStarts(headerLine);
            if (wordStarts.length == 4 || wordStarts.length == 5) {
                if (idxName < 0) idxName = wordStarts[0];
                if (idxId < 0 && wordStarts.length > 1) idxId = wordStarts[1];
                if (idxVersion < 0 && wordStarts.length > 2) idxVersion = wordStarts[2];
                if (idxAvailable < 0 && wordStarts.length > 3) idxAvailable = wordStarts[3];
                if (idxSource < 0 && wordStarts.length > 4) idxSource = wordStarts[4];
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
            // winget appends an "explicit targeting" footer (second table, narrower columns)
            // for packages that `upgrade --all` skips. Everything from here on is footer:
            // parsing it with main-table column positions yields garbage ids (e.g. a sliced
            // "hon 3.14.7 64-bit) CondaForge.Mini") whose install always fails, and duplicate
            // installed copies behind those rows cannot be targeted by --id anyway.
            if (lower.contains("explicit targeting")) break;
            // Any-locale backstop: a second header-like line after data started means the
            // footer table began (its preamble is localized, e.g. German, so the fast path
            // above cannot match it). Headers carry no digits; data rows virtually always do.
            if (!out.isEmpty() && looksLikeTableHeader(trimmedLine)) break;
            if (lower.contains("upgrades available") || lower.contains("package(s) have version")
                    || lower.startsWith("---") || lower.contains("winget upgrade")) continue;
            // Heuristic: skip lines that are clearly not data (e.g., "The upgrade ...")
            if (lower.startsWith("failed") || lower.startsWith("a newer")) continue;

            try {
                String name = null, id = null, version = null, available = null, source = null;
                if (headerLine != null && boundaryLen > 0) {
                    if (idxName >= 0) name = extractColumnAt(l, idxName, colStarts, boundaryLen).trim();
                    if (idxId >= 0) id = extractColumnAt(l, idxId, colStarts, boundaryLen).trim();
                    if (idxVersion >= 0) version = extractColumnAt(l, idxVersion, colStarts, boundaryLen).trim();
                    if (idxAvailable >= 0) available = extractColumnAt(l, idxAvailable, colStarts, boundaryLen).trim();
                    if (idxSource >= 0) source = extractColumnAt(l, idxSource, colStarts, boundaryLen).trim();
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

                // Source filter: this tab only manages the winget source (install runs
                // with --source winget). Non-winget rows (e.g. msstore) are skipped
                // explicitly and counted so the "up to date" status never silently
                // hides them -- see ViewModel status wording ("Store apps not checked").
                if (source != null && !source.isBlank() && !source.equalsIgnoreCase("winget")) {
                    // Allow empty source to pass (winget default), but skip msstore etc.
                    skippedNonWinget++;
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
                // winget ids never contain whitespace: one inside means the line was sliced
                // with wrong column positions (localized header, wrapped prose) — skip it
                // instead of queuing an uninstallable phantom row.
                if (id.matches(".*\\s.*")) continue;
                // Summary/note lines that survive to here (localized "N upgrades available"
                // etc.) carry no version digits in either version field — real rows always do.
                if (!version.matches(".*\\d.*") && !available.matches(".*\\d.*")) continue;
                if (name == null || name.isBlank()) name = id;
                if (!available.equals(version)) {
                    out.add(new SoftwareUpdateEntry(id, name, version, available));
                }
            } catch (Exception ignored) {
            }
        }
        if (skippedNonWinget > 0) {
            AppLogger.info("Skipped " + skippedNonWinget + " non-winget (e.g. msstore) row(s): Store apps are not managed by this tab");
        }
        return out;
    }

    // Start offset of every whitespace-separated word in a header line.
    private static int[] parseHeaderWordStarts(String header) {
        java.util.List<Integer> starts = new java.util.ArrayList<>();
        boolean inWord = false;
        for (int i = 0; i < header.length(); i++) {
            if (header.charAt(i) <= ' ') {
                inWord = false;
            } else if (!inWord) {
                starts.add(i);
                inWord = true;
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] parseSeparatorColumns(String sep) {        java.util.List<Integer> starts = new java.util.ArrayList<>();
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

    // Column end = nearest other column start to the right (order-proof: holds even when
    // roles were mapped positionally for a localized header instead of by keyword order).
    private static String extractColumnAt(String line, int startCol, int[] colStarts, int headerLen) {
        if (startCol < 0 || startCol >= line.length()) return "";
        // End = nearest other column start to the right, else end of line: values may
        // overflow the header/separator width (e.g. long versions in the trailing column)
        // and must not be truncated (was: capped at headerLen, last char(s) lost).
        int endCol = line.length();
        for (int s : colStarts) {
            if (s > startCol && s < endCol) endCol = s;
        }
        int end = Math.min(endCol, line.length());
        if (startCol >= end) return "";
        return line.substring(startCol, end).trim();
    }

    // True for a repeated table-header line (footer table in any locale): header keywords
    // present but no digits. Data rows virtually always carry version digits.
    private static boolean looksLikeTableHeader(String trimmedLine) {
        if (trimmedLine == null || trimmedLine.isBlank() || trimmedLine.matches(".*\\d.*")) return false;
        String lower = trimmedLine.toLowerCase();
        boolean hasName = lower.contains("name");
        boolean hasVersion = lower.contains("version") || lower.contains("installed");
        boolean hasId = lower.contains("id") || lower.contains("identifier") || lower.contains("package");
        return hasName && (hasVersion || hasId);
    }

    List<SoftwareUpdateEntry> parseJsonOutput(String stdout) {
        List<SoftwareUpdateEntry> results = new ArrayList<>();
        int skippedNonWinget = 0;
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
                        // winget --source winget should still sometimes return source=winget, blank means winget.
                        // Non-winget rows (msstore) are not managed by this tab: skip
                        // explicitly (counted + logged below) instead of silently vanishing.
                        skippedNonWinget++;
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
            if (skippedNonWinget > 0) {
                AppLogger.info("Skipped " + skippedNonWinget + " non-winget JSON row(s): Store apps are not managed by this tab");
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
                    SoftwareUpdateEntry parsed = parseWindowsUpdateEntry(n);
                    // Missing updateId entries are non-installable phantom rows: drop at the source.
                    if (parsed != null) results.add(parsed);
                }
            } else if (root.isObject()) {
                SoftwareUpdateEntry parsed = parseWindowsUpdateEntry(root);
                if (parsed != null) results.add(parsed);
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
        try (CancelBridge bridge = CancelBridge.bridge(cancelled, "wu-cancel-monitor")) {
            return scanForWindowsUpdates(bridge.flag());
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
        // updateId is the only installable identifier for WU. Entries without it can never be
        // installed (validation blocks them), so drop them here instead of surfacing phantom
        // rows with synthetic placeholder ids.
        if (updateId == null || updateId.isBlank()) {
            AppLogger.warning("Skipping WU entry with missing updateId: title=" + title + " kb=" + kbArticle);
            return null;
        }
        return new SoftwareUpdateEntry(
                updateId, name, "", displayVersion,
                "WindowsUpdate", updateId, sizeBytes);
    }

    public ProcessResult installWindowsUpdate(String updateId, long timeoutSeconds) throws IOException, InterruptedException {
        return installWindowsUpdate(updateId, timeoutSeconds, null);
    }

    public ProcessResult installWindowsUpdate(String updateId, long timeoutSeconds, AtomicBoolean cancelled)
            throws IOException, InterruptedException, CancellationException {
        return installWindowsUpdate(updateId, timeoutSeconds, cancelled, null);
    }

    /**
     * Installs a Windows Update while streaming progress to the given entry (may be null).
     * Unlike the previous fire-and-forget variant, output lines update {@code entry} status
     * (throttled) and Downloading/Installing markers set indeterminate progress, so the row
     * does not sit silent for the whole (potentially hour-long) install.
     */
    public ProcessResult installWindowsUpdate(String updateId, long timeoutSeconds, AtomicBoolean cancelled,
                                              SoftwareUpdateEntry entry)
            throws IOException, InterruptedException, CancellationException {
        if (updateId == null || updateId.isBlank()) {
            throw new IOException("Missing Windows Update identifier; cannot install");
        }
        Path script = PowerShellScripts.resolve("wu-install.ps1");
        if (cancelled == null && entry == null) {
            return runner.run(ProcessRunner.powershellScript(script.toString(), updateId), timeoutSeconds);
        }
        AtomicLong lastStatusUpdate = new AtomicLong(0);
        return runner.runStreaming(
                ProcessRunner.powershellScript(script.toString(), updateId),
                line -> {
                    if (entry == null || line == null) return;
                    long now = System.currentTimeMillis();
                    if (now - lastStatusUpdate.get() >= 500) {
                        lastStatusUpdate.set(now);
                        String snapshot = line;
                        // Skip echoing the final JSON blob as a status line.
                        String trimmed = snapshot.trim();
                        if (trimmed.startsWith("{") && trimmed.contains("rebootRequired")) return;
                        Platform.runLater(() -> {
                            try {
                                entry.setStatus(snapshot.length() > 160 ? snapshot.substring(0, 160) + "..." : snapshot);
                            } catch (Exception ignored) {}
                        });
                    }
                },
                pct -> {
                    if (entry == null) return;
                    Platform.runLater(() -> {
                        try {
                            entry.setProgress(pct);
                        } catch (Exception ignored) {}
                    });
                },
                cancelled,
                timeoutSeconds
        );
    }

    /**
     * Returns true if the given process result indicates a restart is required to finish
     * the installation. Handles the JSON output of wu-install.ps1 (field "rebootRequired"),
     * MSI/winget reboot exit codes (3010, 1641), and reboot phrasing in either stream.
     * The check is tolerant of progress lines surrounding the final JSON object, so
     * wu-install.ps1 may emit human-readable progress before the JSON result.
     */
    public static boolean isRebootRequired(ProcessResult result) {
        if (result == null) return false;
        if (isRebootExitCode(result.exitCode())) return true;
        String output = result.combinedOutput();
        if (output == null || output.isBlank()) return false;
        // Try to extract the trailing JSON object containing rebootRequired (progress lines may precede it).
        try {
            JsonNode root = tryParseRebootJson(output);
            if (root != null) {
                JsonNode reboot = root.has("rebootRequired") ? root.get("rebootRequired") : null;
                if (reboot == null) {
                    // Case-insensitive fallback within the parsed fragment.
                    java.util.Iterator<String> fields = root.fieldNames();
                    while (fields.hasNext()) {
                        String f = fields.next();
                        if (f.equalsIgnoreCase("rebootRequired")) {
                            reboot = root.get(f);
                            break;
                        }
                    }
                }
                if (reboot != null) {
                    if (reboot.isBoolean()) return reboot.asBoolean(false);
                    if (reboot.isNumber()) return reboot.asInt(0) != 0;
                    if (reboot.isTextual()) return Boolean.parseBoolean(reboot.asText().trim());
                }
            }
        } catch (Exception ignored) {
        }
        // Negation-aware fallback for non-JSON phrasing. "No reboot required",
        // "reboot not required" and '"rebootRequired":false' must NOT trigger a
        // batch abort + forced reboot prompt. JSON with an explicit boolean was
        // already handled above; this covers free-text winget/MSI wrappers.
        // Delegates to the same line-scoped logic as WingetRunner (duplicated
        // to avoid a class cycle).
        if (containsAffirmativeRebootPhrasing(output)) return true;
        return false;
    }

    /**
     * Line-scoped, negation-aware reboot phrasing check. Mirrors
     * {@code WingetRunner.containsAffirmativeReboot}; duplicated to avoid a
     * WingetRunner <-> SoftwareUpdateService class cycle.
     */
    static boolean containsAffirmativeRebootPhrasing(String output) {
        if (output == null || output.isBlank()) return false;
        for (String rawLine : output.toLowerCase().split("\\r?\\n")) {
            String l = rawLine.trim();
            if (l.isEmpty()) continue;
            boolean hasCompact = l.contains("rebootrequired") || l.contains("restartrequired")
                    || l.contains("error_success_reboot_required");
            boolean hasPhrase = l.contains("reboot required") || l.contains("restart required")
                    || l.contains("restart is required") || l.contains("a reboot is required")
                    || l.contains("please reboot") || l.contains("please restart");
            boolean hasBareRestart = !hasCompact && !hasPhrase
                    && l.contains("a restart")
                    && (l.contains("requir") || l.contains("needed") || l.contains("necessary"));
            if (!hasCompact && !hasPhrase && !hasBareRestart) continue;
            if (isNegatedRebootLine(l)) continue;
            return true;
        }
        return false;
    }

    private static boolean isNegatedRebootLine(String lowerLine) {
        String l = lowerLine;
        if (l.matches(".*rebootrequired\"?\\s*[:=]\\s*false.*")) return true;
        if (l.matches(".*restartrequired\"?\\s*[:=]\\s*false.*")) return true;
        if (l.matches(".*rebootrequired\"?\\s*[:=]\\s*0\\b.*")) return true;
        if (l.matches(".*restartrequired\"?\\s*[:=]\\s*0\\b.*")) return true;
        if (l.contains("rebootrequired") || l.contains("restartrequired")) {
            if (l.contains("no ") || l.contains("not ") || l.contains("n't")
                    || l.contains("without") || l.contains("never") || l.contains("false")
                    || l.contains("none")) return true;
            return false;
        }
        if (l.matches(".*\\bno\\s+(reboot|restart)\\b.*")) return true;
        if (l.matches(".*\\b(reboot|restart)\\b[^\\n]*?\\bnot\\s+(required|needed|necessary)\\b.*")) return true;
        if (l.matches(".*\\bnot\\s+requir[^\\n]*?\\b(reboot|restart)\\b.*")) return true;
        if (l.matches(".*n['’]t\\s+requir[^\\n]*?\\b(reboot|restart)\\b.*")) return true;
        if (l.matches(".*without\\s+[^\\n]*?\\b(reboot|restart)\\b.*")) return true;
        return false;
    }

    /**
     * MSI success-with-reboot exit codes. 3010 = ERROR_SUCCESS_REBOOT_REQUIRED,
     * 1641 = ERROR_SUCCESS_REBOOT_INITIATED. winget surfaces installer codes, so a
     * non-zero exit alone must not be treated as a plain failure when it is one of these.
     */
    public static boolean isRebootExitCode(int exitCode) {
        return exitCode == ProcessResult.MSI_SUCCESS_REBOOT_REQUIRED
                || exitCode == ProcessResult.MSI_SUCCESS_REBOOT_INITIATED;
    }

    /**
     * True when the result should be treated as installed (exit 0 or reboot-required exit).
     * Callers must still consult {@link #isRebootRequired(ProcessResult)} to decide whether
     * to abort the remaining batch and prompt for restart.
     */
    public static boolean isSuccessOrRebootRequired(ProcessResult result) {
        if (result == null) return false;
        return result.success() || isRebootRequired(result);
    }

    private static JsonNode tryParseRebootJson(String output) {
        if (output == null) return null;
        // Fast path: whole output is JSON.
        try {
            JsonNode root = JsonMapper.parseTree(output);
            if (root != null && root.isObject()) return root;
        } catch (Exception ignored) {
        }
        // Slow path: find the last {...} fragment mentioning rebootRequired (progress-safe).
        try {
            String lower = output.toLowerCase();
            int keyIdx = lower.lastIndexOf("rebootrequired");
            if (keyIdx < 0) return null;
            int openIdx = output.lastIndexOf('{', keyIdx);
            int closeIdx = output.indexOf('}', keyIdx);
            if (openIdx < 0 || closeIdx < 0 || closeIdx <= openIdx) return null;
            // Balance braces forward from openIdx to capture the full object.
            int depth = 0;
            int end = -1;
            for (int i = openIdx; i < output.length(); i++) {
                char c = output.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) { end = i; break; }
                }
            }
            if (end < 0) return null;
            return JsonMapper.parseTree(output.substring(openIdx, end + 1));
        } catch (Exception ignored) {
            return null;
        }
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
        // Bridge supplier -> AtomicBoolean (shared helper; identical 100ms daemon-monitor semantics)
        try (CancelBridge bridge = CancelBridge.bridge(cancelled, "scanAll-cancel-monitor")) {
            return scanAllConcurrent(bridge.flag(), onWingetDone, onWuDone, overallTimeoutSeconds);
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
        // Fresh errors per scan: sub-scans reset these on entry, but early-return paths
        // (winget missing, non-Windows, pre-cancelled) skip the reset and would otherwise
        // leak the PREVIOUS scan's warning into this scan's status line.
        lastWingetError = null;
        lastWindowsUpdateError = null;
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
                // Surface to the UI so a failed source is reported as a warning instead of
                // silently collapsing into a false "Everything is up to date".
                lastWingetError = "winget scan failed: " + ex.getMessage();
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
                // Surface to the UI so a failed source warns instead of false "up to date".
                if (lastWindowsUpdateError == null || lastWindowsUpdateError.isBlank()) {
                    lastWindowsUpdateError = "Windows Update scan failed: " + ex.getMessage();
                }
                if (onWuDone != null) onWuDone.accept(0);
                return List.<SoftwareUpdateEntry>of();
            }
        }, scanExecutor);

        CompletableFuture<Void> all = CompletableFuture.allOf(wingetFuture, wuFuture);
        try {
            long timeout = Math.max(30, overallTimeoutSeconds);
            long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeout);
            while (!all.isDone()) {
                if (Thread.currentThread().isInterrupted()) {
                    AppLogger.info("Parallel scan interrupted; cancelling winget/WU workers");
                    internalCancelled.set(true);
                    wingetFuture.cancel(true);
                    wuFuture.cancel(true);
                    Thread.currentThread().interrupt();
                    break;
                }
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
                    // Mark incomplete sources as timed out so the UI warns instead of reporting
                    // a false "Everything is up to date" when partial results are empty.
                    if (!wingetFuture.isDone() && (lastWingetError == null || lastWingetError.isBlank())) {
                        lastWingetError = "winget scan timed out after " + timeout + "s";
                    }
                    if (!wuFuture.isDone() && (lastWindowsUpdateError == null || lastWindowsUpdateError.isBlank())) {
                        lastWindowsUpdateError = "Windows Update scan timed out after " + timeout + "s";
                    }
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
            internalCancelled.set(true);
            try { wingetFuture.cancel(true); } catch (Exception ignored) {}
            try { wuFuture.cancel(true); } catch (Exception ignored) {}
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            // An interrupt (e.g. Dashboard per-task timeout via Future.cancel(true))
            // must also release the 2 scanExecutor workers; otherwise orphans hold
            // the pool and the next scan/retry queues behind them.
            internalCancelled.set(true);
            try { wingetFuture.cancel(true); } catch (Exception ignored) {}
            try { wuFuture.cancel(true); } catch (Exception ignored) {}
            AppLogger.warning("Parallel scan failed: " + ex.getMessage());
        } finally {
            if (cancelMonitor != null) cancelMonitor.interrupt();
        }

        // Warm the shared in-memory cache only on clean, complete, non-cancelled scans.
        // Partial (cancelled/timed-out/error) results must never poison it — the ViewModel
        // stale-fallback explicitly labels cached data, so only full successes qualify here.
        try {
            boolean userCancelled = (cancelled != null && cancelled.get()) || internalCancelled.get();
            boolean wingetOk = wingetFuture.isDone() && !wingetFuture.isCancelled() && !wingetFuture.isCompletedExceptionally();
            boolean wuOk = wuFuture.isDone() && !wuFuture.isCancelled() && !wuFuture.isCompletedExceptionally();
            boolean hasErrors = (lastWingetError != null && !lastWingetError.isBlank())
                    || (lastWindowsUpdateError != null && !lastWindowsUpdateError.isBlank());
            if (!userCancelled && wingetOk && wuOk && !hasErrors && !allUpdates.isEmpty()) {
                SoftwareUpdateScanCache.put(allUpdates, lastWingetError, lastWindowsUpdateError);
            }
        } catch (Exception ignored) {}

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
        // Never pass --force: it overrides winget hash/applicability safeguards and turns
        // phantom same-version rows into forced reinstalls. --exact keeps --id precise,
        // --disable-interactivity prevents silent hangs on interactive installers.
        List<String> args = new ArrayList<>(List.of(
                "upgrade", "--id", packageId, "--exact", "--source", "winget",
                "--accept-source-agreements", "--accept-package-agreements"));
        if (silent) {
            args.add("--silent");
            args.add("--disable-interactivity");
        }

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
            // Reboot-required (exit 3010/1641 or reboot phrasing) counts as installed; the caller
            // aborts the batch and prompts for restart. Check before mismatch/failure handling so a
            // 3010 is not misreported as Failed and does not trigger another launcher retry.
            if (isRebootRequired(r)) return r;
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
        if (result == null) return false;
        String combined = "";
        if (result.stdout() != null) combined += result.stdout();
        if (result.stderr() != null) combined += result.stderr();
        String lower = combined.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("install technology is different")
                || combined.contains("0x8A150011");
    }
}
