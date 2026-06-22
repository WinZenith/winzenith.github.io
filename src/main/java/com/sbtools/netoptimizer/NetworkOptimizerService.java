package com.sbtools.netoptimizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbtools.util.AppLogger;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.ProcessResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class NetworkOptimizerService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NetworkChangeLog changeLog = new NetworkChangeLog();
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
            logChange("Apply Optimization", preset.getDisplayName(), preset.getDescription(), true);
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
                if (ok) logChange("Flush DNS Cache", "All", msg, true);
                return ok ? OperationResult.ok(msg) : OperationResult.fail(msg);
            }
            boolean ok = pr.exitCode() == 0;
            if (ok) logChange("Flush DNS Cache", "All", "DNS cache flushed.", true);
            return ok
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
                if (ok) logChange("Reset Network Stack", "TCP/IP + Winsock", "Reboot required.", true);
                return ok
                        ? OperationResult.ok("Network stack reset. Reboot required.", stdout)
                        : OperationResult.fail("Network stack reset failed.", stdout);
            }
            boolean ok = pr.exitCode() == 0;
            if (ok) logChange("Reset Network Stack", "TCP/IP + Winsock", "Reboot required.", true);
            return ok
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
            boolean ok = pr.exitCode() == 0;
            if (ok) logChange("Reset Winsock", "Winsock catalog", "Reboot recommended.", true);
            return ok
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
            logChange(enable ? "Enable Adapter" : "Disable Adapter", adapterName, "", true);
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
                if (ok) {
                    String target = (primaryDns != null && !primaryDns.isEmpty())
                            ? primaryDns + (secondaryDns != null && !secondaryDns.isEmpty() ? ", " + secondaryDns : "")
                            : "DHCP";
                    logChange("Set DNS", adapterName, target, true);
                }
                return ok ? OperationResult.ok(msg) : OperationResult.fail(msg);
            }
            boolean ok = pr.exitCode() == 0;
            if (ok) logChange("Set DNS", adapterName, primaryDns != null ? primaryDns : "DHCP", true);
            return ok
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

    private void logChange(String operation, String target, String details, boolean success) {
        changeLog.append(new NetworkChangeEntry(
                Instant.now().toString(), operation, target, details, success));
    }

    public List<NetworkChangeEntry> getChangeLog() {
        return changeLog.load();
    }

    public void clearChangeLog() {
        changeLog.clear();
    }

    public List<ConnectionInfo> getActiveConnections(String stateFilter) {
        List<ConnectionInfo> connections = new ArrayList<>();
        try {
            Path script = PowerShellScripts.resolve("net-connections.ps1");
            String arg = (stateFilter != null && !stateFilter.isEmpty()) ? stateFilter : "ALL";
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString(), "-State", arg));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty() && !"[]".equals(stdout)) {
                List<Map<String, Object>> raw = mapper.readValue(stdout,
                        new TypeReference<List<Map<String, Object>>>() {});
                for (Map<String, Object> entry : raw) {
                    try {
                        connections.add(new ConnectionInfo(
                                str(entry, "Protocol"),
                                str(entry, "LocalAddress"),
                                str(entry, "RemoteAddress"),
                                str(entry, "State"),
                                entry.get("PID") instanceof Number n ? n.intValue() : 0,
                                str(entry, "ProcessName")));
                    } catch (Exception e) {
                        AppLogger.warning("Failed to parse connection entry: " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to get active connections: " + e.getMessage());
        }
        return connections;
    }

    public AdapterProperties getAdapterProperties(String adapterName) {
        sanitizeName(adapterName);
        try {
            Path script = PowerShellScripts.resolve("net-adapter-properties.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString(), "-AdapterName", adapterName));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                if (data.containsKey("error")) {
                    AppLogger.warning("Adapter properties error: " + data.get("error"));
                    return new AdapterProperties(adapterName, Map.of());
                }
                Object propsObj = data.get("properties");
                if (propsObj instanceof List<?> list) {
                    Map<String, String> props = new LinkedHashMap<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            String name = m.get("Name") != null ? m.get("Name").toString() : "";
                            String value = m.get("Value") != null ? m.get("Value").toString() : "";
                            props.put(name, value);
                        }
                    }
                    return new AdapterProperties(adapterName, props);
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to get adapter properties: " + e.getMessage());
        }
        return new AdapterProperties(adapterName, Map.of());
    }

    public WiFiInfo getCurrentWifiInfo() {
        try {
            Path script = PowerShellScripts.resolve("net-wifi-info.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                if (data.containsKey("error")) {
                    return null;
                }
                return new WiFiInfo(
                        str(data, "ssid"),
                        str(data, "state"),
                        data.get("signalPercent") instanceof Number n ? n.intValue() : 0,
                        str(data, "radioType"),
                        str(data, "channel"),
                        str(data, "receiveRate"),
                        str(data, "transmitRate"));
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to get Wi-Fi info: " + e.getMessage());
        }
        return null;
    }

    public List<String> getWifiProfiles() {
        try {
            Path script = PowerShellScripts.resolve("net-wifi-profiles.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty() && !"[]".equals(stdout)) {
                return mapper.readValue(stdout, new TypeReference<List<String>>() {});
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to get Wi-Fi profiles: " + e.getMessage());
        }
        return List.of();
    }

    public OperationResult disconnectWifi() {
        try {
            Path script = PowerShellScripts.resolve("net-wifi-disconnect.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object success = data.get("success");
                boolean ok = success instanceof Boolean && (Boolean) success;
                String msg = str(data, "message");
                if (ok) logChange("Disconnect Wi-Fi", "Wi-Fi", "", true);
                return ok ? OperationResult.ok(msg) : OperationResult.fail(msg);
            }
            boolean ok = pr.exitCode() == 0;
            if (ok) logChange("Disconnect Wi-Fi", "Wi-Fi", "", true);
            return ok
                    ? OperationResult.ok("Wi-Fi disconnected.")
                    : OperationResult.fail("Disconnect failed with exit code " + pr.exitCode());
        } catch (Exception e) {
            AppLogger.warning("Failed to disconnect Wi-Fi: " + e.getMessage());
            return OperationResult.fail("Failed to disconnect Wi-Fi: " + e.getMessage());
        }
    }

    public OperationResult forgetWifiProfile(String ssid) {
        if (ssid == null || ssid.isBlank()) {
            return OperationResult.fail("SSID is required.");
        }
        try {
            Path script = PowerShellScripts.resolve("net-wifi-forget.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString(), "-SSID", ssid));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object success = data.get("success");
                boolean ok = success instanceof Boolean && (Boolean) success;
                String msg = str(data, "message");
                if (ok) logChange("Forget Wi-Fi Profile", ssid, "", true);
                return ok ? OperationResult.ok(msg) : OperationResult.fail(msg);
            }
            boolean ok = pr.exitCode() == 0;
            if (ok) logChange("Forget Wi-Fi Profile", ssid, "", true);
            return ok
                    ? OperationResult.ok("Profile '" + ssid + "' forgotten.")
                    : OperationResult.fail("Forget failed with exit code " + pr.exitCode());
        } catch (Exception e) {
            AppLogger.warning("Failed to forget Wi-Fi profile: " + e.getMessage());
            return OperationResult.fail("Failed to forget Wi-Fi profile: " + e.getMessage());
        }
    }
}
