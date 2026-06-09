package com.sbtools.software;

import com.sbtools.util.AppLogger;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.File;
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
import java.util.stream.Collectors;

public class SoftwareUpdateService {

    private final ProcessRunner runner = new ProcessRunner(600);
    private volatile String resolvedWingetPath;

    public boolean isWingetAvailable() {
        try {
            String p = resolveWingetPath();
            if (p != null) return true;
            // last resort: try running winget via PowerShell or cmd fallback using wingetCmd
            try {
                ProcessResult r = runner.run(wingetCmd("--version"), 10);
                return r.success();
            } catch (Exception ignored) {
                AppLogger.info("winget not available: direct invocation failed");
                return false;
            }
        } catch (Exception e) {
            AppLogger.info("winget not available: " + e.getMessage());
            return false;
        }
    }

    private String resolveWingetPath() {
        if (resolvedWingetPath != null) return resolvedWingetPath;
        // 1) where.exe
        try {
            ProcessResult where = runner.run(Arrays.asList("where.exe", "winget"), 5);
            if (where.success() && where.stdout() != null && !where.stdout().isBlank()) {
                String first = where.stdout().split("\\r?\\n")[0].trim();
                if (!first.isBlank()) {
                    resolvedWingetPath = first;
                    return resolvedWingetPath;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }

        // 2) search PATH entries for winget.exe
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            String[] parts = pathEnv.split(File.pathSeparator);
            for (String part : parts) {
                try {
                    if (part == null || part.isBlank()) continue;
                    Path p = Paths.get(part, "winget.exe");
                    if (Files.exists(p)) {
                        // some WindowsApps entries may be reparse points/stubs; accept exists()
                        resolvedWingetPath = p.toString();
                        return resolvedWingetPath;
                    }
                } catch (Exception ignored) {
                }
            }
        }

        // 3) check LOCALAPPDATA\Microsoft\WindowsApps\winget.exe
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            try {
                Path p = Paths.get(local, "Microsoft", "WindowsApps", "winget.exe");
                if (Files.exists(p)) {
                    resolvedWingetPath = p.toString();
                    return resolvedWingetPath;
                }
            } catch (Exception ignored) {
            }
        }

        // 4) Try to query Appx package install location for Microsoft.DesktopAppInstaller
        try {
            ProcessResult pkg = runner.run(Arrays.asList("powershell", "-NoProfile", "-Command", "Get-AppxPackage -Name 'Microsoft.DesktopAppInstaller' | Select-Object -ExpandProperty InstallLocation"), 5);
            String out = pkg.stdout();
            if (out != null && !out.isBlank()) {
                String[] lines = out.split("\\r?\\n");
                String locLine = null;
                for (String ln : lines) {
                    if (ln != null && !ln.isBlank()) { locLine = ln.trim(); break; }
                }
                if (locLine != null) {
                    try {
                        Path installPath = Paths.get(locLine);
                        if (Files.isDirectory(installPath)) {
                            try (var stream = Files.walk(installPath)) {
                                var found = stream.filter(pth -> pth.getFileName().toString().equalsIgnoreCase("winget.exe")).findFirst();
                                if (found.isPresent()) {
                                    resolvedWingetPath = found.get().toString();
                                    return resolvedWingetPath;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // not found
        resolvedWingetPath = null;
        return null;
    }

    private List<String> wingetCmd(String... args) {
        String exe = resolveWingetPath();
        List<String> cmd = new ArrayList<>();
        if (exe != null) {
            cmd.add(exe);
            for (String a : args) cmd.add(a);
        } else {
            // fall back to invoking via cmd.exe so App Execution Aliases or shell-resolved apps work
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add("winget");
            for (String a : args) cmd.add(a);
        }
        return cmd;
    }

    public String getWingetDiagnostics() {
        StringBuilder sb = new StringBuilder();
        try {
            ProcessResult where = runner.run(Arrays.asList("where.exe", "winget"), 5);
            sb.append("where.exe output:\n").append(where.combinedOutput()).append("\n");
        } catch (Exception e) {
            sb.append("where.exe error: ").append(e.getMessage()).append("\n");
        }
        try {
            // try via resolved path or direct invocation
            if (resolveWingetPath() != null) {
                ProcessResult v = runner.run(Arrays.asList(resolveWingetPath(), "--version"), 5);
                sb.append("winget --version:\n").append(v.combinedOutput()).append("\n");
            } else {
                // attempt via cmd.exe
                try {
                    ProcessResult v = runner.run(Arrays.asList("cmd.exe", "/c", "winget --version"), 5);
                    sb.append("winget --version (via cmd.exe):\n").append(v.combinedOutput()).append("\n");
                } catch (Exception ex) {
                    sb.append("winget --version (via cmd.exe) error: ").append(ex.getMessage()).append("\n");
                }
                // attempt via PowerShell as a second fallback
                try {
                    String cmd = buildPowerShellCommandString("winget", "--version");
                    ProcessResult v2 = runner.run(Arrays.asList("powershell", "-NoProfile", "-Command", "& { " + cmd + " }"), 5);
                    sb.append("winget --version (via PowerShell):\n").append(v2.combinedOutput()).append("\n");
                } catch (Exception ex2) {
                    sb.append("winget --version (via PowerShell) error: ").append(ex2.getMessage()).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("winget --version error: ").append(e.getMessage()).append("\n");
        }
        sb.append("resolvedWingetPath=\n").append(resolveWingetPath()).append("\n");
        sb.append("PATH=\n").append(System.getenv("PATH")).append("\n");
        sb.append("UserLocalAppData=\n").append(System.getenv("LOCALAPPDATA")).append("\\Microsoft\\WindowsApps\n");
        return sb.toString();
    }

    public List<SoftwareUpdateEntry> scanForUpdates() throws IOException, InterruptedException {
        List<SoftwareUpdateEntry> results = new ArrayList<>();
        String[] jsonArgs = new String[]{"upgrade", "--source", "winget", "--accept-source-agreements", "--accept-package-agreements", "--output", "json"};
        ProcessResult r = null;
        List<List<String>> candidates = new ArrayList<>();
        String resolved = resolveWingetPath();
        if (resolved != null) {
            List<String> c = new ArrayList<>();
            c.add(resolved);
            for (String a : jsonArgs) c.add(a);
            candidates.add(c);
        }
        // cmd.exe fallback
        List<String> cmdFallback = new ArrayList<>();
        cmdFallback.add("cmd.exe");
        cmdFallback.add("/c");
        cmdFallback.add("winget");
        for (String a : jsonArgs) cmdFallback.add(a);
        candidates.add(cmdFallback);
        // PowerShell fallback
        // build PowerShell command string from args
        String[] allJsonArgs = new String[jsonArgs.length + 1];
        allJsonArgs[0] = "winget";
        System.arraycopy(jsonArgs, 0, allJsonArgs, 1, jsonArgs.length);
        String psCmd = buildPowerShellCommandString(allJsonArgs);
        List<String> psFallback = Arrays.asList("powershell", "-NoProfile", "-Command", "& { " + psCmd + " }");
        candidates.add(psFallback);

        Exception lastEx = null;
        for (List<String> candidate : candidates) {
            try {
                r = runner.run(candidate, 120);
                String stdout = r.stdout();
                if ((r.success() || (stdout != null && !stdout.isBlank()))) {
                    if (!r.success()) {
                        AppLogger.warning("winget scan returned non-zero: " + r.exitCode() + " output: " + r.combinedOutput());
                    }
                    results.addAll(parseJsonOutput(stdout));
                    break;
                }
            } catch (Exception ex) {
                lastEx = ex;
            }
        }
        if (results.isEmpty() && lastEx != null) {
            AppLogger.warning("Failed to parse winget output as JSON: " + lastEx.getMessage());
        }

        // If JSON produced no results or JSON failed, try text-mode fallback for older winget
        if (results.isEmpty()) {
            String[] textArgs = new String[]{"upgrade", "--source", "winget"};
            List<List<String>> textCandidates = new ArrayList<>();
            String resolved2 = resolveWingetPath();
            if (resolved2 != null) {
                List<String> c = new ArrayList<>();
                c.add(resolved2);
                for (String a : textArgs) c.add(a);
                textCandidates.add(c);
            }
            List<String> cmdFallback2 = new ArrayList<>();
            cmdFallback2.add("cmd.exe");
            cmdFallback2.add("/c");
            cmdFallback2.add("winget");
            for (String a : textArgs) cmdFallback2.add(a);
            textCandidates.add(cmdFallback2);
            String[] allTextArgs = new String[textArgs.length + 1];
            allTextArgs[0] = "winget";
            System.arraycopy(textArgs, 0, allTextArgs, 1, textArgs.length);
            String psTextCmd = buildPowerShellCommandString(allTextArgs);
            textCandidates.add(Arrays.asList("powershell", "-NoProfile", "-Command", "& { " + psTextCmd + " }"));

            Exception lastEx2 = null;
            for (List<String> candidate : textCandidates) {
                try {
                    ProcessResult r2 = runner.run(candidate, 120);
                    String stdout2 = r2.stdout();
                    if (stdout2 != null && !stdout2.isBlank()) {
                        List<SoftwareUpdateEntry> textResults = parseTextOutput(stdout2);
                        if (!textResults.isEmpty()) {
                            results.addAll(textResults);
                        }
                        break;
                    }
                } catch (Exception ex) {
                    lastEx2 = ex;
                }
            }
            if (results.isEmpty() && lastEx2 != null) {
                AppLogger.warning("Fallback winget text scan failed: " + lastEx2.getMessage());
            }
        }

        return results;
    }

    List<SoftwareUpdateEntry> parseTextOutput(String stdout) {
        List<SoftwareUpdateEntry> out = new ArrayList<>();
        String trimmed = stdout == null ? "" : stdout.trim();
        if (trimmed.isEmpty()) return out;
        if (trimmed.toLowerCase().contains("no applicable upgrades") || trimmed.toLowerCase().contains("no installed package")) {
            return out;
        }
        String[] lines = stdout.split("\\r?\\n");
        int headerIdx = -1;
        String headerLine = null;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.toLowerCase().contains("name") && (l.toLowerCase().contains("version") || l.toLowerCase().contains("installed"))) {
                headerIdx = i;
                headerLine = l;
                break;
            }
        }
        int start = headerIdx >= 0 ? headerIdx + 1 : 0;
        // if header exists, try to compute column indices
        String[] headerTokens = headerLine == null ? new String[0] : headerLine.trim().split("\\s{2,}");
        int idxName = findHeaderIndex(headerTokens, "name");
        int idxId = findHeaderIndex(headerTokens, "id", "identifier", "packageidentifier");
        int idxVersion = findHeaderIndex(headerTokens, "version", "installedversion", "installed");
        int idxAvailable = findHeaderIndex(headerTokens, "available", "availableversion", "new");
        int idxSource = findHeaderIndex(headerTokens, "source");

        for (int i = start; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.isBlank()) continue;
            if (l.startsWith("---")) continue;
            String[] tokens = l.split("\\s{2,}");
            if (tokens.length < 3) continue;

            try {
                String name = null, id = null, version = null, available = null, source = null;
                if (headerLine != null && headerTokens.length > 0) {
                    if (idxName >= 0 && idxName < tokens.length) name = tokens[idxName];
                    if (idxId >= 0 && idxId < tokens.length) id = tokens[idxId];
                    if (idxVersion >= 0 && idxVersion < tokens.length) version = tokens[idxVersion];
                    if (idxAvailable >= 0 && idxAvailable < tokens.length) available = tokens[idxAvailable];
                    if (idxSource >= 0 && idxSource < tokens.length) source = tokens[idxSource];
                }
                // heuristic fallback mapping when header not useful
                if (name == null) name = tokens[0];
                if (id == null && tokens.length > 1) id = tokens[1];
                if (version == null && tokens.length > 2) version = tokens[2];
                if (available == null && tokens.length > 3) available = tokens[3];
                if (source == null && tokens.length > 4) source = tokens[4];

                if (source != null && !source.isBlank() && !source.equalsIgnoreCase("winget")) continue;
                if (available == null || available.isBlank()) continue;
                if (version == null) version = "";
                if (!available.equals(version)) {
                    out.add(new SoftwareUpdateEntry(id, name, version, available));
                }
            } catch (Exception ignored) {
                // continue parsing other lines
            }
        }
        return out;
    }

    // package-private for tests
    List<SoftwareUpdateEntry> parseJsonOutput(String stdout) {
        List<SoftwareUpdateEntry> results = new ArrayList<>();
        try {
            JsonNode root = JsonMapper.parseTree(stdout);
            // Handle both flat array and object-wrapped array formats
            JsonNode arrayNode = null;
            if (root.isArray()) {
                arrayNode = root;
            } else if (root.isObject()) {
                // Look for the first array field (e.g. "upgrades", "results", "data")
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
                        results.add(new SoftwareUpdateEntry(id, name, version, available));
                    }
                }
            }
        } catch (Exception ex) {
            AppLogger.warning("parseJsonOutput failed: " + ex.getMessage());
        }
        return results;
    }

    private int findHeaderIndex(String[] headers, String... keys) {
        if (headers == null) return -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].toLowerCase();
            for (String k : keys) {
                if (h.contains(k.toLowerCase())) return i;
            }
        }
        return -1;
    }

    private String buildPowerShellCommandString(String... args) {
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (a == null) continue;
            if (sb.length() > 0) sb.append(' ');
            String safe = a.replace("\"", "\\\"");
            if (safe.contains(" ") || safe.contains("\"")) {
                sb.append('"').append(safe).append('"');
            } else {
                sb.append(safe);
            }
        }
        return sb.toString();
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

                    SoftwareUpdateEntry entry = new SoftwareUpdateEntry(
                            updateId, name, "", displayVersion,
                            "WindowsUpdate", updateId, sizeBytes);
                    results.add(entry);
                }
            } else if (root.isObject()) {
                String updateId = findText(root, "updateId");
                String title = findText(root, "title");
                String description = findText(root, "description");
                String version = findText(root, "version");
                long sizeBytes = 0;
                JsonNode sizeNode = root.get("sizeBytes");
                if (sizeNode != null && !sizeNode.isNull()) {
                    sizeBytes = sizeNode.asLong(0);
                }
                String kbArticle = findText(root, "kbArticle");

                String displayVersion = kbArticle != null && !kbArticle.isBlank()
                        ? "KB" + kbArticle
                        : (version != null ? version : "");
                String name = title != null ? title : (description != null ? description : "Windows Update");

                results.add(new SoftwareUpdateEntry(
                        updateId, name, "", displayVersion,
                        "WindowsUpdate", updateId, sizeBytes));
            }
            AppLogger.info("Found " + results.size() + " Windows Update(s)");
        } catch (Exception e) {
            AppLogger.warning("Windows Update scan failed: " + e.getMessage());
        }
        return results;
    }

    public ProcessResult installWindowsUpdate(String updateId, long timeoutSeconds) throws IOException, InterruptedException {
        Path script = PowerShellScripts.resolve("wu-install.ps1");
        return runner.run(ProcessRunner.powershellScript(script.toString(), updateId), timeoutSeconds);
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

    public ProcessResult updatePackage(String packageId, boolean silent, long timeoutSeconds) throws IOException, InterruptedException {
        String[] args = new String[]{"upgrade", "--id", packageId, "--accept-source-agreements", "--accept-package-agreements"};
        List<List<String>> candidates = new ArrayList<>();
        String resolved = resolveWingetPath();
        if (resolved != null) {
            List<String> c = new ArrayList<>();
            c.add(resolved);
            for (String a : args) c.add(a);
            if (silent) c.add("--silent");
            candidates.add(c);
        }
        List<String> cmdFallback = new ArrayList<>();
        cmdFallback.add("cmd.exe");
        cmdFallback.add("/c");
        cmdFallback.add("winget");
        for (String a : args) cmdFallback.add(a);
        if (silent) cmdFallback.add("--silent");
        candidates.add(cmdFallback);

        String[] allArgs = new String[args.length + 1];
        allArgs[0] = "winget";
        System.arraycopy(args, 0, allArgs, 1, args.length);
        String psCmd = buildPowerShellCommandString(allArgs);
        if (silent) psCmd = psCmd + " --silent";
        candidates.add(Arrays.asList("powershell", "-NoProfile", "-Command", "& { " + psCmd + " }"));

        ProcessResult lastResult = null;
        Exception lastEx = null;
        for (List<String> cand : candidates) {
            try {
                lastResult = runner.run(cand, timeoutSeconds);
                if (lastResult.success()) return lastResult;
            } catch (Exception ex) {
                lastEx = ex;
            }
        }
        if (lastResult != null) return lastResult;
        throw new IOException("Failed to run winget upgrade" + (lastEx != null ? ": " + lastEx.getMessage() : ""));
    }
}
