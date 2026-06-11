package com.sbtools.netoptimizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbtools.util.AppLogger;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.ProcessResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class NetworkOptimizerService {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<NetworkAdapterRow> listAdapters() {
        List<NetworkAdapterRow> adapters = new ArrayList<>();
        try {
            Path script = PowerShellScripts.resolve("net-adapter-info.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty() && !"[]".equals(stdout)) {
                List<Map<String, Object>> raw = mapper.readValue(stdout,
                        new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> entry : raw) {
                    try {
                        String name = str(entry, "Name");
                        String desc = str(entry, "InterfaceDescription");
                        String status = str(entry, "Status");
                        String speed = str(entry, "LinkSpeed");
                        String mac = str(entry, "MacAddress");
                        String ip = str(entry, "IPAddress");
                        String adminStatus = str(entry, "AdminStatus");
                        boolean enabled = "Up".equalsIgnoreCase(status) || "Enabled".equalsIgnoreCase(adminStatus);
                        adapters.add(new NetworkAdapterRow(name, desc, status, speed, mac, ip, enabled));
                    } catch (Exception e) {
                        AppLogger.warning("Failed to parse adapter entry: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to list adapters: " + e.getMessage());
        }
        return adapters;
    }

    public boolean applyOptimization(OptimizationPreset preset) {
        try {
            Path script = PowerShellScripts.resolve("net-optimize.ps1");
            String presetArg = preset.name();
            ProcessResult pr = new ProcessRunner(60).run(
                    ProcessRunner.powershellScript(script.toString(), "-Preset", presetArg));
            return pr.exitCode() == 0;
        } catch (Exception e) {
            AppLogger.warning("Failed to apply optimization: " + e.getMessage());
            return false;
        }
    }

    public boolean flushDnsCache() {
        try {
            Path script = PowerShellScripts.resolve("net-dns-flush.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> result = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object success = result.get("success");
                return success instanceof Boolean && (Boolean) success;
            }
            return pr.exitCode() == 0;
        } catch (Exception e) {
            AppLogger.warning("Failed to flush DNS: " + e.getMessage());
            return false;
        }
    }

    public boolean resetNetworkStack() {
        try {
            Path script = PowerShellScripts.resolve("net-reset.ps1");
            ProcessResult pr = new ProcessRunner(60).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> result = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object success = result.get("success");
                return success instanceof Boolean && (Boolean) success;
            }
            return pr.exitCode() == 0;
        } catch (Exception e) {
            AppLogger.warning("Failed to reset network stack: " + e.getMessage());
            return false;
        }
    }

    public boolean resetWinsock() {
        try {
            ProcessBuilder pb = new ProcessBuilder("netsh", "winsock", "reset");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            AppLogger.warning("Failed to reset winsock: " + e.getMessage());
            return false;
        }
    }

    public boolean renewIp(String adapterName) {
        try {
            ProcessBuilder release = new ProcessBuilder("ipconfig", "/release", adapterName);
            release.redirectErrorStream(true);
            Process p1 = release.start();
            p1.waitFor();
            ProcessBuilder renew = new ProcessBuilder("ipconfig", "/renew", adapterName);
            renew.redirectErrorStream(true);
            Process p2 = renew.start();
            p2.waitFor();
            return true;
        } catch (Exception e) {
            AppLogger.warning("Failed to renew IP: " + e.getMessage());
            return false;
        }
    }

    public boolean setAdapterState(String adapterName, boolean enable) {
        try {
            String cmd = enable ? "Enable-NetAdapter" : "Disable-NetAdapter";
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile",
                    "-Command", cmd + " -Name '" + adapterName + "' -Confirm:$false");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            AppLogger.warning("Failed to set adapter state: " + e.getMessage());
            return false;
        }
    }

    public String getIpConfigAll() {
        try {
            ProcessBuilder pb = new ProcessBuilder("ipconfig", "/all");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (Exception e) {
            AppLogger.warning("Failed to get ipconfig: " + e.getMessage());
            return "Failed to retrieve network information.";
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }
}
