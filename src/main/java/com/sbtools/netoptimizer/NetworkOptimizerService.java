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
    // SAFE_NAME: allow Unicode letters/numbers for localized adapter names (e.g., Réseau, Łącze) plus common symbols; block injection chars "'\"`;|&<>\$%!+=\n
    private static final Pattern SAFE_NAME = Pattern.compile("^[\\p{L}\\p{N} _\\-().*#]+$");
    // SAFE_HOST: hostname, IPv4 or IPv6. Allow alnum, dot, hyphen, underscore, colon for IPv6.
    private static final Pattern SAFE_HOST = Pattern.compile("^[a-zA-Z0-9._\\-:]+$");
    private static final Pattern IPV4_PATTERN = Pattern.compile("^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");

    private static List<String> powershellCommand(String command) {
        return List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command);
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Adapter name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException("Adapter name too long");
        }
        // Block obvious injection characters that could escape psQuote context
        if (trimmed.contains(";") || trimmed.contains("|") || trimmed.contains("&") || trimmed.contains("`") || trimmed.contains("$") || trimmed.contains("\n") || trimmed.contains("\r")) {
            throw new IllegalArgumentException("Invalid adapter name: " + name);
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
            String stderr = pr.stderr() != null ? pr.stderr().trim() : "";
            if (!stdout.isEmpty() && !"[]".equals(stdout)) {
                try {
                    List<Map<String, Object>> raw = mapper.readValue(stdout,
                            new TypeReference<List<Map<String, Object>>>() {});
                    for (Map<String, Object> entry : raw) {
                        try {
                            String name = str(entry, "Name");
                            if (name.isBlank()) continue;
                            String desc = str(entry, "InterfaceDescription");
                            String status = str(entry, "Status");
                            String speed = str(entry, "LinkSpeed");
                            String mac = str(entry, "MacAddress");
                            String ip = str(entry, "IPAddress");
                            String adminStatus = str(entry, "AdminStatus");
                            // enabled = administrative state, not operational Up status
                            boolean adminEnabled = "Up".equalsIgnoreCase(adminStatus) || "Enabled".equalsIgnoreCase(adminStatus);
                            // status reflects operational: Up/Disconnected/Disabled etc.
                            boolean enabled = adminEnabled;
                            adapters.add(new NetworkAdapterRow(name, desc, status, speed, mac, ip, enabled));
                        } catch (Exception e) {
                            AppLogger.warning("Failed to parse adapter entry: " + e.getMessage());
                        }
                    }
                } catch (Exception je) {
                    AppLogger.warning("Failed to parse adapter JSON: " + je.getMessage() + " stdout=" + stdout + " stderr=" + stderr);
                }
            } else if (!stderr.isEmpty()) {
                AppLogger.warning("List adapters stderr: " + stderr);
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to list adapters: " + e.getMessage());
        }
        return adapters;
    }

    public OperationResult applyOptimization(OptimizationPreset preset) {
        try {
            Path script = PowerShellScripts.resolve("net-optimize.ps1");
            String presetArg = preset.getScriptName();
            ProcessResult pr = new ProcessRunner(60).run(
                    ProcessRunner.powershellScript(script.toString(), "-Preset", presetArg));
            String stdout = pr.stdout() != null ? pr.stdout().trim() : "";
            String combined = pr.combinedOutput();
            if (pr.exitCode() != 0) {
                // Try to parse results JSON to give per-setting details
                String details = combined;
                if (!stdout.isEmpty()) details = stdout;
                // Detect access denied hints
                if (combined.toLowerCase().contains("access") && combined.toLowerCase().contains("denied")) {
                    details += "\n\nTip: Run WinZenith as Administrator.";
                }
                return OperationResult.fail("Optimization failed with exit code " + pr.exitCode(), details);
            }
            logChange("Apply Optimization", preset.getDisplayName(), preset.getDescription(), true);
            return OperationResult.ok(preset.getDisplayName() + " applied successfully.", stdout);
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
            String out = pr.combinedOutput();
            if (out != null && out.toLowerCase().contains("access") && out.toLowerCase().contains("denied")) {
                return OperationResult.fail("Winsock reset requires Administrator privileges.", out);
            }
            if (ok) logChange("Reset Winsock", "Winsock catalog", "Reboot recommended.", true);
            return ok
                    ? OperationResult.ok("Winsock reset. Reboot recommended.", out)
                    : OperationResult.fail("Winsock reset failed.", out);
        } catch (Exception e) {
            AppLogger.warning("Failed to reset winsock: " + e.getMessage());
            return OperationResult.fail("Failed to reset winsock: " + e.getMessage());
        }
    }

    public OperationResult renewIp(String adapterName) {
        try {
            sanitizeName(adapterName);
            // Call ipconfig directly without cmd.exe to avoid cmd metachar issues; ProcessBuilder handles spaces via quoting
            ProcessResult release = new ProcessRunner(30).run(
                    List.of("ipconfig", "/release", adapterName));
            if (release.exitCode() != 0) {
                AppLogger.warning("ipconfig /release failed: " + release.combinedOutput());
                // Don't abort if adapter uses static IP; still try renew
                String relOut = release.combinedOutput();
                if (relOut != null && relOut.toLowerCase().contains("no operation can be performed")) {
                    // Adapter not DHCP - inform but continue
                }
            }
            ProcessResult renew = new ProcessRunner(30).run(
                    List.of("ipconfig", "/renew", adapterName));
            if (renew.exitCode() != 0) {
                return OperationResult.fail("IP renewal failed.",
                        "release: " + release.combinedOutput() + "\nrenew: " + renew.combinedOutput());
            }
            logChange("Renew IP", adapterName, "ipconfig /release + /renew", true);
            return OperationResult.ok("IP address renewed for " + adapterName + ".", renew.stdout());
        } catch (Exception e) {
            AppLogger.warning("Failed to renew IP: " + e.getMessage());
            return OperationResult.fail("Failed to renew IP: " + e.getMessage());
        }
    }

    public OperationResult setAdapterState(String adapterName, boolean enable) {
        try {
            sanitizeName(adapterName);
            String cmd = enable ? "Enable-NetAdapter" : "Disable-NetAdapter";
            // Use powershellScript approach with proper quoting via psQuote
            String psCmd = cmd + " -Name " + ProcessRunner.psQuote(adapterName) + " -Confirm:$false";
            ProcessResult pr = new ProcessRunner(30).run(powershellCommand(psCmd));
            String out = pr.combinedOutput();
            if (pr.exitCode() != 0) {
                if (out != null && out.toLowerCase().contains("access") && out.toLowerCase().contains("denied")) {
                    return OperationResult.fail("Requires Administrator: failed to " + (enable ? "enable" : "disable") + " adapter.", out);
                }
                return OperationResult.fail("Failed to " + (enable ? "enable" : "disable") + " adapter.", out);
            }
            logChange(enable ? "Enable Adapter" : "Disable Adapter", adapterName, "", true);
            return OperationResult.ok((enable ? "Enabled" : "Disabled") + " " + adapterName + ".", out);
        } catch (Exception e) {
            AppLogger.warning("Failed to set adapter state: " + e.getMessage());
            return OperationResult.fail("Failed to set adapter state: " + e.getMessage());
        }
    }

    public String getIpConfigAll() {
        try {
            // Use chcp 65001 to force UTF-8 output so ProcessRunner UTF-8 decoding is correct for localized systems (OEM CP850/866).
            // Single command string after /c is required for cmd.exe.
            ProcessResult pr = new ProcessRunner(30).run(
                    List.of("cmd.exe", "/c", "chcp 65001 >nul & ipconfig /all"));
            if (pr.exitCode() == 0) {
                String out = pr.stdout();
                // cmd may prefix with Active code page: 65001 line - strip it
                if (out != null) {
                    out = out.replaceFirst("(?m)^Active code page:.*\\R", "");
                }
                if (out != null && !out.isBlank()) return out;
                // Fallback to direct ipconfig if cmd wrapper produced empty
                ProcessResult pr2 = new ProcessRunner(30).run(List.of("ipconfig", "/all"));
                if (pr2.exitCode() == 0 && pr2.stdout() != null && !pr2.stdout().isBlank()) return pr2.stdout();
                return "No network information returned.";
            }
            // Fallback to direct ipconfig on cmd failure
            try {
                ProcessResult pr2 = new ProcessRunner(30).run(List.of("ipconfig", "/all"));
                if (pr2.exitCode() == 0 && pr2.stdout() != null && !pr2.stdout().isBlank()) return pr2.stdout();
            } catch (Exception ignored) {}
            String err = pr.stderr() != null ? pr.stderr() : pr.combinedOutput();
            return "Failed to retrieve network information:\n" + err;
        } catch (Exception e) {
            AppLogger.warning("Failed to get ipconfig: " + e.getMessage());
            return "Failed to retrieve network information: " + e.getMessage();
        }
    }

    public TcpSettings getCurrentTcpSettings() {
        try {
            ProcessResult pr = new ProcessRunner(30).run(powershellCommand("netsh int tcp show global"));
            String out = pr.stdout();
            if (out == null || out.isBlank()) {
                String combined = pr.combinedOutput();
                if (combined != null && combined.toLowerCase().contains("access") && combined.toLowerCase().contains("denied")) {
                    AppLogger.warning("netsh requires admin: " + combined);
                }
                return new TcpSettings(Map.of());
            }
            return TcpSettings.parse(out);
        } catch (Exception e) {
            AppLogger.warning("Failed to get TCP settings: " + e.getMessage());
            return new TcpSettings(Map.of());
        }
    }

    public List<String> getCurrentDnsServers(String adapterName) {
        try {
            sanitizeName(adapterName);
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
        try {
            sanitizeName(adapterName);
            String p1 = primaryDns != null ? primaryDns.trim() : "";
            String p2 = secondaryDns != null ? secondaryDns.trim() : "";
            if (!p1.isEmpty() && !isValidIpAddress(p1)) {
                return OperationResult.fail("Invalid primary DNS: " + p1);
            }
            if (!p2.isEmpty() && !isValidIpAddress(p2)) {
                return OperationResult.fail("Invalid secondary DNS: " + p2);
            }
            if (p1.isEmpty() && !p2.isEmpty()) {
                return OperationResult.fail("Primary DNS must be set if secondary is provided.");
            }
            Path script = PowerShellScripts.resolve("net-dns-set.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString(),
                            "-AdapterName", adapterName,
                            "-PrimaryDNS", p1,
                            "-SecondaryDNS", p2));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object success = data.get("success");
                boolean ok = success instanceof Boolean && (Boolean) success;
                String msg = str(data, "message");
                if (ok) {
                    String target = !p1.isEmpty() ? p1 + (!p2.isEmpty() ? ", " + p2 : "") : "DHCP";
                    logChange("Set DNS", adapterName, target, true);
                }
                return ok ? OperationResult.ok(msg, stdout) : OperationResult.fail(msg, stdout);
            }
            boolean ok = pr.exitCode() == 0;
            if (ok) logChange("Set DNS", adapterName, !p1.isEmpty() ? p1 : "DHCP", true);
            return ok
                    ? OperationResult.ok("DNS servers updated.")
                    : OperationResult.fail("DNS update failed with exit code " + pr.exitCode(), pr.combinedOutput());
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

    public AdapterProperties getAdapterProperties(String adapterName) {
        try {
            sanitizeName(adapterName);
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
        if (ssid.length() > 32) {
            return OperationResult.fail("SSID must be 32 characters or less.");
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

    private String sanitizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host is required");
        }
        String trimmed = host.trim();
        if (trimmed.length() > 253) {
            throw new IllegalArgumentException("Host too long");
        }
        // Allow percent-encoded? No, strict but include underscore/colon for IPv6
        if (!SAFE_HOST.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid host: " + host + " (allowed: letters, digits, dot, hyphen, underscore, colon)");
        }
        // Disallow leading hyphen/dot/colon
        if (trimmed.startsWith("-") || trimmed.startsWith(".") || trimmed.startsWith(":")) {
            throw new IllegalArgumentException("Invalid host: " + host);
        }
        return trimmed;
    }

    public static boolean isValidHost(String host) {
        if (host == null || host.isBlank()) return false;
        String t = host.trim();
        if (t.length() > 253) return false;
        if (!SAFE_HOST.matcher(t).matches()) return false;
        if (t.startsWith("-") || t.startsWith(".") || t.startsWith(":")) return false;
        return true;
    }

    public static boolean isValidIpAddress(String ip) {
        if (ip == null || ip.isBlank()) return false;
        String t = ip.trim();
        // IPv4 strict
        if (IPV4_PATTERN.matcher(t).matches()) return true;
        // IPv6 loose check: hex, colons, at least 2 colons, no DNS lookup to avoid blocking
        if (t.contains(":")) {
            String ipv6 = t.toLowerCase();
            // Must be hex digits and colons only, allow :: compression
            if (ipv6.matches("^[0-9a-f:]+$") && ipv6.chars().filter(ch -> ch == ':').count() >= 2) {
                // Disallow triple colon and invalid chars already filtered
                if (ipv6.contains(":::")) return false;
                // Basic segment length check (each segment <=4 hex chars)
                String[] parts = ipv6.split(":", -1);
                for (String part : parts) {
                    if (part.length() > 4) return false;
                    if (!part.isEmpty() && !part.matches("^[0-9a-f]{1,4}$")) return false;
                }
                return true;
            }
        }
        return false;
    }

    public PingResult ping(String host, int count) {
        try {
            sanitizeHost(host);
            if (count < 1 || count > 50) throw new IllegalArgumentException("Count must be 1-50");
            Path script = PowerShellScripts.resolve("net-ping.ps1");
            ProcessResult pr = new ProcessRunner(30 + (long) count * 5).run(
                    ProcessRunner.powershellScript(script.toString(), "-TargetHost", host, "-Count", String.valueOf(count)));
            String stdout = pr.stdout() != null ? pr.stdout().trim() : "";
            if (!stdout.isEmpty()) {
                try {
                    Map<String, Object> data = mapper.readValue(stdout,
                            new TypeReference<Map<String, Object>>() {});
                    if (data.containsKey("error")) {
                        return PingResult.fail(host, str(data, "error"));
                    }
                    if (data.containsKey("host")) {
                        return new PingResult(
                                host,
                                data.get("packetsSent") instanceof Number n ? n.intValue() : 0,
                                data.get("packetsReceived") instanceof Number n ? n.intValue() : 0,
                                data.get("packetLossPercent") instanceof Number n ? n.intValue() : 100,
                                data.get("minMs") instanceof Number n ? n.doubleValue() : 0,
                                data.get("maxMs") instanceof Number n ? n.doubleValue() : 0,
                                data.get("avgMs") instanceof Number n ? n.doubleValue() : 0,
                                str(data, "rawOutput"));
                    }
                } catch (Exception je) {
                    AppLogger.warning("Failed to parse ping JSON: " + je.getMessage() + " stdout=" + stdout);
                }
            }
            String err = pr.stderr() != null && !pr.stderr().isBlank() ? pr.stderr() : stdout;
            return PingResult.fail(host, err.isEmpty() ? "No output from ping." : err);
        } catch (IllegalArgumentException e) {
            return PingResult.fail(host, e.getMessage());
        } catch (Exception e) {
            AppLogger.warning("Failed to ping: " + e.getMessage());
            return PingResult.fail(host, "Error: " + e.getMessage());
        }
    }

    public List<TracerouteHop> traceroute(String host, int maxHops) {
        try {
            sanitizeHost(host);
            if (maxHops < 1 || maxHops > 30) maxHops = 30;
            Path script = PowerShellScripts.resolve("net-traceroute.ps1");
            ProcessResult pr = new ProcessRunner(60 + (long) maxHops * 5).run(
                    ProcessRunner.powershellScript(script.toString(), "-TargetHost", host, "-MaxHops", String.valueOf(maxHops)));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty() && !"[]".equals(stdout)) {
                List<Map<String, Object>> raw = mapper.readValue(stdout,
                        new TypeReference<List<Map<String, Object>>>() {});
                List<TracerouteHop> hops = new ArrayList<>();
                for (Map<String, Object> entry : raw) {
                    try {
                        hops.add(new TracerouteHop(
                                entry.get("hopNumber") instanceof Number n ? n.intValue() : 0,
                                str(entry, "address"),
                                str(entry, "latency1"),
                                str(entry, "latency2"),
                                str(entry, "latency3")));
                    } catch (Exception e) {
                        AppLogger.warning("Failed to parse traceroute hop: " + e.getMessage());
                    }
                }
                return hops;
            }
        } catch (IllegalArgumentException e) {
            AppLogger.warning("Invalid host for traceroute: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            AppLogger.warning("Failed to traceroute: " + e.getMessage());
        }
        return List.of();
    }

}
