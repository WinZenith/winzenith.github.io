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

    private final ProcessRunner runner = new ProcessRunner(300);

    public boolean createRestorePoint(String description) {
        try {
            Path script = PowerShellScripts.resolve("checkpoint-restore.ps1");
            ProcessResult r = runner.run(ProcessRunner.powershellScript(script.toString(), description));
            return r.success();
        } catch (Exception e) {
            AppLogger.warning("Failed to create restore point: " + e.getMessage());
            return false;
        }
    }

    public List<SystemRestoreRow> listRestorePoints() {
        List<SystemRestoreRow> result = new ArrayList<>();
        try {
            Path script = PowerShellScripts.resolve("list-restore-points.ps1");
            ProcessResult r = runner.run(ProcessRunner.powershellScript(script.toString()));
            if (!r.success()) {
                AppLogger.warning("list-restore-points failed: " + r.stderr());
                return result;
            }
            String[] lines = r.stdout().split("\\r?\\n");
            boolean header = true;
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (header) {
                    header = false;
                    continue;
                }
                SystemRestoreRow row = parseCsvLine(line);
                if (row != null) {
                    result.add(row);
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to list restore points: " + e.getMessage());
        }
        return result;
    }

    public boolean deleteRestorePoint(int sequenceNumber) {
        try {
            Path script = PowerShellScripts.resolve("delete-restore-point.ps1");
            ProcessResult r = runner.run(ProcessRunner.powershellScript(script.toString(), String.valueOf(sequenceNumber)));
            return r.success() && r.stdout().contains("\"deleted\": true");
        } catch (Exception e) {
            AppLogger.warning("Failed to delete restore point " + sequenceNumber + ": " + e.getMessage());
            return false;
        }
    }

    public void launchSystemRestore() throws IOException {
        new ProcessBuilder("rstrui.exe").start();
    }

    private static SystemRestoreRow parseCsvLine(String line) {
        try {
            String[] parts = splitCsv(line);
            if (parts.length < 4) return null;
            String description = unquote(parts[0]);
            String creationTime = unquote(parts[1]);
            int eventType = Integer.parseInt(unquote(parts[2]));
            int sequenceNumber = Integer.parseInt(unquote(parts[3]));
            return new SystemRestoreRow(sequenceNumber, description, creationTime, eventType);
        } catch (Exception e) {
            AppLogger.warning("Failed to parse restore point CSV: " + line);
            return null;
        }
    }

    private static String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
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

    private static String unquote(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s;
    }
}
