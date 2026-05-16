package com.basicsdriverupdate.windowsupdate;

import com.basicsdriverupdate.util.JsonMapper;
import com.basicsdriverupdate.util.PowerShellScripts;
import com.basicsdriverupdate.util.ProcessResult;
import com.basicsdriverupdate.util.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class WindowsUpdateService {

    private final ProcessRunner processRunner = new ProcessRunner(900);

    public List<OsUpdateEntry> scanOsUpdates() throws IOException, InterruptedException {
        Path script = PowerShellScripts.resolve("wu-search-os.ps1");
        ProcessResult result = processRunner.run(ProcessRunner.powershellScript(script.toString()));
        if (!result.success()) {
            throw new IOException("Windows Update scan failed: " + result.combinedOutput());
        }
        return parseOsUpdates(result.stdout());
    }

    static List<OsUpdateEntry> parseOsUpdates(String json) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = JsonMapper.parseTree(json);
        List<OsUpdateEntry> list = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode n : root) {
                list.add(nodeToEntry(n));
            }
        } else if (root.isObject() && root.has("updateId")) {
            list.add(nodeToEntry(root));
        }
        return list;
    }

    public InstallOsResult install(List<String> updateIds) throws IOException, InterruptedException {
        if (updateIds.isEmpty()) {
            throw new IOException("No updates selected");
        }
        Path script = PowerShellScripts.resolve("wu-install.ps1");
        List<String> cmd = ProcessRunner.powershellScript(script.toString());
        cmd.addAll(updateIds);
        ProcessResult result = processRunner.run(cmd);
        if (!result.success()) {
            throw new IOException("Install failed: " + result.combinedOutput());
        }
        boolean reboot = result.stdout() != null && result.stdout().contains("\"rebootRequired\":true");
        return new InstallOsResult(result.exitCode() == 0, reboot, result.stdout());
    }

    private static OsUpdateEntry nodeToEntry(JsonNode n) {
        return new OsUpdateEntry(
                text(n, "updateId"),
                text(n, "title"),
                text(n, "kbArticle"),
                n.has("sizeBytes") ? n.get("sizeBytes").asLong(0) : 0,
                text(n, "importance"),
                n.has("rebootRequired") && n.get("rebootRequired").asBoolean(false),
                true
        );
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v != null && !v.isNull() ? v.asText("") : "";
    }

    public record InstallOsResult(boolean success, boolean rebootRequired, String detail) {
    }
}
