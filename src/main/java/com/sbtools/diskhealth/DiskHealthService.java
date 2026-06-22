package com.sbtools.diskhealth;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DiskHealthService {

    private static final long TIMEOUT_SECONDS = 120;
    private final ProcessRunner processRunner = new ProcessRunner(TIMEOUT_SECONDS);

    public record HealthResult(List<DiskHealthInfo> drives, boolean smartctlAvailable) {}

    public HealthResult getDiskHealth() throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("Disk health is only available on Windows.");
        }
        Path script = PowerShellScripts.resolve("disk-health.ps1");
        ProcessResult result = processRunner.run(ProcessRunner.powershellScript(script.toString()));
        if (!result.success()) {
            throw new IOException("Failed to get disk health: " + result.combinedOutput());
        }
        String json = result.stdout().trim();
        if (json.isBlank()) return new HealthResult(List.of(), false);
        try {
            JsonNode root = JsonMapper.mapper().readTree(json);
            boolean smartctlAvailable = root.has("smartctlAvailable") && root.get("smartctlAvailable").asBoolean(false);

            List<DiskHealthInfo> drives = new ArrayList<>();
            JsonNode drivesNode = root.has("drives") ? root.get("drives") : root;
            if (drivesNode.isArray()) {
                for (JsonNode node : drivesNode) {
                    drives.add(JsonMapper.mapper().treeToValue(node, DiskHealthInfo.class));
                }
            }
            return new HealthResult(drives, smartctlAvailable);
        } catch (Exception e) {
            AppLogger.error("Failed to parse disk health JSON", e);
            throw new IOException("Failed to parse disk health: " + e.getMessage(), e);
        }
    }
}
