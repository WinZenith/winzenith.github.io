package com.sbtools.backup;

import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.PowerShellScripts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class SystemRestoreService {

    private final ProcessRunner runner = new ProcessRunner(60);

    public record RestorePointResult(boolean success, int sequenceNumber, String error) {
        public RestorePointResult(boolean success, int sequenceNumber) { this(success, sequenceNumber, ""); }
    }

    public RestorePointResult createRestorePoint(String description) {
        try {
            Path script = PowerShellScripts.resolve("checkpoint-restore.ps1");
            ProcessResult r = runner.run(ProcessRunner.powershellScript(script.toString(), description));
            int seq = -1;
            boolean scriptSuccess = r.success();
            String err = "";
            if (r.stdout() != null && !r.stdout().isBlank()) {
                try {
                    var tree = com.sbtools.util.JsonMapper.parseTree(r.stdout());
                    seq = tree.path("sequenceNumber").asInt(-1);
                    if (tree.has("success")) {
                        scriptSuccess = tree.path("success").asBoolean(r.success());
                    }
                    if (tree.has("error")) err = tree.path("error").asText("");
                } catch (Exception ignored) {}
            }
            if (err.isBlank() && !r.stderr().isBlank()) err = r.stderr().trim();
            if (err.isBlank() && !r.stdout().isBlank() && !scriptSuccess) err = r.combinedOutput();
            if (scriptSuccess) {
                AppLogger.info("System restore point created: " + description);
            } else {
                AppLogger.warning("System restore point creation failed: " + (err.isBlank() ? r.combinedOutput() : err));
            }
            return new RestorePointResult(scriptSuccess, seq, err);
        } catch (Exception e) {
            AppLogger.warning("Failed to create restore point: " + e.getMessage());
            return new RestorePointResult(false, -1, e.getMessage());
        }
    }

    public List<SystemRestoreRow> listRestorePoints() throws IOException {
        List<SystemRestoreRow> result = new ArrayList<>();
        try {
            Path script = PowerShellScripts.resolve("list-restore-points.ps1");
            ProcessResult r = runner.run(ProcessRunner.powershellScript(script.toString()));
            if (!r.success()) {
                String out = r.combinedOutput();
                AppLogger.warning("list-restore-points failed: " + out);
                // Surface access-denied / protection-disabled distinctly for UI
                if (out.toLowerCase().contains("access") || out.toLowerCase().contains("denied")
                        || out.contains("0x80070005")) {
                    throw new IOException("Access denied - run as Administrator to list restore points. " + out);
                }
                if (out.toLowerCase().contains("0x80070422") || out.toLowerCase().contains("protection")) {
                    throw new IOException("System Protection is disabled. Enable it in System Properties. " + out);
                }
                throw new IOException("Failed to list restore points: " + out);
            }
            String out = r.stdout() == null ? "" : r.stdout().trim();
            if (out.isEmpty()) return result;

            // Try JSON first (new script)
            boolean parsedAsJson = false;
            try {
                // Handle "null" output from empty array in older scripts
                if ("null".equalsIgnoreCase(out)) {
                    return result;
                }
                var tree = com.sbtools.util.JsonMapper.parseTree(out);
                if (tree.isArray()) {
                    for (var node : tree) {
                        String desc = node.path("Description").asText(node.path("description").asText(""));
                        String ctRaw = node.path("CreationTime").asText(node.path("creationTime").asText(""));
                        String ct = normalizeCreationTime(ctRaw);
                        int et = node.path("EventType").asInt(node.path("eventType").asInt(0));
                        int seq = node.path("SequenceNumber").asInt(node.path("sequenceNumber").asInt(-1));
                        if (seq >= 0) {
                            result.add(new SystemRestoreRow(seq, desc, ct, et));
                        }
                    }
                    parsedAsJson = true;
                } else if (tree.isObject()) {
                    // single object case - check for Description or SequenceNumber
                    if (tree.has("Description") || tree.has("description") || tree.has("SequenceNumber") || tree.has("sequenceNumber")) {
                        String desc = tree.path("Description").asText(tree.path("description").asText(""));
                        String ctRaw = tree.path("CreationTime").asText(tree.path("creationTime").asText(""));
                        String ct = normalizeCreationTime(ctRaw);
                        int et = tree.path("EventType").asInt(tree.path("eventType").asInt(0));
                        int seq = tree.path("SequenceNumber").asInt(tree.path("sequenceNumber").asInt(-1));
                        if (seq >= 0) result.add(new SystemRestoreRow(seq, desc, ct, et));
                        parsedAsJson = true;
                    } else if (tree.isNull()) {
                        parsedAsJson = true;
                        return result;
                    }
                }
            } catch (Exception jsonEx) {
                // fall through to CSV
            }
            if (parsedAsJson) return result;

            // Fallback CSV parsing
            String[] lines = out.split("\\r?\\n");
            boolean header = true;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // JSON lines already handled; skip if line starts with [ or {
                if (line.startsWith("[") || line.startsWith("{")) continue;
                if (header) {
                    header = false;
                    continue;
                }
                SystemRestoreRow row = parseCsvLine(line);
                if (row != null) {
                    result.add(row);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            AppLogger.warning("Failed to list restore points: " + e.getMessage());
            throw new IOException("Failed to list restore points: " + e.getMessage(), e);
        }
        return result;
    }

    public void launchSystemRestore() throws IOException {
        AppLogger.info("Launching Windows System Restore (rstrui.exe)");
        new ProcessBuilder("rstrui.exe").start();
    }

    static SystemRestoreRow parseCsvLine(String line) {
        try {
            String[] parts = splitCsv(line);
            if (parts.length < 4) return null;
            String description = unquote(parts[0]);
            String creationTimeRaw = unquote(parts[1]);
            String creationTime = normalizeCreationTime(creationTimeRaw);
            int eventType = Integer.parseInt(unquote(parts[2]));
            int sequenceNumber = Integer.parseInt(unquote(parts[3]));
            return new SystemRestoreRow(sequenceNumber, description, creationTime, eventType);
        } catch (Exception e) {
            AppLogger.warning("Failed to parse restore point CSV: " + line);
            return null;
        }
    }

    static String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    // Escaped quote "" -> single "
                    current.append('"');
                    i++; // skip next
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    static String unquote(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1);
            // Unescape doubled quotes
            s = s.replace("\"\"", "\"");
        } else {
            // Also unescape if not outer quotes but contains doubled
            s = s.replace("\"\"", "\"");
        }
        return s;
    }

    static String normalizeCreationTime(String ct) {
        if (ct == null || ct.isBlank()) {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
        }
        String trimmed = ct.trim();
        // Handle WMI DMTF datetime format: yyyymmddHHMMSS.mmmmmmsUUU e.g., 20260825120000.000000+120
        if (trimmed.matches("\\d{14}\\.\\d+.*")) {
            try {
                String core = trimmed.substring(0, 14);
                java.text.SimpleDateFormat wmi = new java.text.SimpleDateFormat("yyyyMMddHHmmss");
                java.util.Date d = wmi.parse(core);
                return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
            } catch (Exception ignored) {
            }
        }
        // Handle ISO or already formatted, just return
        if (trimmed.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
            return trimmed;
        }
        // Try generic Date parsing
        try {
            java.util.Date d = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(trimmed);
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
        } catch (Exception ignored) {}
        return trimmed;
    }
}
