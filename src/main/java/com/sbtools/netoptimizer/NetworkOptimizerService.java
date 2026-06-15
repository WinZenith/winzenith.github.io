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
import java.util.regex.Pattern;

public class NetworkOptimizerService {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern SAFE_NAME = Pattern.compile("^[a-zA-Z0-9 _\\-().]+$");

    private static List<String> powershellCommand(String command) {
        return List.of("powershell", "-NoProfile", "-Command", command);
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Adapter name is required");
        }
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid adapter name: " + name);
        }
        return name;
    }

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

    public OperationResult applyOptimization(OptimizationPreset preset) {
        try {
            Path script = PowerShellScripts.resolve("net-optimize.ps1");
            String presetArg = preset.name();
            ProcessResult pr = new ProcessRunner(60).run(
                    ProcessRunner.powershellScript(script.toString(), "-Preset", presetArg));
            if (pr.exitCode() != 0) {
                return OperationResult.fail("Optimization failed with exit code " + pr.exitCode(),
                        pr.combinedOutput());
            }
            return OperationResult.ok(preset.getDisplayName() + " applied successfully.",
                    pr.stdout().trim());
        } catch (Exception e) {
            AppLogger.warning("Failed to apply optimization: " + e.getMessage());
            return OperationResult.fail("Failed to apply optimization: " + e.getMessage());
        }
    }

    public OperationResult flushDnsCache() {
        try {
            Path script = PowerShellScripts.resolve("net-dns-flush.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> result = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object success = result.get("success");
                boolean ok = success instanceof Boolean && (Boolean) success;
                String msg = str(result, "message");
                return ok ? OperationResult.ok(msg) : OperationResult.fail(msg);
            }
            return pr.exitCode() == 0
                    ? OperationResult.ok("DNS cache flushed.")
                    : OperationResult.fail("Flush failed with exit code " + pr.exitCode());
        } catch (Exception e) {
            AppLogger.warning("Failed to flush DNS: " + e.getMessage());
            return OperationResult.fail("Failed to flush DNS: " + e.getMessage());
        }
    }

    public OperationResult resetNetworkStack() {
        try {
            Path script = PowerShellScripts.resolve("net-reset.ps1");
            ProcessResult pr = new ProcessRunner(60).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> result = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object success = result.get("success");
                boolean ok = success instanceof Boolean && (Boolean) success;
                return ok
                        ? OperationResult.ok("Network stack reset. Reboot required.", stdout)
                        : OperationResult.fail("Network stack reset failed.", stdout);
            }
            return pr.exitCode() == 0
                    ? OperationResult.ok("Network stack reset. Reboot required.")
                    : OperationResult.fail("Reset failed with exit code " + pr.exitCode());
        } catch (Exception e) {
            AppLogger.warning("Failed to reset network stack: " + e.getMessage());
            return OperationResult.fail("Failed to reset network stack: " + e.getMessage());
        }
    }

    public OperationResult resetWinsock() {
        try {
            ProcessResult pr = new ProcessRunner(60).run(powershellCommand("netsh winsock reset"));
            return pr.exitCode() == 0
                    ? OperationResult.ok("Winsock reset. Reboot recommended.")
                    : OperationResult.fail("Winsock reset failed.", pr.combinedOutput());
        } catch (Exception e) {
            AppLogger.warning("Failed to reset winsock: " + e.getMessage());
            return OperationResult.fail("Failed to reset winsock: " + e.getMessage());
        }
    }

    public OperationResult renewIp(String adapterName) {
        sanitizeName(adapterName);
        try {
            ProcessResult release = new ProcessRunner(30).run(
                    powershellCommand("ipconfig /release \"" + adapterName + "\""));
            if (release.exitCode() != 0) {
                AppLogger.warning("ipconfig /release failed: " + release.combinedOutput());
            }
            ProcessResult renew = new ProcessRunner(30).run(
                    powershellCommand("ipconfig /renew \"" + adapterName + "\""));
            if (renew.exitCode() != 0) {
                return OperationResult.fail("IP renewal failed.",
                        "release: " + release.combinedOutput() + "\nrenew: " + renew.combinedOutput());
            }
            return OperationResult.ok("IP address renewed for " + adapterName + ".");
        } catch (Exception e) {
            AppLogger.warning("Failed to renew IP: " + e.getMessage());
            return OperationResult.fail("Failed to renew IP: " + e.getMessage());
        }
    }

    public OperationResult setAdapterState(String adapterName, boolean enable) {
        sanitizeName(adapterName);
        try {
            String cmd = enable ? "Enable-NetAdapter" : "Disable-NetAdapter";
            ProcessResult pr = new ProcessRunner(30).run(
                    powershellCommand(cmd + " -Name '" + adapterName + "' -Confirm:$false"));
            if (pr.exitCode() != 0) {
                return OperationResult.fail("Failed to " + (enable ? "enable" : "disable") + " adapter.",
                        pr.combinedOutput());
            }
            return OperationResult.ok((enable ? "Enabled" : "Disabled") + " " + adapterName + ".");
        } catch (Exception e) {
            AppLogger.warning("Failed to set adapter state: " + e.getMessage());
            return OperationResult.fail("Failed to set adapter state: " + e.getMessage());
        }
    }

    public String getIpConfigAll() {
        try {
            ProcessResult pr = new ProcessRunner(30).run(
                    powershellCommand("ipconfig /all"));
            return pr.exitCode() == 0 ? pr.stdout() : "Failed to retrieve network information.";
        } catch (Exception e) {
            AppLogger.warning("Failed to get ipconfig: " + e.getMessage());
            return "Failed to retrieve network information.";
        }
    }

    public TcpSettings getCurrentTcpSettings() {
        try {
            ProcessResult pr = new ProcessRunner(30).run(
                    powershellCommand("netsh int tcp show global"));
            return TcpSettings.parse(pr.stdout());
        } catch (Exception e) {
            AppLogger.warning("Failed to get TCP settings: " + e.getMessage());
            return new TcpSettings(Map.of());
        }
    }

    public List<String> getCurrentDnsServers(String adapterName) {
        sanitizeName(adapterName);
        try {
            Path script = PowerShellScripts.resolve("net-dns-get.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString(), "-AdapterName", adapterName));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object dnsObj = data.get("dnsServers");
                if (dnsObj instanceof List<?> list) {
                    return list.stream().map(Object::toString).toList();
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to get DNS servers: " + e.getMessage());
        }
        return List.of();
    }

    public OperationResult setDnsServers(String adapterName, String primaryDns, String secondaryDns) {
        sanitizeName(adapterName);
        try {
            Path script = PowerShellScripts.resolve("net-dns-set.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString(),
                            "-AdapterName", adapterName,
                            "-PrimaryDNS", primaryDns != null ? primaryDns : "",
                            "-SecondaryDNS", secondaryDns != null ? secondaryDns : ""));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object success = data.get("success");
                boolean ok = success instanceof Boolean && (Boolean) success;
                String msg = str(data, "message");
                return ok ? OperationResult.ok(msg) : OperationResult.fail(msg);
            }
            return pr.exitCode() == 0
                    ? OperationResult.ok("DNS servers updated.")
                    : OperationResult.fail("DNS update failed with exit code " + pr.exitCode());
        } catch (Exception e) {
            AppLogger.warning("Failed to set DNS servers: " + e.getMessage());
            return OperationResult.fail("Failed to set DNS servers: " + e.getMessage());
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }
}
