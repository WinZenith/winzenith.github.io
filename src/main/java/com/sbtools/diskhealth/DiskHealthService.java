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
        ProcessResult result = null;
        IOException lastIo = null;
        for (List<String> cmd : List.of(ProcessRunner.powershellScript(script.toString()), ProcessRunner.pwshScript(script.toString()))) {
            try {
                result = processRunner.run(cmd);
                break;
            } catch (IOException e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean missing = msg.contains("cannot run program") || msg.contains("no such file") || msg.contains("error=2");
                if (missing && !cmd.get(0).equals("pwsh.exe")) {
                    AppLogger.warning("DiskHealth: powershell.exe not found, trying pwsh.exe");
                    lastIo = e;
                    continue;
                }
                throw e;
            }
        }
        if (result == null) {
            if (lastIo != null) throw lastIo;
            throw new IOException("Failed to get disk health: no PowerShell executable");
        }
        if (!result.success()) {
            AppLogger.error("disk-health.ps1 failed: " + result.combinedOutput());
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
                    DiskHealthInfo info = JsonMapper.mapper().treeToValue(node, DiskHealthInfo.class);
                    if (node.has("rawSmartAttributes") && node.get("rawSmartAttributes").isArray()) {
                        List<SmartAttribute> attrs = new ArrayList<>();
                        for (JsonNode attrNode : node.get("rawSmartAttributes")) {
                            attrs.add(JsonMapper.mapper().treeToValue(attrNode, SmartAttribute.class));
                        }
                        info.setRawSmartAttributes(attrs);
                    }
                    applyWmiThresholdOverride(info);
                    drives.add(info);
                }
            }
            return new HealthResult(drives, smartctlAvailable);
        } catch (Exception e) {
            AppLogger.error("Failed to parse disk health JSON", e);
            throw new IOException("Failed to parse disk health: " + e.getMessage(), e);
        }
    }

    /**
     * Defense-in-depth health override (WMI-only mode): upgrade Healthy/OK/Unknown
     * when sector counters already show damage. Mirrors disk-health.ps1 thresholds
     * so stale scripts still get correct UI coloring.
     */
    private static void applyWmiThresholdOverride(DiskHealthInfo info) {
        if (info == null) return;
        String s = info.getHealthStatus();
        if (s == null) return;
        if (!s.equalsIgnoreCase("Healthy") && !s.equalsIgnoreCase("OK")
                && !s.equalsIgnoreCase("Unknown")) return;
        long worst = 0;
        for (long v : new long[]{info.getReallocatedSectors(),
                info.getCurrentPendingSectorCount(), info.getUncorrectableSectorCount()}) {
            if (v >= 0 && v > worst) worst = v;
        }
        if (worst > 10) info.setHealthStatus("Critical");
        else if (worst > 0) info.setHealthStatus("Caution");
    }
}
